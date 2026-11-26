package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.connectors.config.ConnectorProperties;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import com.jizza.engine.persistence.entity.McpServerEntity;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The MCP sign-in: discovery, self-registration, PKCE and the token exchange. */
class McpOAuthTest {

  private GuardedHttpClient http;
  private McpOAuth oauth;
  private McpServerEntity server;

  @BeforeEach
  void setUp() {
    http = mock(GuardedHttpClient.class);
    when(http.send(anyString(), any(), any(), any(), isNull()))
        .thenReturn(new GuardedHttpClient.Response(404, "", Map.of()));
    ConnectorProperties props = new ConnectorProperties();
    props.setCallbackBaseUrl("https://jizza.example.app/");
    oauth = new McpOAuth(http, new McpServerServiceTest.FakeEncryption(), props);
    server = new McpServerEntity();
    server.setId(UUID.randomUUID());
    server.setName("Notion");
    server.setUrl("https://mcp.notion.example/mcp");
  }

  private void answer(String method, String url, int status, String body) {
    when(http.send(eq(method), eq(URI.create(url)), any(), any(), isNull()))
        .thenReturn(new GuardedHttpClient.Response(status, body, Map.of()));
  }

  @Test
  void discoversRegistersAndSendsTheBrowserToSignIn() {
    answer("GET", "https://mcp.notion.example/.well-known/oauth-protected-resource/mcp", 200, """
        {"resource":"https://mcp.notion.example/mcp",
         "authorization_servers":["https://auth.notion.example"],"scopes_supported":["read"]}""");
    answer("GET", "https://auth.notion.example/.well-known/oauth-authorization-server", 200, """
        {"authorization_endpoint":"https://auth.notion.example/authorize",
         "token_endpoint":"https://auth.notion.example/token",
         "registration_endpoint":"https://auth.notion.example/register",
         "code_challenge_methods_supported":["S256"]}""");
    answer("POST", "https://auth.notion.example/register", 201, "{\"client_id\":\"jizza-1\"}");

    UUID session = UUID.randomUUID();
    URI url = oauth.start(server, session);

    assertThat(url.toString()).startsWith("https://auth.notion.example/authorize?");
    Map<String, String> params = query(url);
    assertThat(params).containsEntry("client_id", "jizza-1")
        .containsEntry("redirect_uri", "https://jizza.example.app/api/mcp/oauth/callback")
        .containsEntry("code_challenge_method", "S256")
        .containsEntry("resource", "https://mcp.notion.example/mcp")
        .containsEntry("scope", "read")
        .containsEntry("state", server.getOauthState());
    assertThat(server.getOauthSessionId()).isEqualTo(session);
    assertThat(server.getOauthStateExpiresAt()).isAfter(Instant.now());
    // The verifier is stored encrypted, never sent.
    assertThat(url.toString()).doesNotContain(new String(server.getOauthVerifier(), StandardCharsets.UTF_8));
  }

  @Test
  void aClientRegisteredForAnotherReturnAddressIsRegisteredAgain() {
    answer("GET", "https://mcp.notion.example/.well-known/oauth-protected-resource/mcp", 200, """
        {"resource":"https://mcp.notion.example/mcp","authorization_servers":["https://auth.notion.example"]}""");
    answer("GET", "https://auth.notion.example/.well-known/oauth-authorization-server", 200, """
        {"authorization_endpoint":"https://auth.notion.example/authorize",
         "token_endpoint":"https://auth.notion.example/token",
         "registration_endpoint":"https://auth.notion.example/register"}""");
    answer("POST", "https://auth.notion.example/register", 201, "{\"client_id\":\"jizza-new\"}");
    server.setOauthAuthorizationUrl("https://auth.notion.example/authorize");
    server.setOauthTokenUrl("https://auth.notion.example/token");
    server.setOauthClientId("jizza-old");
    server.setOauthRegisteredRedirect("http://localhost:8080/api/mcp/oauth/callback");

    Map<String, String> params = query(oauth.start(server, UUID.randomUUID()));

    assertThat(params).containsEntry("client_id", "jizza-new")
        .containsEntry("redirect_uri", "https://jizza.example.app/api/mcp/oauth/callback");
    assertThat(server.getOauthRegisteredRedirect()).isEqualTo("https://jizza.example.app/api/mcp/oauth/callback");
  }

  @Test
  void aClientIdSomeoneTypedIsKept() {
    server.setOauthAuthorizationUrl("https://auth.notion.example/authorize");
    server.setOauthTokenUrl("https://auth.notion.example/token");
    server.setOauthClientId("typed-in");

    assertThat(query(oauth.start(server, UUID.randomUUID()))).containsEntry("client_id", "typed-in");
    assertThat(server.getOauthRegisteredRedirect()).isNull();
  }

  @Test
  void aServerWithNoMetadataGetsTheDefaultPaths() {
    answer("POST", "https://mcp.notion.example/register", 201, "{\"client_id\":\"c\"}");
    URI url = oauth.start(server, UUID.randomUUID());
    assertThat(url.toString()).startsWith("https://mcp.notion.example/authorize?");
    assertThat(server.getOauthTokenUrl()).isEqualTo("https://mcp.notion.example/token");
  }

  @Test
  void theCodeIsExchangedOnceForTokens() {
    server.setOauthClientId("c");
    server.setOauthAuthorizationUrl("https://auth.example/authorize");
    server.setOauthTokenUrl("https://auth.example/token");
    oauth.start(server, UUID.randomUUID());
    answer("POST", "https://auth.example/token", 200,
        "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}");

    oauth.complete(server, "the-code");

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(http).send(eq("POST"), eq(URI.create("https://auth.example/token")),
        any(), body.capture(), isNull());
    assertThat(body.getValue()).contains("grant_type=authorization_code", "code=the-code",
        "code_verifier=", "resource=https%3A%2F%2Fmcp.notion.example%2Fmcp");
    assertThat(oauth.accessToken(server)).isEqualTo("at-1");
    assertThat(server.getOauthState()).isNull();
    assertThat(oauth.needsRefresh(server)).isFalse();
  }

  @Test
  void anExpiredAttemptIsRefused() {
    server.setOauthClientId("c");
    server.setOauthAuthorizationUrl("https://auth.example/authorize");
    server.setOauthTokenUrl("https://auth.example/token");
    oauth.start(server, UUID.randomUUID());
    server.setOauthStateExpiresAt(Instant.now().minusSeconds(1));
    assertThatThrownBy(() -> oauth.complete(server, "code")).isInstanceOf(AuthException.class);
    assertThat(server.getOauthState()).isNull();
  }

  private static Map<String, String> query(URI url) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : url.getRawQuery().split("&")) {
      String[] kv = pair.split("=", 2);
      out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
          URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    return out;
  }
}
