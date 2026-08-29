package com.jizza.engine.connectors.tools.execute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.connector.IdempotencyKey;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.TokenPlacement;
import com.jizza.engine.api.error.EgressBlockedException;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.connectors.config.ConnectorProperties;
import com.jizza.engine.connectors.egress.DefaultEgressGuard;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import com.jizza.engine.connectors.oauth.DefaultOAuthService;
import com.jizza.engine.connectors.service.ConnectionService;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.IdempotencyKeyEntity;
import com.jizza.engine.persistence.repository.IdempotencyKeyRepository;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultToolExecutorTest {

  private static final EncryptionService REVERSIBLE = new EncryptionService() {
    @Override public byte[] encrypt(byte[] plain) {
      byte[] out = new byte[plain.length];
      for (int i = 0; i < plain.length; i++) out[i] = (byte) (plain[i] ^ 0x5A);
      return out;
    }

    @Override public byte[] decrypt(byte[] ct) { return encrypt(ct); }
  };

  private final UUID userId = UUID.randomUUID();
  private final List<IdempotencyKeyEntity> keyRows = new ArrayList<>();

  private ConnectionEntity connection;
  private ConnectionService connections;
  private DefaultOAuthService oauth;
  private GuardedHttpClient http;
  private DefaultToolExecutor executor;

  @BeforeEach
  void setUp() {
    connection = new ConnectionEntity();
    connection.setId(UUID.randomUUID());
    connection.setUserId(userId);
    connection.setServiceName("Pet Store");
    connection.setAuthScheme(AuthScheme.OAUTH2);
    connection.setTokenPlacement(TokenPlacement.HEADER);
    connection.setBaseUrl("https://api.petstore.test/v1");
    connection.setAccessToken(REVERSIBLE.encryptString("live-access-token"));
    connection.setTokenExpiresAt(java.time.Instant.now().plusSeconds(3600));
    connection.setAutoRefresh(true);

    connections = mock(ConnectionService.class);
    when(connections.require(connection.getId())).thenReturn(connection);
    when(connections.decryptClientSecret(any()))
        .thenAnswer(inv -> REVERSIBLE.decryptString(
            ((ConnectionEntity) inv.getArgument(0)).getClientSecret()));

    oauth = mock(DefaultOAuthService.class);
    when(oauth.needsRefresh(any())).thenReturn(false);

    http = mock(GuardedHttpClient.class);
    when(http.send(any(), any(), any(), any(), any()))
        .thenReturn(new GuardedHttpClient.Response(200, "{\"id\":\"p1\"}", Map.of()));

    IdempotencyKeyRepository keys = mock(IdempotencyKeyRepository.class);
    when(keys.save(any())).thenAnswer(inv -> {
      IdempotencyKeyEntity e = inv.getArgument(0);
      if (e.getId() == null) e.setId(UUID.randomUUID());
      keyRows.removeIf(r -> r.getKey().equals(e.getKey()));
      keyRows.add(e);
      return e;
    });
    when(keys.findByUserIdAndKey(any(), any())).thenAnswer(inv -> keyRows.stream()
        .filter(r -> r.getUserId().equals(inv.getArgument(0)) && r.getKey().equals(inv.getArgument(1)))
        .findFirst());

    executor = new DefaultToolExecutor(connections, oauth, REVERSIBLE, http,
        new IdempotencyService(keys), mock(AuditLog.class));
  }

  private GeneratedToolDto readTool() {
    return new GeneratedToolDto(UUID.randomUUID(), connection.getId(), "showpetbyid",
        "Fetch one pet", Json.obj(), "GET", "/pets/{petId}",
        Json.read("{\"path\":[\"petId\"],\"query\":[\"expand\"],\"header\":[]}"), null, false, true);
  }

  private GeneratedToolDto writeTool() {
    return new GeneratedToolDto(UUID.randomUUID(), connection.getId(), "createpet", "Add a pet",
        Json.obj(), "POST", "/pets",
        Json.read("{\"path\":[],\"query\":[],\"header\":[],\"bodyProperty\":\"body\"}"),
        null, true, true);
  }

  private URI capturedUri() {
    ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
    verify(http).send(any(), uri.capture(), any(), any(), any());
    return uri.getValue();
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> capturedHeaders() {
    ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
    verify(http).send(any(), any(), headers.capture(), any(), any());
    return headers.getValue();
  }

  @Test
  void buildsTheRequestFromTheStoredMethodPathAndMapping() {
    var result = executor.execute(readTool(),
        Json.obj().put("petId", "p 1").put("expand", "owner"), null);

    assertThat(result.ok()).isTrue();
    assertThat(capturedUri().toString())
        .isEqualTo("https://api.petstore.test/v1/pets/p+1?expand=owner");
    verify(http).send(eq("GET"), any(), any(), eq(null), eq("https://api.petstore.test/v1"));
  }

  @Test
  void injectsTheAccessTokenServerSide() {
    executor.execute(readTool(), Json.obj().put("petId", "p1"), null);
    assertThat(capturedHeaders()).containsEntry("Authorization", "Bearer live-access-token");
  }

  @Test
  void placesTheTokenInTheQueryStringWhenTheConnectionSaysSo() {
    connection.setTokenPlacement(TokenPlacement.QUERY);
    executor.execute(readTool(), Json.obj().put("petId", "p1"), null);

    assertThat(capturedUri().toString()).contains("access_token=live-access-token");
    assertThat(capturedHeaders()).doesNotContainKey("Authorization");
  }

  @Test
  void injectsAnApiKeyAccordingToTheAuthMapping() {
    connection.setAuthScheme(AuthScheme.API_KEY);
    connection.setClientSecret(REVERSIBLE.encryptString("key-123"));
    var tool = new GeneratedToolDto(UUID.randomUUID(), connection.getId(), "t", "d", Json.obj(),
        "GET", "/pets", Json.read("{\"path\":[],\"query\":[],\"header\":[]}"),
        Json.read("{\"in\":\"header\",\"name\":\"X-API-Key\",\"prefix\":\"\"}"), false, true);

    executor.execute(tool, Json.obj(), null);

    assertThat(capturedHeaders()).containsEntry("X-API-Key", "key-123");
  }

  @Test
  void sendsAJsonBodyForAWriteOperation() {
    var body = Json.obj().put("name", "Rex").put("tag", "dog");
    executor.execute(writeTool(), Json.obj().set("body", body),
        IdempotencyKey.of(userId, "k-1"));

    ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
    verify(http).send(eq("POST"), any(), any(), sent.capture(), any());
    assertThat(sent.getValue()).contains("\"name\":\"Rex\"");
    assertThat(capturedHeaders()).containsEntry("content-type", "application/json");
  }

  @Test
  void aRepeatedSideEffectingCallWithTheSameKeyIsNotSentTwice() {
    var args = Json.obj().set("body", Json.obj().put("name", "Rex"));
    var key = IdempotencyKey.of(userId, "k-1");

    var first = executor.execute(writeTool(), args, key);
    var second = executor.execute(writeTool(), args, key);

    assertThat(first.ok()).isTrue();
    assertThat(second.ok()).isTrue();
    assertThat(second.output()).isEqualTo(first.output());
    verify(http, org.mockito.Mockito.times(1)).send(any(), any(), any(), any(), any());
  }

  @Test
  void reusingAKeyForADifferentRequestIsRefused() {
    var key = IdempotencyKey.of(userId, "k-1");
    executor.execute(writeTool(), Json.obj().set("body", Json.obj().put("name", "Rex")), key);

    var different = executor.execute(writeTool(),
        Json.obj().set("body", Json.obj().put("name", "Different")), key);

    assertThat(different.ok()).isFalse();
    assertThat(different.error()).contains("already used for a different request");
    verify(http, org.mockito.Mockito.times(1)).send(any(), any(), any(), any(), any());
  }

  @Test
  void aReadIsNotDeDuplicated() {
    executor.execute(readTool(), Json.obj().put("petId", "p1"), IdempotencyKey.of(userId, "k-read"));
    executor.execute(readTool(), Json.obj().put("petId", "p1"), IdempotencyKey.of(userId, "k-read"));
    verify(http, org.mockito.Mockito.times(2)).send(any(), any(), any(), any(), any());
  }

  @Test
  void aFailedCallLeavesTheKeyRetryable() {
    when(http.send(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("upstream exploded"))
        .thenReturn(new GuardedHttpClient.Response(200, "{\"id\":\"p1\"}", Map.of()));
    var args = Json.obj().set("body", Json.obj().put("name", "Rex"));
    var key = IdempotencyKey.of(userId, "k-retry");

    assertThat(executor.execute(writeTool(), args, key).ok()).isFalse();
    assertThat(executor.execute(writeTool(), args, key).ok()).isTrue();
  }

  @Test
  void anSsrfTargetIsReportedAsBlockedAndNothingIsSent() {
    ConnectorProperties props = new ConnectorProperties();
    GuardedHttpClient guarded = new GuardedHttpClient(new DefaultEgressGuard(props), props);
    var realExecutor = new DefaultToolExecutor(connections, oauth, REVERSIBLE, guarded,
        new IdempotencyService(mock(IdempotencyKeyRepository.class)), mock(AuditLog.class));
    connection.setBaseUrl("https://169.254.169.254");

    var result = realExecutor.execute(
        new GeneratedToolDto(UUID.randomUUID(), connection.getId(), "meta", "d", Json.obj(),
            "GET", "/latest/meta-data/", Json.obj(), null, false, true),
        Json.obj(), null);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("non-public");
  }

  @Test
  void aMissingPathParameterIsAClearErrorRatherThanAMalformedUrl() {
    var result = executor.execute(readTool(), Json.obj(), null);
    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("Missing path parameter 'petId'");
    verify(http, never()).send(any(), any(), any(), any(), any());
  }

  @Test
  void refreshesALapsedTokenBeforeCalling() {
    when(oauth.needsRefresh(any())).thenReturn(true);
    executor.execute(readTool(), Json.obj().put("petId", "p1"), null);
    verify(oauth).refresh(connection);
  }

  @Test
  void reportsRateLimitingWithTheRetryHint() {
    when(http.send(any(), any(), any(), any(), any())).thenReturn(
        new GuardedHttpClient.Response(429, "{}", Map.of("retry-after", "30")));

    var result = executor.execute(readTool(), Json.obj().put("petId", "p1"), null);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("rate limiting").contains("30");
  }

  @Test
  void anErrorBodyThatEchoesCredentialsIsScrubbedBeforeItIsReturned() {
    when(http.send(any(), any(), any(), any(), any())).thenReturn(new GuardedHttpClient.Response(
        403, "{\"error\":\"bad token\",\"access_token\":\"live-access-token\"}", Map.of()));

    var result = executor.execute(readTool(), Json.obj().put("petId", "p1"), null);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).doesNotContain("live-access-token").contains("403");
  }

  @Test
  void aDisabledToolDoesNotRun() {
    var disabled = new GeneratedToolDto(UUID.randomUUID(), connection.getId(), "t", "d",
        Json.obj(), "GET", "/pets", Json.obj(), null, false, false);

    ToolResult result = executor.execute(disabled, Json.obj(), null);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("disabled");
    verify(http, never()).send(any(), any(), any(), any(), any());
  }

  @Test
  void aConnectionWithoutATokenSaysSoRatherThanCallingUnauthenticated() {
    connection.setAccessToken(null);
    var result = executor.execute(readTool(), Json.obj().put("petId", "p1"), null);
    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("not connected yet");
    verify(http, never()).send(any(), any(), any(), any(), any());
  }

  @Test
  void theResponseBodyIsReturnedToTheCallerUnderAStatus() {
    var result = executor.execute(readTool(), Json.obj().put("petId", "p1"), null);
    JsonNode output = result.output();
    assertThat(output.path("status").asInt()).isEqualTo(200);
    assertThat(output.path("body").path("id").asText()).isEqualTo("p1");
  }
}
