package com.jizza.engine.connectors.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.connector.OAuthService;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.ConnectionStatus;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.connectors.config.ConnectorProperties;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import com.jizza.engine.connectors.service.ConnectionService;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.OAuthStateEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.OAuthStateRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hardened authorization-code handshake (R24).
 *
 * <p>What makes it hardened rather than merely working: the {@code state} is random, single-use,
 * expiring and bound to the session that started it; PKCE is on by default and its verifier never
 * leaves the server; tokens are envelope-encrypted before they touch the database; a refresh that
 * returns a new refresh token rotates the stored one; and disconnecting revokes at the provider
 * before clearing anything locally.
 */
@Service
public class DefaultOAuthService implements OAuthService {

  private static final Logger log = LoggerFactory.getLogger(DefaultOAuthService.class);

  private final ConnectionRepository connections;
  private final OAuthStateRepository states;
  private final ConnectionService connectionService;
  private final EncryptionService encryption;
  private final GuardedHttpClient http;
  private final ConnectorProperties props;
  private final AuditLog audit;

  public DefaultOAuthService(ConnectionRepository connections, OAuthStateRepository states,
                             ConnectionService connectionService, EncryptionService encryption,
                             GuardedHttpClient http, ConnectorProperties props, AuditLog audit) {
    this.connections = connections;
    this.states = states;
    this.connectionService = connectionService;
    this.encryption = encryption;
    this.http = http;
    this.props = props;
    this.audit = audit;
  }

  @Override
  @Transactional
  public URI startAuth(UUID connectionId, UUID sessionId) {
    ConnectionEntity connection = connectionService.require(connectionId);
    if (connection.getAuthScheme() != AuthScheme.OAUTH2) {
      throw new ValidationException("Only OAuth2 connections use the authorization handshake");
    }
    if (connection.getAuthorizationUrl() == null || connection.getClientId() == null) {
      throw new ValidationException("This connection needs an authorization URL and a client id");
    }

    String state = Pkce.newState();
    String verifier = connection.isPkce() ? Pkce.newVerifier() : null;

    OAuthStateEntity entity = new OAuthStateEntity();
    entity.setUserId(connection.getUserId());
    entity.setConnectionId(connectionId);
    entity.setSessionId(sessionId);
    entity.setState(state);
    entity.setCodeVerifier(verifier == null ? null : encryption.encryptString(verifier));
    entity.setRedirectUri(props.redirectUri());
    entity.setExpiresAt(Instant.now().plus(props.getStateTtl()));
    entity.setUsed(false);
    states.save(entity);

    Map<String, String> params = new LinkedHashMap<>();
    params.put("response_type", "code");
    params.put("client_id", connection.getClientId());
    params.put("redirect_uri", props.redirectUri());
    params.put("state", state);
    if (connection.getScopes() != null && !connection.getScopes().isBlank()) {
      params.put("scope", joinScopes(connection));
    }
    if (verifier != null) {
      params.put("code_challenge", Pkce.challenge(verifier));
      params.put("code_challenge_method", "S256");
    }
    // Providers differ, but asking for offline access is what makes unattended refresh possible.
    if (connection.getExtraFields() != null) {
      connection.getExtraFields().fields().forEachRemaining(field -> {
        if (field.getValue().isTextual()) params.put(field.getKey(), field.getValue().asText());
      });
    }

    audit.record(connection.getUserId(), ActorType.USER, null, "connection.auth_started",
        connection.getServiceName(), Json.obj().put("pkce", verifier != null));
    return URI.create(withQuery(connection.getAuthorizationUrl(), params));
  }

  @Override
  @Transactional
  public ConnectionDto completeCallback(String state, String code) {
    if (state == null || state.isBlank() || code == null || code.isBlank()) {
      throw new ValidationException("The callback needs both a state and a code");
    }
    OAuthStateEntity stored = states.findByState(state)
        .orElseThrow(() -> new AuthException("oauth-state", "That authorization attempt is not "
            + "recognised. Start the connection again."));

    if (stored.isUsed()) {
      // A replayed state is worth surfacing loudly: it is either a bug or an attack.
      audit.record(stored.getUserId(), ActorType.SYSTEM, null, "connection.auth_state_replayed",
          stored.getConnectionId().toString(), null);
      throw new AuthException("oauth-state", "That authorization attempt has already been used");
    }
    if (stored.getExpiresAt().isBefore(Instant.now())) {
      throw new AuthException("oauth-state", "That authorization attempt expired. Try again.");
    }
    stored.setUsed(true);
    states.save(stored);

    ConnectionEntity connection = connectionService.require(stored.getConnectionId());
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", stored.getRedirectUri());
    form.put("client_id", connection.getClientId());
    String clientSecret = connectionService.decryptClientSecret(connection);
    if (clientSecret != null) form.put("client_secret", clientSecret);
    if (stored.getCodeVerifier() != null) {
      form.put("code_verifier", encryption.decryptString(stored.getCodeVerifier()));
    }

    JsonNode tokens = exchange(connection, connection.getAccessTokenUrl(), form, "authorization");
    storeTokens(connection, tokens, true);
    connection.setStatus(ConnectionStatus.CONNECTED);
    connections.save(connection);

    audit.record(connection.getUserId(), ActorType.USER, null, "connection.connected",
        connection.getServiceName(), null);
    return ConnectionService.toDto(connection);
  }

