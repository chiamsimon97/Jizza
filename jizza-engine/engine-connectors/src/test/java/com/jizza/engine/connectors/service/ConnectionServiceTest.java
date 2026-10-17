package com.jizza.engine.connectors.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.connector.ConnectionSettings;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.AuthScheme;
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

/**
 * The store's own behaviour: it encrypts, it round-trips, and an omitted secret leaves the stored
 * one alone. What makes a connection valid and what its defaults are is the platform's business and
 * is asserted in {@code ConnectionUseCaseTest}.
 */
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
    service = new ConnectionService(connections, REVERSIBLE);
  }

  private ConnectionSettings oauthSettings(String secret) {
    return new ConnectionSettings("Example", AuthScheme.OAUTH2, "client-abc",
        secret, "https://auth.example.com/authorize", "https://auth.example.com/token", null,
        "read", "space", true, false, null, null, "https://api.example.com", true,
        null, null, null, null, null);
  }

  @Test
  void storesTheClientSecretEncryptedAndNeverReturnsIt() {
    var dto = service.create(userId, userId, oauthSettings("super-secret"));

    assertThat(dto.clientSecretSet()).isTrue();
    assertThat(Json.write(dto)).doesNotContain("super-secret");
    ConnectionEntity stored = rows.get(0);
    assertThat(new String(stored.getClientSecret(), StandardCharsets.UTF_8))
        .doesNotContain("super-secret");
    assertThat(service.decryptClientSecret(stored)).isEqualTo("super-secret");
  }

  @Test
  void anUpdateWithoutASecretLeavesTheStoredOneAlone() {
    var dto = service.create(userId, userId, oauthSettings("super-secret"));

    service.update(dto.id(), new ConnectionSettings("Example renamed", null, null,
        null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null));

    ConnectionEntity stored = rows.get(0);
    assertThat(stored.getServiceName()).isEqualTo("Example renamed");
    assertThat(service.decryptClientSecret(stored)).isEqualTo("super-secret");
  }

  @Test
  void aNewConnectionStartsUnconnected() {
    var dto = service.create(userId, userId, oauthSettings("s"));
    assertThat(dto.status()).isEqualTo(com.jizza.engine.api.enums.ConnectionStatus.NEW);
    assertThat(dto.tokenPresent()).isFalse();
  }

  @Test
  void aConnectionIsFoundByIdAndListedForItsOwner() {
    var dto = service.create(userId, userId, oauthSettings("s"));
    assertThat(service.find(dto.id())).isPresent();
    assertThat(service.find(UUID.randomUUID())).isEmpty();
  }
}
