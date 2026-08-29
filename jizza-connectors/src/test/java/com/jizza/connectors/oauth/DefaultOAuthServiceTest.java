package com.jizza.connectors.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.crypto.EncryptionService;
import com.jizza.common.enums.AuthScheme;
import com.jizza.common.enums.ConnectionStatus;
import com.jizza.common.error.AuthException;
import com.jizza.common.util.Json;
import com.jizza.connectors.config.ConnectorProperties;
import com.jizza.connectors.http.GuardedHttpClient;
import com.jizza.connectors.service.ConnectionService;
import com.jizza.persistence.entity.ConnectionEntity;
import com.jizza.persistence.entity.OAuthStateEntity;
import com.jizza.persistence.repository.ConnectionRepository;
import com.jizza.persistence.repository.OAuthStateRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultOAuthServiceTest {

  /** Reversible stand-in for envelope encryption, so stored values stay assertable in a unit test. */
  private static final EncryptionService REVERSIBLE = new EncryptionService() {
    @Override public byte[] encrypt(byte[] plain) {
      byte[] out = new byte[plain.length];
      for (int i = 0; i < plain.length; i++) out[i] = (byte) (plain[i] ^ 0x5A);
      return out;
    }

    @Override public byte[] decrypt(byte[] ct) { return encrypt(ct); }
  };

  private final UUID userId = UUID.randomUUID();
  private final List<OAuthStateEntity> stateRows = new ArrayList<>();

  private ConnectionEntity connection;
  private ConnectionRepository connections;
  private OAuthStateRepository states;
  private GuardedHttpClient http;
  private AuditLog audit;
  private ConnectorProperties props;
  private DefaultOAuthService oauth;

  @BeforeEach
  void setUp() {
    connection = new ConnectionEntity();
    connection.setId(UUID.randomUUID());
    connection.setUserId(userId);
    connection.setServiceName("Example");
    connection.setAuthScheme(AuthScheme.OAUTH2);
    connection.setClientId("client-abc");
    connection.setClientSecret(REVERSIBLE.encryptString("super-secret"));
    connection.setAuthorizationUrl("https://auth.example.com/authorize");
    connection.setAccessTokenUrl("https://auth.example.com/token");
    connection.setScopes("read.profile write.calendar");
    connection.setScopeSeparator("space");
    connection.setPkce(true);
    connection.setAutoRefresh(true);
    connection.setStatus(ConnectionStatus.NEW);

    connections = mock(ConnectionRepository.class);
    when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));
    when(connections.save(any())).thenAnswer(inv -> inv.getArgument(0));

    states = mock(OAuthStateRepository.class);
    when(states.save(any())).thenAnswer(inv -> {
      OAuthStateEntity e = inv.getArgument(0);
      if (e.getId() == null) e.setId(UUID.randomUUID());
      stateRows.removeIf(r -> r.getState().equals(e.getState()));
      stateRows.add(e);
      return e;
    });
    when(states.findByState(any())).thenAnswer(inv ->
        stateRows.stream().filter(s -> s.getState().equals(inv.getArgument(0))).findFirst());

    http = mock(GuardedHttpClient.class);
    audit = mock(AuditLog.class);
    props = new ConnectorProperties();
    props.setCallbackBaseUrl("https://jizza.example.ngrok.app");

    ConnectionService connectionService =
        new ConnectionService(connections, REVERSIBLE, audit);
    oauth = new DefaultOAuthService(connections, states, connectionService, REVERSIBLE, http,
        props, audit);
  }

  private Map<String, String> queryOf(URI uri) {
    Map<String, String> params = new HashMap<>();
    for (String pair : uri.getQuery().split("&")) {
      int eq = pair.indexOf('=');
      params.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return params;
  }

  private void tokenEndpointReturns(String json) {
    when(http.send(eq("POST"), any(), any(), any(), any()))
        .thenReturn(new GuardedHttpClient.Response(200, json, Map.of()));
  }

  @Test
  void buildsAnAuthorizeUrlWithSingleUseStateAndPkce() {
    UUID sessionId = UUID.randomUUID();

    URI uri = oauth.startAuth(connection.getId(), sessionId);
    Map<String, String> params = queryOf(uri);

    assertThat(uri.toString()).startsWith("https://auth.example.com/authorize?");
    assertThat(params.get("response_type")).isEqualTo("code");
    assertThat(params.get("client_id")).isEqualTo("client-abc");
    assertThat(params.get("redirect_uri")).isEqualTo("https://jizza.example.ngrok.app/api/oauth/callback");
    assertThat(params.get("scope")).isEqualTo("read.profile write.calendar");
    assertThat(params.get("code_challenge_method")).isEqualTo("S256");
    assertThat(params).doesNotContainKey("client_secret");

    OAuthStateEntity stored = stateRows.get(0);
    assertThat(stored.getState()).isEqualTo(params.get("state"));
    assertThat(stored.getSessionId()).isEqualTo(sessionId);
    assertThat(stored.isUsed()).isFalse();
    assertThat(stored.getExpiresAt()).isAfter(Instant.now());

    // The challenge is derived from the verifier we kept; the verifier itself never left.
    String verifier = REVERSIBLE.decryptString(stored.getCodeVerifier());
    assertThat(Pkce.challenge(verifier)).isEqualTo(params.get("code_challenge"));
  }

  @Test
  void storedStateIsBoundToTheSessionAndCodeVerifierIsEncrypted() {
    oauth.startAuth(connection.getId(), UUID.randomUUID());
    OAuthStateEntity stored = stateRows.get(0);
    assertThat(new String(stored.getCodeVerifier(), StandardCharsets.UTF_8))
        .isNotEqualTo(REVERSIBLE.decryptString(stored.getCodeVerifier()));
  }

  @Test
  void completesTheHandshakeAndStoresTokensEncrypted() {
    oauth.startAuth(connection.getId(), UUID.randomUUID());
    String state = stateRows.get(0).getState();
    tokenEndpointReturns("""
        {"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"token_type":"Bearer"}
        """);

    var dto = oauth.completeCallback(state, "auth-code-1");

    assertThat(dto.status()).isEqualTo(ConnectionStatus.CONNECTED);
    assertThat(dto.tokenPresent()).isTrue();
    assertThat(REVERSIBLE.decryptString(connection.getAccessToken())).isEqualTo("at-1");
    assertThat(REVERSIBLE.decryptString(connection.getRefreshToken())).isEqualTo("rt-1");
    assertThat(new String(connection.getAccessToken(), StandardCharsets.UTF_8))
        .doesNotContain("at-1");
    assertThat(connection.getTokenExpiresAt()).isAfter(Instant.now());
  }

  @Test
  void theTokenExchangeSendsTheVerifierAndTheSecretButNeverToTheBrowser() {
    oauth.startAuth(connection.getId(), UUID.randomUUID());
    String state = stateRows.get(0).getState();
    String verifier = REVERSIBLE.decryptString(stateRows.get(0).getCodeVerifier());
    tokenEndpointReturns("{\"access_token\":\"at-1\",\"expires_in\":60}");

    var dto = oauth.completeCallback(state, "auth-code-1");

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(http).send(eq("POST"), any(), any(), body.capture(), any());
    assertThat(body.getValue())
        .contains("grant_type=authorization_code")
        .contains("code=auth-code-1")
        .contains("code_verifier=" + verifier)
        .contains("client_secret=super-secret");
    assertThat(Json.write(dto)).doesNotContain("super-secret").doesNotContain("at-1");
  }

  @Test
  void refusesAnUnknownState() {
    assertThatThrownBy(() -> oauth.completeCallback("never-issued", "code"))
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("not recognised");
    verify(http, never()).send(any(), any(), any(), any(), any());
  }

  @Test
  void refusesAReplayedState() {
    oauth.startAuth(connection.getId(), UUID.randomUUID());
    String state = stateRows.get(0).getState();
    tokenEndpointReturns("{\"access_token\":\"at-1\",\"expires_in\":60}");
    oauth.completeCallback(state, "code-1");

    assertThatThrownBy(() -> oauth.completeCallback(state, "code-1"))
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("already been used");
    verify(audit).record(eq(userId), any(), any(), eq("connection.auth_state_replayed"), any(), any());
  }

  @Test
  void refusesAnExpiredState() {
    oauth.startAuth(connection.getId(), UUID.randomUUID());
    stateRows.get(0).setExpiresAt(Instant.now().minusSeconds(1));

    assertThatThrownBy(() -> oauth.completeCallback(stateRows.get(0).getState(), "code"))
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void aProviderErrorDuringExchangeDoesNotLeakTheResponseBody() {
    oauth.startAuth(connection.getId(), UUID.randomUUID());
    when(http.send(eq("POST"), any(), any(), any(), any())).thenReturn(
        new GuardedHttpClient.Response(400, "{\"error\":\"invalid_client\","
            + "\"client_secret\":\"super-secret\"}", Map.of()));

    assertThatThrownBy(() -> oauth.completeCallback(stateRows.get(0).getState(), "code"))
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("HTTP 400")
        .hasMessageNotContaining("super-secret");
  }

  @Test
  void refreshRotatesTheRefreshTokenWhenTheProviderIssuesANewOne() {
    connection.setRefreshToken(REVERSIBLE.encryptString("rt-old"));
    connection.setTokenExpiresAt(Instant.now().minusSeconds(1));
    tokenEndpointReturns("{\"access_token\":\"at-2\",\"refresh_token\":\"rt-new\",\"expires_in\":3600}");

    oauth.refreshIfNeeded(connection.getId());

    assertThat(REVERSIBLE.decryptString(connection.getAccessToken())).isEqualTo("at-2");
    assertThat(REVERSIBLE.decryptString(connection.getRefreshToken())).isEqualTo("rt-new");
  }

  @Test
  void refreshKeepsTheExistingRefreshTokenWhenTheProviderDoesNotRotate() {
    connection.setRefreshToken(REVERSIBLE.encryptString("rt-old"));
    connection.setTokenExpiresAt(Instant.now().minusSeconds(1));
    tokenEndpointReturns("{\"access_token\":\"at-2\",\"expires_in\":3600}");

    oauth.refreshIfNeeded(connection.getId());

    assertThat(REVERSIBLE.decryptString(connection.getRefreshToken())).isEqualTo("rt-old");
  }

  @Test
  void doesNotRefreshAWorkingToken() {
    connection.setRefreshToken(REVERSIBLE.encryptString("rt-old"));
    connection.setAccessToken(REVERSIBLE.encryptString("at-live"));
    connection.setTokenExpiresAt(Instant.now().plusSeconds(3600));

    oauth.refreshIfNeeded(connection.getId());

    verify(http, never()).send(any(), any(), any(), any(), any());
    assertThat(REVERSIBLE.decryptString(connection.getAccessToken())).isEqualTo("at-live");
  }

  @Test
  void aFailedRefreshMarksTheConnectionForReauthentication() {
    connection.setRefreshToken(REVERSIBLE.encryptString("rt-old"));
    connection.setTokenExpiresAt(Instant.now().minusSeconds(1));
    when(http.send(eq("POST"), any(), any(), any(), any()))
        .thenReturn(new GuardedHttpClient.Response(401, "{\"error\":\"invalid_grant\"}", Map.of()));

    assertThatThrownBy(() -> oauth.refreshIfNeeded(connection.getId()))
        .isInstanceOf(AuthException.class);
    assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.NEEDS_REAUTH);
  }

  @Test
  void revokingCallsTheProviderThenClearsEveryStoredToken() {
    connection.setAccessToken(REVERSIBLE.encryptString("at-1"));
    connection.setRefreshToken(REVERSIBLE.encryptString("rt-1"));
    connection.setStatus(ConnectionStatus.CONNECTED);
    connection.setRevokeApi(Json.obj().put("method", "POST")
        .put("url", "https://auth.example.com/revoke"));
    when(http.send(any(), any(), any(), any(), any()))
        .thenReturn(new GuardedHttpClient.Response(200, "{}", Map.of()));

    oauth.revoke(connection.getId());

    verify(http).send(eq("POST"), eq(URI.create("https://auth.example.com/revoke")), any(), any(), any());
    assertThat(connection.getAccessToken()).isNull();
    assertThat(connection.getRefreshToken()).isNull();
    assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.NEW);
  }

  @Test
  void revocationStillClearsLocalTokensIfTheProviderCallFails() {
    connection.setAccessToken(REVERSIBLE.encryptString("at-1"));
    connection.setRevokeApi(Json.obj().put("url", "https://auth.example.com/revoke"));
    when(http.send(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("provider down"));

    oauth.revoke(connection.getId());

    assertThat(connection.getAccessToken()).isNull();
  }

  @Test
  void testConnectionReportsStatusAndSendsTheTokenServerSide() {
    connection.setAccessToken(REVERSIBLE.encryptString("at-1"));
    connection.setTokenExpiresAt(Instant.now().plusSeconds(3600));
    connection.setTestApi(Json.obj().put("method", "GET").put("url", "https://api.example.com/me"));
    when(http.send(any(), any(), any(), any(), any()))
        .thenReturn(new GuardedHttpClient.Response(200, "{\"id\":1}", Map.of()));

    var result = oauth.test(connection.getId());

    assertThat(result.ok()).isTrue();
    assertThat(result.httpStatus()).isEqualTo(200);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
    verify(http).send(eq("GET"), any(), headers.capture(), any(), any());
    assertThat(headers.getValue().get("authorization")).isEqualTo("Bearer at-1");
  }

  @Test
  void testConnectionSaysSoWhenNoTestEndpointIsConfigured() {
    var result = oauth.test(connection.getId());
    assertThat(result.ok()).isFalse();
    assertThat(result.message()).contains("No test endpoint");
  }
}
