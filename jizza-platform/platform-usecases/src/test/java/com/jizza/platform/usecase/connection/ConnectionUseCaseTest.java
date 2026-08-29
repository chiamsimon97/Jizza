package com.jizza.platform.usecase.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.connector.ConnectionSettings;
import com.jizza.engine.api.connector.ConnectionStore;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.ConnectionStatus;
import com.jizza.engine.api.enums.TokenPlacement;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The product's rules about connecting a service: what has to be filled in, what the defaults are,
 * and that one user cannot reach another's connection. The store is a stand-in, because none of
 * this depends on how a connection is persisted.
 */
class ConnectionUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final List<ConnectionDto> saved = new ArrayList<>();
  private ConnectionUseCase useCase;

  /** Records what the use-case asked the engine to store, which is what the assertions read. */
  private final ConnectionStore store = new ConnectionStore() {
    @Override public ConnectionDto create(UUID owner, ConnectionSettings s) {
      ConnectionDto dto = dtoOf(UUID.randomUUID(), owner, s);
      saved.add(dto);
      return dto;
    }

    @Override public ConnectionDto update(UUID id, ConnectionSettings s) {
      ConnectionDto existing = find(id).orElseThrow();
      ConnectionDto dto = dtoOf(id, existing.userId(), s);
      saved.replaceAll(c -> c.id().equals(id) ? dto : c);
      return dto;
    }

    @Override public List<ConnectionDto> list(UUID owner) {
      return saved.stream().filter(c -> c.userId().equals(owner)).toList();
    }

    @Override public Optional<ConnectionDto> find(UUID id) {
      return saved.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    @Override public void delete(UUID id) {
      saved.removeIf(c -> c.id().equals(id));
    }
  };

  private static ConnectionDto dtoOf(UUID id, UUID owner, ConnectionSettings s) {
    return new ConnectionDto(id, owner, s.serviceName(), s.authScheme(), s.clientId(),
        s.clientSecret() != null, s.authorizationUrl(), s.accessTokenUrl(), s.refreshTokenUrl(),
        s.scopes(), s.scopeSeparator(), Boolean.TRUE.equals(s.pkce()),
        Boolean.TRUE.equals(s.unifiedCallback()), s.tokenPlacement(), s.grantType(), null,
        !Boolean.FALSE.equals(s.autoRefresh()), false, s.testApi(), s.revokeApi(), s.profileApi(),
        s.webhookVerifyApi(), s.extraFields(), ConnectionStatus.NEW, Instant.now());
  }

  @BeforeEach
  void setUp() {
    useCase = new ConnectionUseCase(store, mock(AuditLog.class));
  }

  private ConnectionSettings oauth(String secret) {
    return new ConnectionSettings("Example", AuthScheme.OAUTH2, "client-abc", secret,
        "https://auth.example.com/authorize", "https://auth.example.com/token", null,
        "read", null, true, false, null, null, "https://api.example.com", true,
        null, null, null, null, null);
  }

  @Test
  void appliesTheDefaultsMostProvidersWantSoTheCommonCaseNeedsNoConfiguration() {
    var dto = useCase.create(userId, oauth("s"));

    assertThat(dto.grantType()).isEqualTo("authorization_code");
    assertThat(dto.scopeSeparator()).isEqualTo("space");
    assertThat(dto.tokenPlacement()).isEqualTo(TokenPlacement.HEADER);
  }

  @Test
  void anExplicitSettingIsNotOverwrittenByADefault() {
    var dto = useCase.create(userId, new ConnectionSettings("Example", AuthScheme.OAUTH2, "c", "s",
        "https://a", "https://t", null, "read", "comma", null, null, TokenPlacement.QUERY,
        "client_credentials", null, null, null, null, null, null, null));

    assertThat(dto.scopeSeparator()).isEqualTo("comma");
    assertThat(dto.tokenPlacement()).isEqualTo(TokenPlacement.QUERY);
    assertThat(dto.grantType()).isEqualTo("client_credentials");
  }

  @Test
  void anOAuthConnectionWithoutItsUrlsIsRefusedOnTheWayInRatherThanAtFirstUse() {
    var incomplete = new ConnectionSettings("Example", AuthScheme.OAUTH2, "client", "secret",
        null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);

    assertThatThrownBy(() -> useCase.create(userId, incomplete))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("authorization URL");
  }

  @Test
  void anOAuthConnectionNeedsAClientId() {
    var noClient = new ConnectionSettings("Example", AuthScheme.OAUTH2, null, "secret",
        "https://a", "https://t", null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);

    assertThatThrownBy(() -> useCase.create(userId, noClient))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("client id");
  }

  @Test
  void anApiKeyConnectionDoesNotNeedOAuthUrls() {
    var apiKey = new ConnectionSettings("Weather", AuthScheme.API_KEY, null, "key-123",
        null, null, null, null, null, null, null, null, null, "https://api.weather.test", null,
        null, null, null, null, null);

    var dto = useCase.create(userId, apiKey);

    assertThat(dto.authScheme()).isEqualTo(AuthScheme.API_KEY);
    assertThat(dto.clientSecretSet()).isTrue();
  }

  @Test
  void aConnectionNeedsAServiceName() {
    var nameless = new ConnectionSettings(" ", AuthScheme.API_KEY, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null);

    assertThatThrownBy(() -> useCase.create(userId, nameless))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anEditDoesNotHaveToRepeatTheRulesThatOnlyApplyWhenCreating() {
    var dto = useCase.create(userId, oauth("s"));

    // Renaming alone: no service name, client id or URLs in the patch, and it is still accepted.
    var renamed = useCase.update(userId, dto.id(), new ConnectionSettings("Renamed", null, null,
        null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null));

    assertThat(renamed.serviceName()).isEqualTo("Renamed");
  }

  @Test
  void anotherUsersConnectionIsReportedMissingRatherThanForbidden() {
    var dto = useCase.create(userId, oauth("s"));
    UUID somebodyElse = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.get(somebodyElse, dto.id()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCase.delete(somebodyElse, dto.id()))
        .isInstanceOf(NotFoundException.class);
    assertThat(saved).as("and nothing was removed").hasSize(1);
  }

  @Test
  void listingOnlyReturnsTheCallersOwnConnections() {
    useCase.create(userId, oauth("s"));
    useCase.create(UUID.randomUUID(), oauth("s"));

    assertThat(useCase.list(userId)).hasSize(1);
  }
}
