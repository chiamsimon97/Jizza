package com.jizza.platform.usecase.connection;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.connector.ConnectionSettings;
import com.jizza.engine.api.connector.ConnectionStore;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.TokenPlacement;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connecting a third-party service (R23) -- what the user means by "add a service".
 *
 * <p>Everything here is a product decision rather than a mechanism: which combinations of fields
 * make a usable connection, what an omitted field means on an edit, what the defaults are, and what
 * is worth writing to the activity log. The engine stores the result and later presents the
 * credential on the user's behalf; it has no opinion about any of this.
 */
@Service
public class ConnectionUseCase {

  private final ConnectionStore connections;
  private final AuditLog audit;

  public ConnectionUseCase(ConnectionStore connections, AuditLog audit) {
    this.connections = connections;
    this.audit = audit;
  }

  @Transactional
  public ConnectionDto create(UUID userId, ConnectionSettings requested) {
    validate(requested, true);
    ConnectionDto created = connections.create(userId, withDefaults(requested));

    audit.record(userId, ActorType.USER, null, "connection.created", created.serviceName(),
        Json.obj().put("connectionId", created.id().toString())
            .put("authScheme", String.valueOf(created.authScheme())));
    return created;
  }

  @Transactional
  public ConnectionDto update(UUID userId, UUID connectionId, ConnectionSettings requested) {
    ConnectionDto existing = ownedBy(userId, connectionId);
    validate(requested, false);

    ConnectionDto updated = connections.update(connectionId, requested);
    audit.record(userId, ActorType.USER, null, "connection.updated", existing.serviceName(), null);
    return updated;
  }

  @Transactional(readOnly = true)
  public List<ConnectionDto> list(UUID userId) {
    return connections.list(userId);
  }

  @Transactional(readOnly = true)
  public ConnectionDto get(UUID userId, UUID connectionId) {
    return ownedBy(userId, connectionId);
  }

  @Transactional
  public void delete(UUID userId, UUID connectionId) {
    ConnectionDto existing = ownedBy(userId, connectionId);
    connections.delete(connectionId);
    audit.record(userId, ActorType.USER, null, "connection.deleted", existing.serviceName(), null);
  }

  /**
   * A connection belonging to somebody else is reported as missing rather than forbidden, so the
   * API cannot be used to discover that an id exists.
   */
  public ConnectionDto ownedBy(UUID userId, UUID connectionId) {
    return connections.find(connectionId)
        .filter(connection -> connection.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Connection", connectionId));
  }

  /**
   * What has to be present for a connection to be usable at all. Checked on the way in rather than
   * at first use, because a half-configured connection that only fails when an agent reaches for it
   * is a much worse experience than one that refuses to save.
   */
  private void validate(ConnectionSettings settings, boolean creating) {
    if (creating && isBlank(settings.serviceName())) {
      throw new ValidationException("A connection needs a service name");
    }
    if (settings.authScheme() == AuthScheme.OAUTH2) {
      if (isBlank(settings.authorizationUrl()) || isBlank(settings.accessTokenUrl())) {
        throw new ValidationException(
            "An OAuth2 connection needs both an authorization URL and a token URL");
      }
      if (creating && isBlank(settings.clientId())) {
        throw new ValidationException("An OAuth2 connection needs a client id");
      }
    }
  }

  /** The shape most providers want, so the common case needs no configuration. */
  private ConnectionSettings withDefaults(ConnectionSettings s) {
    return new ConnectionSettings(s.serviceName(), s.authScheme(), s.clientId(), s.clientSecret(),
        s.authorizationUrl(), s.accessTokenUrl(), s.refreshTokenUrl(), s.scopes(),
        s.scopeSeparator() == null ? "space" : s.scopeSeparator(),
        s.pkce(), s.unifiedCallback(),
        s.tokenPlacement() == null ? TokenPlacement.HEADER : s.tokenPlacement(),
        s.grantType() == null ? "authorization_code" : s.grantType(),
        s.baseUrl(), s.autoRefresh(), s.testApi(), s.revokeApi(), s.profileApi(),
        s.webhookVerifyApi(), s.extraFields());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
