package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.common.dto.AgentDto;
import com.jizza.common.enums.CapabilityType;
import com.jizza.common.enums.ConnectionStatus;
import com.jizza.common.llm.LlmResponse;
import com.jizza.common.orchestrator.AgentDirectory;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.util.Json;
import com.jizza.connectors.oauth.Pkce;
import com.jizza.persistence.repository.ConnectionRepository;
import com.jizza.persistence.repository.OAuthStateRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: the OAuth2 handshake, API-doc ingestion and the tool executor, against a real HTTP
 * service (build prompt Acceptance Criteria 7 and 8, and the SSRF guard from criterion 3).
 *
 * <p>The handshake and every connection route go through the HTTP API. Document ingestion is driven
 * through its service rather than the multipart endpoint: the endpoint's own guards -- session,
 * step-up, size and type -- are asserted separately, and going straight to the service keeps these
 * tests about what the generated tools then do.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ConnectorsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final String CLIENT_SECRET = "super-secret-client-value";

  @LocalServerPort private int port;
  @Autowired private AgentDirectory agents;
  @Autowired private ConnectionRepository connections;
  @Autowired private OAuthStateRepository states;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;
  private StubService service;
  private UUID userId;
  private UUID connectionId;

  @BeforeEach
  void setUp() {
    service = new StubService();
    api = new ApiClient(port);
    api.prime();
    model.reset();

    String email = "conn-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    userId = UUID.fromString(api.post("/api/auth/login",
        Map.of("email", email, "password", PASSWORD)).json().path("userId").asText());
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));

    var created = api.post("/api/connections", connectionBody());
    assertThat(created.ok()).as(created.body()).isTrue();
    connectionId = UUID.fromString(created.json().path("id").asText());
  }

  @AfterEach
  void tearDown() {
    service.close();
  }

  private Map<String, Object> connectionBody() {
    Map<String, Object> body = new HashMap<>();
    body.put("serviceName", "Pet Store");
    body.put("authScheme", "OAUTH2");
    body.put("clientId", "client-abc");
    body.put("clientSecret", CLIENT_SECRET);
    body.put("authorizationUrl", service.authorizeUrl());
    body.put("accessTokenUrl", service.tokenUrl());
    body.put("scopes", "pets.read pets.write");
    body.put("pkce", true);
    body.put("baseUrl", service.apiBaseUrl());
    body.put("revokeApi", Json.obj().put("method", "POST").put("url", service.revokeUrl()));
    body.put("testApi", Json.obj().put("method", "GET").put("url", service.apiBaseUrl() + "/pets"));
    return body;
  }

  /** Walks the handshake the way the browser would, ending at the public callback. */
  private void connect() {
    var authorize = api.post("/api/connections/" + connectionId + "/authorize", null);
    assertThat(authorize.ok()).as(authorize.body()).isTrue();

    Map<String, String> params = queryOf(URI.create(authorize.json().path("url").asText()));
    var callback = new ApiClient(port).get("/api/oauth/callback?state="
        + params.get("state") + "&code=auth-code-1");
    assertThat(callback.status()).as(callback.body()).isEqualTo(200);
  }

  private static Map<String, String> queryOf(URI uri) {
    Map<String, String> params = new HashMap<>();
    for (String pair : uri.getQuery().split("&")) {
      int eq = pair.indexOf('=');
      params.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return params;
  }

  private UUID agentId(String key) {
    return agents.byKey(userId, key).map(AgentDto::id).orElseThrow();
  }

  // ── the handshake ────────────────────────────────────────────────────────

  @Test
  void theAuthorizeUrlCarriesASingleUseStateAndAPkceChallengeButNoSecret() {
    var authorize = api.post("/api/connections/" + connectionId + "/authorize", null);
    Map<String, String> params = queryOf(URI.create(authorize.json().path("url").asText()));

    assertThat(params.get("response_type")).isEqualTo("code");
    assertThat(params.get("client_id")).isEqualTo("client-abc");
    assertThat(params.get("scope")).isEqualTo("pets.read pets.write");
    assertThat(params.get("code_challenge_method")).isEqualTo("S256");
    assertThat(params).doesNotContainKey("client_secret");
    assertThat(authorize.body()).doesNotContain(CLIENT_SECRET);

    var stored = states.findByState(params.get("state")).orElseThrow();
    assertThat(stored.isUsed()).isFalse();
    assertThat(stored.getExpiresAt()).isAfter(Instant.now());
    assertThat(stored.getSessionId()).as("bound to the session that started it").isNotNull();
    // The verifier is stored encrypted, so its ciphertext is not the value the challenge was
    // derived from: the verifier itself never left the server.
    assertThat(Pkce.challenge(new String(stored.getCodeVerifier(), StandardCharsets.UTF_8)))
        .isNotEqualTo(params.get("code_challenge"));
    assertThat(params.get("code_challenge")).isNotBlank();
  }

  @Test
  void completingTheCallbackConnectsAndStoresTokensEncrypted() {
    connect();

    var connection = api.get("/api/connections/" + connectionId);
    assertThat(connection.json().path("status").asText()).isEqualTo("CONNECTED");
    assertThat(connection.json().path("tokenPresent").asBoolean()).isTrue();
    assertThat(connection.body()).doesNotContain("access-token-1").doesNotContain(CLIENT_SECRET);

    var stored = connections.findById(connectionId).orElseThrow();
    assertThat(new String(stored.getAccessToken(), StandardCharsets.UTF_8))
        .doesNotContain("access-token-1");

    // The exchange sent the secret and the PKCE verifier server-side, as form fields.
    var exchange = service.received.stream()
        .filter(r -> r.path().equals("/oauth/token")).findFirst().orElseThrow();
    var form = StubService.parseForm(exchange.body());
    assertThat(form.get("grant_type")).isEqualTo("authorization_code");
    assertThat(form.get("code")).isEqualTo("auth-code-1");
    assertThat(form.get("client_secret")).isEqualTo(CLIENT_SECRET);
    assertThat(form.get("code_verifier")).isNotBlank();
  }

  @Test
  void aReplayedOrUnknownStateIsRefused() {
    var authorize = api.post("/api/connections/" + connectionId + "/authorize", null);
    String state = queryOf(URI.create(authorize.json().path("url").asText())).get("state");

    assertThat(new ApiClient(port)
        .get("/api/oauth/callback?state=" + state + "&code=auth-code-1").status()).isEqualTo(200);

    var replay = new ApiClient(port).get("/api/oauth/callback?state=" + state + "&code=auth-code-1");
    assertThat(replay.status()).isEqualTo(400);
    assertThat(replay.body()).contains("already been used");

    var unknown = new ApiClient(port).get("/api/oauth/callback?state=never-issued&code=x");
    assertThat(unknown.status()).isEqualTo(400);
    assertThat(unknown.body()).contains("not recognised");
  }

  @Test
  void testConnectionReportsStatusUsingTheStoredToken() {
    connect();
    var result = api.post("/api/connections/" + connectionId + "/test", null);

    assertThat(result.json().path("ok").asBoolean()).as(result.body()).isTrue();
    assertThat(result.json().path("httpStatus").asInt()).isEqualTo(200);
    assertThat(service.lastBearerToken()).isEqualTo("access-token-1");
  }

  @Test
  void anExpiredTokenIsRefreshedTransparentlyAndTheRefreshTokenRotates() {
    connect();

    // Wind the token past its expiry and have the provider issue a rotated pair.
    var stored = connections.findById(connectionId).orElseThrow();
    stored.setTokenExpiresAt(Instant.now().minusSeconds(60));
    connections.save(stored);
    service.accessTokenToIssue = "access-token-2";
    service.refreshTokenToIssue = "refresh-token-2";

    var result = api.post("/api/connections/" + connectionId + "/test", null);

    assertThat(result.json().path("ok").asBoolean()).as(result.body()).isTrue();
    assertThat(service.lastBearerToken()).isEqualTo("access-token-2");
    assertThat(service.callsTo("/oauth/token")).isEqualTo(2);

    var refreshExchange = service.received.stream()
        .filter(r -> r.path().equals("/oauth/token"))
        .reduce((first, second) -> second).orElseThrow();
    assertThat(StubService.parseForm(refreshExchange.body()).get("grant_type"))
        .isEqualTo("refresh_token");
  }

  @Test
  void disconnectingRevokesAtTheProviderAndClearsEveryToken() {
    connect();

    var revoked = api.post("/api/connections/" + connectionId + "/revoke", null);

    assertThat(revoked.ok()).as(revoked.body()).isTrue();
    assertThat(service.revoked).as("the provider was told first").isTrue();

    var stored = connections.findById(connectionId).orElseThrow();
    assertThat(stored.getAccessToken()).isNull();
    assertThat(stored.getRefreshToken()).isNull();
    assertThat(stored.getStatus()).isEqualTo(ConnectionStatus.NEW);
  }

  // ── API doc to tools, and executing one ──────────────────────────────────

  @Test
  void anAttachedOpenApiDocumentGeneratesOneToolPerOperation(
      @Autowired com.jizza.connectors.tools.generate.ApiDocIngestService apiDocs) {
    var tools = apiDocs.fromApiDoc(connectionId,
        service.openApiDocument().getBytes(StandardCharsets.UTF_8),
        com.jizza.common.enums.DocFormat.OPENAPI_JSON);

    assertThat(tools).extracting(com.jizza.common.dto.GeneratedToolDto::name)
        .containsExactlyInAnyOrder("listpets", "createpet", "showpetbyid");
    assertThat(tools).filteredOn(t -> t.name().equals("createpet"))
        .allMatch(com.jizza.common.dto.GeneratedToolDto::sideEffecting);
    assertThat(tools).filteredOn(t -> t.name().equals("listpets"))
        .noneMatch(com.jizza.common.dto.GeneratedToolDto::sideEffecting);

    // The document declared an Authorization parameter; the model must never be asked for it.
    var listPets = tools.stream().filter(t -> t.name().equals("listpets")).findFirst().orElseThrow();
    assertThat(listPets.inputSchema().path("properties").has("Authorization")).isFalse();

    assertThat(api.get("/api/connections/" + connectionId + "/tools").json()).hasSize(3);
  }

  @Test
  void anAssignedToolMakesARealAuthenticatedCallThroughTheAgent(
      @Autowired com.jizza.connectors.tools.generate.ApiDocIngestService apiDocs,
      @Autowired com.jizza.common.agent.CapabilityRegistry capabilities) {
    connect();
    var tools = apiDocs.fromApiDoc(connectionId,
        service.openApiDocument().getBytes(StandardCharsets.UTF_8),
        com.jizza.common.enums.DocFormat.OPENAPI_JSON);
    UUID listPets = tools.stream().filter(t -> t.name().equals("listpets"))
        .findFirst().orElseThrow().id();

    UUID kai = agentId("finance");
    var granted = api.post("/api/agents/" + kai + "/capabilities",
        Map.of("type", "GENERATED_TOOL", "capabilityRef", listPets.toString()));
    assertThat(granted.ok()).as(granted.body()).isTrue();

    var result = capabilities.invoke(kai, new ToolCall("c1", "listpets", Json.obj()));

    assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
    assertThat(result.output().path("status").asInt()).isEqualTo(200);
    assertThat(result.output().path("body").path("pets").get(0).path("name").asText())
        .isEqualTo("Rex");
    assertThat(service.lastBearerToken())
        .as("auth injected server-side, never by the model").isEqualTo("access-token-1");
  }

  @Test
  void aSideEffectingCallIsNotSentTwiceForTheSameIdempotencyKey(
      @Autowired com.jizza.connectors.tools.generate.ApiDocIngestService apiDocs,
      @Autowired com.jizza.common.connector.ToolExecutor executor) {
    connect();
    var createPet = apiDocs.fromApiDoc(connectionId,
        service.openApiDocument().getBytes(StandardCharsets.UTF_8),
        com.jizza.common.enums.DocFormat.OPENAPI_JSON).stream()
        .filter(t -> t.name().equals("createpet")).findFirst().orElseThrow();

    var args = Json.obj().set("body", Json.obj().put("name", "Rex"));
    var key = com.jizza.common.connector.IdempotencyKey.of(userId, "e2e-" + UUID.randomUUID());

    var first = executor.execute(createPet, args, key);
    var second = executor.execute(createPet, args, key);

    assertThat(first.ok()).as(String.valueOf(first.error())).isTrue();
    assertThat(second.ok()).isTrue();
    assertThat(second.output()).isEqualTo(first.output());
    assertThat(service.petsCreated.get()).as("the service saw one create, not two").isEqualTo(1);
  }

  @Test
  void theToolIsOfferedToTheAgentThatHoldsItAndToNoOther(
      @Autowired com.jizza.connectors.tools.generate.ApiDocIngestService apiDocs,
      @Autowired com.jizza.common.agent.CapabilityRegistry capabilities) {
    var listPets = apiDocs.fromApiDoc(connectionId,
        service.openApiDocument().getBytes(StandardCharsets.UTF_8),
        com.jizza.common.enums.DocFormat.OPENAPI_JSON).stream()
        .filter(t -> t.name().equals("listpets")).findFirst().orElseThrow();

    UUID kai = agentId("finance");
    UUID vita = agentId("health");
    capabilities.grant(kai, CapabilityType.GENERATED_TOOL, listPets.id());

    assertThat(capabilities.toolsFor(kai)).extracting(com.jizza.common.tool.ToolSpec::name)
        .contains("listpets");
    assertThat(capabilities.toolsFor(vita)).extracting(com.jizza.common.tool.ToolSpec::name)
        .doesNotContain("listpets");
    assertThat(capabilities.invoke(vita, new ToolCall("c1", "listpets", Json.obj())).error())
        .contains("not assigned to this agent");
  }

  @Test
  void theEgressGuardBlocksAToolPointedAtAnInternalAddress(
      @Autowired com.jizza.common.connector.ToolExecutor executor) {
    connect();
    // A connection whose base URL is the cloud metadata address: the classic SSRF target.
    var metadata = api.post("/api/connections", Map.of(
        "serviceName", "Metadata", "authScheme", "API_KEY", "clientSecret", "k",
        "baseUrl", "http://169.254.169.254"));
    UUID metadataConnection = UUID.fromString(metadata.json().path("id").asText());

    var tool = new com.jizza.common.dto.GeneratedToolDto(UUID.randomUUID(), metadataConnection,
        "read_metadata", "d", Json.obj(), "GET", "/latest/meta-data/", Json.obj(), null,
        false, true);

    var result = executor.execute(tool, Json.obj(), null);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("non-public");
  }

  @Test
  void theEgressGuardKeepsAToolOnTheHostItsConnectionDeclared(
      @Autowired com.jizza.common.connector.ToolExecutor executor) {
    connect();
    var tool = new com.jizza.common.dto.GeneratedToolDto(UUID.randomUUID(), connectionId,
        "escape", "d", Json.obj(), "GET", "/v1/pets", Json.obj(), null, false, true);

    // Point the connection somewhere else and the same tool stops working: the host comes from
    // the connection, so a doctored document cannot redirect the call.
    var stored = connections.findById(connectionId).orElseThrow();
    stored.setBaseUrl("http://10.0.0.5/v1");
    connections.save(stored);

    assertThat(executor.execute(tool, Json.obj(), null).error()).contains("non-public");
  }

  @Test
  void anUpstreamErrorIsReportedWithoutEchoingCredentials(
      @Autowired com.jizza.connectors.tools.generate.ApiDocIngestService apiDocs,
      @Autowired com.jizza.common.connector.ToolExecutor executor) {
    connect();
    var listPets = apiDocs.fromApiDoc(connectionId,
        service.openApiDocument().getBytes(StandardCharsets.UTF_8),
        com.jizza.common.enums.DocFormat.OPENAPI_JSON).stream()
        .filter(t -> t.name().equals("listpets")).findFirst().orElseThrow();
    service.nextStatus = 500;

    var result = executor.execute(listPets, Json.obj(), null);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("500").doesNotContain("access-token-1");
  }

  @Test
  void aGeneratedToolNeverPutsACredentialInModelContext(
      @Autowired com.jizza.connectors.tools.generate.ApiDocIngestService apiDocs,
      @Autowired com.jizza.common.agent.CapabilityRegistry capabilities) {
    connect();
    var listPets = apiDocs.fromApiDoc(connectionId,
        service.openApiDocument().getBytes(StandardCharsets.UTF_8),
        com.jizza.common.enums.DocFormat.OPENAPI_JSON).stream()
        .filter(t -> t.name().equals("listpets")).findFirst().orElseThrow();
    UUID kai = agentId("finance");
    capabilities.grant(kai, CapabilityType.GENERATED_TOOL, listPets.id());

    model.scriptFor("Jizza",
        new LlmResponse("", List.of(new ToolCall("d1", "delegate_to_specialist",
            Json.obj().put("agent_key", "finance").put("task", "list the pets"))),
            "tool_use", 5, 5, "m"),
        LlmResponse.text("Kai found Rex."));
    model.scriptFor("Kai",
        new LlmResponse("", List.of(new ToolCall("t1", "listpets", Json.obj())),
            "tool_use", 5, 5, "m"),
        LlmResponse.text("There is one pet, Rex."));

    var reply = api.post("/api/chat", Map.of("message", "what pets are there?"));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(model.everythingTheModelSaw())
        .contains("listpets")
        .doesNotContain("access-token-1")
        .doesNotContain(CLIENT_SECRET);
  }
}