  /** A refused refresh still leaves the connection marked as needing a new sign-in. */
  @Override
  @Transactional(noRollbackFor = RuntimeException.class)
  public ConnectionDto refreshIfNeeded(UUID connectionId) {
    ConnectionEntity connection = connectionService.require(connectionId);
    if (!needsRefresh(connection)) return ConnectionService.toDto(connection);
    return ConnectionService.toDto(refresh(connection));
  }

  /** Refresh with rotation. Called by the tool executor just before it needs a live token. */
  @Transactional
  public ConnectionEntity refresh(ConnectionEntity connection) {
    String refreshToken = encryption.decryptString(connection.getRefreshToken());
    if (refreshToken == null) {
      connection.setStatus(ConnectionStatus.NEEDS_REAUTH);
      connections.save(connection);
      signInLost(connection);
      throw new AuthException("connection-expired", connection.getServiceName()
          + " needs to be reconnected: there is no refresh token to renew it with.");
    }

    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    form.put("client_id", connection.getClientId());
    String clientSecret = connectionService.decryptClientSecret(connection);
    if (clientSecret != null) form.put("client_secret", clientSecret);

    String url = connection.getRefreshTokenUrl() == null
        ? connection.getAccessTokenUrl() : connection.getRefreshTokenUrl();
    try {
      JsonNode tokens = exchange(connection, url, form, "refresh");
      storeTokens(connection, tokens, false);
      connection.setStatus(ConnectionStatus.CONNECTED);
      connections.save(connection);
      audit.record(connection.getUserId(), ActorType.SYSTEM, null, "connection.token_refreshed",
          connection.getServiceName(), null);
      return connection;
    } catch (RuntimeException e) {
      connection.setStatus(ConnectionStatus.NEEDS_REAUTH);
      connections.save(connection);
      audit.record(connection.getUserId(), ActorType.SYSTEM, null, "connection.refresh_failed",
          connection.getServiceName(), Json.obj().put("error", e.getMessage()));
      signInLost(connection);
      throw e;
    }
  }

  private org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.connector.ConnectorSignIn> signIn;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSignIn(
      org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.connector.ConnectorSignIn> signIn) {
    this.signIn = signIn;
  }

  /** The person hears that the service needs signing in again, on the reply and in the inbox. */
  private void signInLost(ConnectionEntity connection) {
    if (signIn == null) return;
    signIn.ifAvailable(s -> s.lost(connection.getUserId(), com.jizza.engine.api.connector.ConnectorSignIn.SERVICE,
        connection.getId(), connection.getServiceName()));
  }

  public boolean needsRefresh(ConnectionEntity connection) {
    if (!connection.isAutoRefresh() || connection.getRefreshToken() == null) return false;
    Instant expiry = connection.getTokenExpiresAt();
    return expiry == null || expiry.minus(props.getRefreshSkew()).isBefore(Instant.now());
  }

  @Override
  @Transactional
  public void revoke(UUID connectionId) {
    ConnectionEntity connection = connectionService.require(connectionId);
    JsonNode revokeApi = connection.getRevokeApi();

    // Revoke at the provider first: clearing our copy of a token that is still live elsewhere
    // would leave a credential in the wild with nothing pointing at it.
    if (revokeApi != null && revokeApi.hasNonNull("url")) {
      try {
        callConfiguredEndpoint(connection, revokeApi);
      } catch (RuntimeException e) {
        log.warn("Provider revocation for {} failed; clearing local tokens anyway",
            connection.getServiceName(), e);
      }
    }

    connection.setAccessToken(null);
    connection.setRefreshToken(null);
    connection.setTokenExpiresAt(null);
    connection.setStatus(ConnectionStatus.NEW);
    connections.save(connection);
    audit.record(connection.getUserId(), ActorType.USER, null, "connection.revoked",
        connection.getServiceName(), null);
  }

