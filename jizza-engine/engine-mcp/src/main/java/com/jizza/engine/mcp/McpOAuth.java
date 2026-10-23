package com.jizza.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.connectors.config.ConnectorProperties;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import com.jizza.engine.connectors.oauth.Pkce;
import com.jizza.engine.persistence.entity.McpServerEntity;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The MCP authorization flow for a remote server, as Claude runs it for a custom connector:
 * discover the server's authorization server (RFC 9728, then RFC 8414), register Jizza as a client
 * where the server allows it (RFC 7591), then the authorization-code flow with PKCE, naming the
 * server as the token's audience (RFC 8707).
 *
 * <p>It is as hardened as a connection's handshake: the state is random, single-use, expiring and
 * bound to the session that started it; the PKCE verifier never leaves the server; every address
 * goes through the guarded client; and tokens are envelope-encrypted before they are stored.
 */
public class McpOAuth {

  private static final Logger log = LoggerFactory.getLogger(McpOAuth.class);

  static final String CALLBACK_PATH = "/api/mcp/oauth/callback";
  /** A token this close to expiry is renewed before it is used. */
  private static final Duration REFRESH_SKEW = Duration.ofMinutes(1);

  private final GuardedHttpClient http;
  private final EncryptionService encryption;
  private final ConnectorProperties props;

  public McpOAuth(GuardedHttpClient http, EncryptionService encryption, ConnectorProperties props) {
    this.http = http;
    this.encryption = encryption;
    this.props = props;
  }

  public String redirectUri() {
    String base = props.getCallbackBaseUrl();
    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return base + CALLBACK_PATH;
  }

  /** Discovers and registers when needed, records a fresh state, and returns where to sign in. */
  public URI start(McpServerEntity server, UUID sessionId) {
    if (server.getOauthAuthorizationUrl() == null || server.getOauthTokenUrl() == null
        || server.getOauthClientId() == null) {
      String registration = discover(server);
      if (server.getOauthClientId() == null) register(server, registration);
    }

    String state = Pkce.newState();
    String verifier = Pkce.newVerifier();
    server.setOauthState(state);
    server.setOauthVerifier(encryption.encryptString(verifier));
    server.setOauthSessionId(sessionId);
    server.setOauthStateExpiresAt(Instant.now().plus(props.getStateTtl()));

    Map<String, String> params = new LinkedHashMap<>();
    params.put("response_type", "code");
    params.put("client_id", server.getOauthClientId());
    params.put("redirect_uri", redirectUri());
    params.put("state", state);
    params.put("code_challenge", Pkce.challenge(verifier));
    params.put("code_challenge_method", "S256");
    params.put("resource", resourceOf(server));
    if (server.getOauthScopes() != null && !server.getOauthScopes().isBlank()) {
      params.put("scope", String.join(" ", server.getOauthScopes().strip().split("[,\\s]+")));
    }
    String url = server.getOauthAuthorizationUrl();
    return URI.create(url + (url.contains("?") ? "&" : "?") + form(params));
  }

  /** The redirect's half: checks the state, exchanges the code and stores the tokens. */
  public void complete(McpServerEntity server, String code) {
    if (server.getOauthStateExpiresAt() == null
        || server.getOauthStateExpiresAt().isBefore(Instant.now())) {
      clearState(server);
      throw new AuthException("oauth-state", "That sign-in attempt expired. Try again.");
    }
    String verifier = encryption.decryptString(server.getOauthVerifier());
    clearState(server);

    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", redirectUri());
    form.put("client_id", server.getOauthClientId());
    form.put("code_verifier", verifier);
    form.put("resource", resourceOf(server));
    String secret = encryption.decryptString(server.getOauthClientSecret());
    if (secret != null) form.put("client_secret", secret);
    storeTokens(server, exchange(server, form, "sign-in"), true);
  }

  /** True when the stored token is missing or about to expire and a refresh token can renew it. */
  public boolean needsRefresh(McpServerEntity server) {
    if (server.getRefreshToken() == null) return false;
    Instant expiry = server.getTokenExpiresAt();
    return expiry != null && expiry.minus(REFRESH_SKEW).isBefore(Instant.now());
  }

