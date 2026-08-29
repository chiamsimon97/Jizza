package com.jizza.engine.connectors.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionServiceTest {

  private static final EncryptionService REVERSIBLE = new EncryptionService() {
    @Override public byte[] encrypt(byte[] plain) {
      byte[] out = new byte[plain.length];
      for (int i = 0; i < plain.length; i++) out[i] = (byte) (plain[i] ^ 0x5A);
      return out;
    }

    @Override public byte[] decrypt(byte[] ct) { return encrypt(ct); }
  };

  private final UUID userId = UUID.randomUUID();
  private final List<ConnectionEntity> rows = new ArrayList<>();
  private ConnectionService service;

  @BeforeEach
  void setUp() {
    ConnectionRepository connections = mock(ConnectionRepository.class);
    when(connections.save(any())).thenAnswer(inv -> {
      ConnectionEntity e = inv.getArgument(0);
      if (e.getId() == null) e.setId(UUID.randomUUID());
      rows.removeIf(r -> r.getId().equals(e.getId()));
      rows.add(e);
      return e;
    });
    when(connections.findById(any())).thenAnswer(inv ->
        rows.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
    service = new ConnectionService(connections, REVERSIBLE, mock(AuditLog.class));
  }

  private ConnectionService.ConnectionRequest oauthRequest(String secret) {
    return new ConnectionService.ConnectionRequest("Example", AuthScheme.OAUTH2, "client-abc",
        secret, "https://auth.example.com/authorize", "https://auth.example.com/token", null,
        "read", "space", true, false, null, null, "https://api.example.com", true,
        null, null, null, null, null);
  }

  @Test
  void storesTheClientSecretEncryptedAndNeverReturnsIt() {
    var dto = service.create(userId, oauthRequest("super-secret"));

    assertThat(dto.clientSecretSet()).isTrue();
    assertThat(Json.write(dto)).doesNotContain("super-secret");
    ConnectionEntity stored = rows.get(0);
    assertThat(new String(stored.getClientSecret(), StandardCharsets.UTF_8))
        .doesNotContain("super-secret");
    assertThat(service.decryptClientSecret(stored)).isEqualTo("super-secret");
  }

  @Test
  void anUpdateWithoutASecretLeavesTheStoredOneAlone() {
    var dto = service.create(userId, oauthRequest("super-secret"));

    service.update(dto.id(), new ConnectionService.ConnectionRequest("Example renamed", null, null,
        null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null));

    ConnectionEntity stored = rows.get(0);
    assertThat(stored.getServiceName()).isEqualTo("Example renamed");
    assertThat(service.decryptClientSecret(stored)).isEqualTo("super-secret");
  }

  @Test
  void appliesTheContractDefaultsForAnOAuthConnection() {
    var dto = service.create(userId, oauthRequest("s"));
    assertThat(dto.grantType()).isEqualTo("authorization_code");
    assertThat(dto.scopeSeparator()).isEqualTo("space");
    assertThat(dto.tokenPlacement()).isEqualTo(com.jizza.engine.api.enums.TokenPlacement.HEADER);
    assertThat(dto.status()).isEqualTo(com.jizza.engine.api.enums.ConnectionStatus.NEW);
    assertThat(dto.tokenPresent()).isFalse();
  }

  @Test
  void anOAuthConnectionWithoutItsUrlsIsRejected() {
    var incomplete = new ConnectionService.ConnectionRequest("Example", AuthScheme.OAUTH2,
        "client", "secret", null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
    assertThatThrownBy(() -> service.create(userId, incomplete))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("authorization URL");
  }

  @Test
  void anApiKeyConnectionDoesNotNeedOAuthUrls() {
    var apiKey = new ConnectionService.ConnectionRequest("Weather", AuthScheme.API_KEY, null,
        "key-123", null, null, null, null, null, null, null, null, null,
        "https://api.weather.test", null, null, null, null, null, null);
    var dto = service.create(userId, apiKey);
    assertThat(dto.authScheme()).isEqualTo(AuthScheme.API_KEY);
    assertThat(dto.clientSecretSet()).isTrue();
  }

  @Test
  void aConnectionNeedsAServiceName() {
    var nameless = new ConnectionService.ConnectionRequest(" ", AuthScheme.API_KEY, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null);
    assertThatThrownBy(() -> service.create(userId, nameless))
        .isInstanceOf(ValidationException.class);
  }
}