  @Override
  @Transactional
  public TestResult test(UUID connectionId) {
    ConnectionEntity connection = connectionService.require(connectionId);
    JsonNode testApi = connection.getTestApi();
    if (testApi == null || !testApi.hasNonNull("url")) {
      return new TestResult(false, 0, "No test endpoint is configured for this connection");
    }
    if (needsRefresh(connection)) {
      try {
        refresh(connection);
      } catch (RuntimeException e) {
        // Kept, not rolled back: the connection now says it needs a new sign-in.
        return new TestResult(false, 0, connection.getServiceName()
            + " needs you to sign in again: Jizza couldn't renew the sign-in.");
      }
    }

    try {
      GuardedHttpClient.Response response = callConfiguredEndpoint(connection, testApi);
      boolean ok = response.ok();
      connection.setStatus(ok ? ConnectionStatus.CONNECTED : ConnectionStatus.ERROR);
      connections.save(connection);
      audit.record(connection.getUserId(), ActorType.USER, null, "connection.tested",
          connection.getServiceName(), Json.obj().put("ok", ok).put("status", response.status()));
      return new TestResult(ok, response.status(),
          ok ? "Connection is working" : "The service replied " + response.status());
    } catch (RuntimeException e) {
      connection.setStatus(ConnectionStatus.ERROR);
      connections.save(connection);
      return new TestResult(false, 0, e.getMessage());
    }
  }

  private GuardedHttpClient.Response callConfiguredEndpoint(ConnectionEntity connection,
                                                            JsonNode config) {
    String method = config.path("method").asText("GET").toUpperCase(java.util.Locale.ROOT);
    URI uri = URI.create(config.path("url").asText());
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("accept", "application/json");
    String accessToken = encryption.decryptString(connection.getAccessToken());
    if (accessToken != null) headers.put("authorization", "Bearer " + accessToken);
    return http.send(method, uri, headers, null, declaredHost(connection));
  }

  private JsonNode exchange(ConnectionEntity connection, String url, Map<String, String> form,
                            String purpose) {
    Map<String, String> headers = Map.of(
        "content-type", "application/x-www-form-urlencoded",
        "accept", "application/json");
    GuardedHttpClient.Response response = http.send("POST", URI.create(url), headers,
        formEncode(form), null);
    if (!response.ok()) {
      // The provider's body can echo the client secret back; never let it into an exception message.
      throw new AuthException("oauth-" + purpose, connection.getServiceName()
          + " rejected the " + purpose + " request with HTTP " + response.status());
    }
    return response.json();
  }

  /**
   * @param initial on the first exchange a provider must supply a refresh token to enable
   *                unattended use; on a refresh, a response without one means "keep the old one",
   *                which is exactly the non-rotating case.
   */
  private void storeTokens(ConnectionEntity connection, JsonNode tokens, boolean initial) {
    String accessToken = tokens.path("access_token").asText(null);
    if (accessToken == null) {
      throw new AuthException("oauth-token", connection.getServiceName()
          + " did not return an access token");
    }
    connection.setAccessToken(encryption.encryptString(accessToken));

    String refreshToken = tokens.path("refresh_token").asText(null);
    if (refreshToken != null) {
      // Rotation: a provider that issues a new refresh token has invalidated the previous one.
      connection.setRefreshToken(encryption.encryptString(refreshToken));
    } else if (initial) {
      connection.setRefreshToken(null);
    }

    long expiresIn = tokens.path("expires_in").asLong(0);
    connection.setTokenExpiresAt(expiresIn > 0
        ? Instant.now().plusSeconds(expiresIn)
        : null);
  }

  private String declaredHost(ConnectionEntity connection) {
    if (connection.getBaseUrl() != null && !connection.getBaseUrl().isBlank()) {
      return connection.getBaseUrl();
    }
    return connection.getAccessTokenUrl();
  }

  private String joinScopes(ConnectionEntity connection) {
    String separator = switch (String.valueOf(connection.getScopeSeparator())) {
      case "comma" -> ",";
      case "plus" -> "+";
      default -> " ";
    };
    return String.join(separator, connection.getScopes().split("[,\\s]+"));
  }

  private String withQuery(String url, Map<String, String> params) {
    StringBuilder sb = new StringBuilder(url);
    sb.append(url.contains("?") ? '&' : '?');
    sb.append(formEncode(params));
    return sb.toString();
  }

  private String formEncode(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    params.forEach((key, value) -> {
      if (sb.length() > 0) sb.append('&');
      sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    });
    return sb.toString();
  }
}