  /** Renews the access token, rotating the refresh token when the server issues a new one. */
  public void refresh(McpServerEntity server) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", encryption.decryptString(server.getRefreshToken()));
    form.put("client_id", server.getOauthClientId());
    form.put("resource", resourceOf(server));
    String secret = encryption.decryptString(server.getOauthClientSecret());
    if (secret != null) form.put("client_secret", secret);
    storeTokens(server, exchange(server, form, "refresh"), false);
  }

  public String accessToken(McpServerEntity server) {
    return encryption.decryptString(server.getAccessToken());
  }

  public static void clearState(McpServerEntity server) {
    server.setOauthState(null);
    server.setOauthVerifier(null);
    server.setOauthSessionId(null);
    server.setOauthStateExpiresAt(null);
  }

  // ── Discovery and registration ────────────────────────────────────────────────

  /**
   * Finds the authorization and token endpoints. The protected-resource document names the
   * authorization server; a server without one is its own (the 2025-03-26 revision of MCP), and a
   * server publishing no metadata at all gets that revision's default paths.
   *
   * @return the registration endpoint, or null when the server has none
   */
  String discover(McpServerEntity server) {
    URI mcp = URI.create(server.getUrl());
    String origin = mcp.getScheme() + "://" + mcp.getRawAuthority();
    String path = mcp.getRawPath() == null ? "" : mcp.getRawPath().replaceAll("/+$", "");

    String issuer = origin;
    JsonNode resource = firstJson(List.of(
        origin + "/.well-known/oauth-protected-resource" + path,
        origin + "/.well-known/oauth-protected-resource"));
    if (resource != null) {
      if (resource.path("authorization_servers").isArray()
          && !resource.path("authorization_servers").isEmpty()) {
        issuer = resource.path("authorization_servers").get(0).asText().replaceAll("/+$", "");
      }
      if (resource.hasNonNull("resource")) server.setOauthResource(resource.path("resource").asText());
      if ((server.getOauthScopes() == null || server.getOauthScopes().isBlank())
          && resource.path("scopes_supported").isArray()) {
        List<String> scopes = new ArrayList<>();
        resource.path("scopes_supported").forEach(s -> scopes.add(s.asText()));
        if (!scopes.isEmpty()) server.setOauthScopes(String.join(" ", scopes));
      }
    }

    URI as = URI.create(issuer);
    String asOrigin = as.getScheme() + "://" + as.getRawAuthority();
    String asPath = as.getRawPath() == null ? "" : as.getRawPath().replaceAll("/+$", "");
    List<String> candidates = new ArrayList<>();
    candidates.add(asOrigin + "/.well-known/oauth-authorization-server" + asPath);
    candidates.add(asOrigin + "/.well-known/openid-configuration" + asPath);
    if (!asPath.isEmpty()) {
      candidates.add(asOrigin + asPath + "/.well-known/openid-configuration");
      candidates.add(asOrigin + "/.well-known/oauth-authorization-server");
    }
    JsonNode metadata = firstJson(candidates);
    if (metadata != null && metadata.hasNonNull("authorization_endpoint")
        && metadata.hasNonNull("token_endpoint")) {
      server.setOauthAuthorizationUrl(metadata.path("authorization_endpoint").asText());
      server.setOauthTokenUrl(metadata.path("token_endpoint").asText());
      if (metadata.has("code_challenge_methods_supported")
          && !metadata.path("code_challenge_methods_supported").toString().contains("S256")) {
        throw new ValidationException("This server's sign-in does not support PKCE, which Jizza "
            + "requires");
      }
      return metadata.path("registration_endpoint").asText(null);
    }
    server.setOauthAuthorizationUrl(asOrigin + "/authorize");
    server.setOauthTokenUrl(asOrigin + "/token");
    return asOrigin + "/register";
  }

  /** Dynamic client registration, as a public client that proves itself with PKCE. */
  void register(McpServerEntity server, String registrationEndpoint) {
    if (registrationEndpoint == null) {
      throw new ValidationException("This server does not let apps register themselves. Add a "
          + "client id from the service's developer settings, then sign in again.");
    }
    var body = Json.obj().put("client_name", "Jizza")
        .put("token_endpoint_auth_method", "none");
    body.putArray("redirect_uris").add(redirectUri());
    body.putArray("grant_types").add("authorization_code").add("refresh_token");
    body.putArray("response_types").add("code");
    if (server.getOauthScopes() != null) body.put("scope", server.getOauthScopes());
    GuardedHttpClient.Response response = http.send("POST", URI.create(registrationEndpoint),
        Map.of("content-type", "application/json", "accept", "application/json"),
        body.toString(), null);
    if (!response.ok() || !response.json().hasNonNull("client_id")) {
      throw new ValidationException("The server refused to register Jizza (HTTP "
          + response.status() + "). Add a client id from the service's developer settings, then "
          + "sign in again.");
    }
    server.setOauthClientId(response.json().path("client_id").asText());
    String secret = response.json().path("client_secret").asText(null);
    if (secret != null) server.setOauthClientSecret(encryption.encryptString(secret));
  }

  private JsonNode firstJson(List<String> urls) {
    for (String url : urls) {
      try {
        GuardedHttpClient.Response response = http.send("GET", URI.create(url),
            Map.of("accept", "application/json"), null, null);
        if (response.ok()) {
          JsonNode json = response.json();
          if (json.isObject() && !json.has("raw")) return json;
        }
      } catch (RuntimeException e) {
        log.debug("No OAuth metadata at {}: {}", url, e.getMessage());
      }
    }
    return null;
  }

  // ── Tokens ────────────────────────────────────────────────────────────────────

  private JsonNode exchange(McpServerEntity server, Map<String, String> form, String purpose) {
    GuardedHttpClient.Response response = http.send("POST",
        URI.create(server.getOauthTokenUrl()),
        Map.of("content-type", "application/x-www-form-urlencoded", "accept", "application/json"),
        form(form), null);
    if (!response.ok()) {
      // The body can echo a secret back, so only the status is reported.
      throw new AuthException("oauth-" + purpose, server.getName() + " rejected the " + purpose
          + " request with HTTP " + response.status());
    }
    return response.json();
  }

  private void storeTokens(McpServerEntity server, JsonNode tokens, boolean initial) {
    String access = tokens.path("access_token").asText(null);
    if (access == null) {
      throw new AuthException("oauth-token", server.getName() + " did not return an access token");
    }
    server.setAccessToken(encryption.encryptString(access));
    String refresh = tokens.path("refresh_token").asText(null);
    if (refresh != null) server.setRefreshToken(encryption.encryptString(refresh));
    else if (initial) server.setRefreshToken(null);
    long expiresIn = tokens.path("expires_in").asLong(0);
    server.setTokenExpiresAt(expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
  }

  private static String resourceOf(McpServerEntity server) {
    return server.getOauthResource() != null ? server.getOauthResource() : server.getUrl();
  }

  private static String form(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    params.forEach((key, value) -> {
      if (value == null) return;
      if (!sb.isEmpty()) sb.append('&');
      sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    });
    return sb.toString();
  }
}
