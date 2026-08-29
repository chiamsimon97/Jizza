package com.jizza.engine.connectors.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.ConnectionStatus;
import com.jizza.engine.api.enums.TokenPlacement;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connection configuration (R23). The client secret is envelope-encrypted on the way in and never
 * comes back out: {@link ConnectionDto} reports only whether a secret is <em>set</em>.
 */
@Service
public class ConnectionService {

  private final ConnectionRepository connections;
  private final EncryptionService encryption;
  private final AuditLog audit;

  public ConnectionService(ConnectionRepository connections, EncryptionService encryption,
                           AuditLog audit) {
    this.connections = connections;
    this.encryption = encryption;
    this.audit = audit;
  }

  /**
   * @param clientSecret plaintext on the way in only; null on an update leaves the stored secret
   *                     untouched, so editing a connection does not require retyping it.
   */
  public record ConnectionRequest(String serviceName, AuthScheme authScheme, String clientId,
                                  String clientSecret, String authorizationUrl,
                                  String accessTokenUrl, String refreshTokenUrl, String scopes,
                                  String scopeSeparator, Boolean pkce, Boolean unifiedCallback,
                                  TokenPlacement tokenPlacement, String grantType, String baseUrl,
                                  Boolean autoRefresh, JsonNode testApi, JsonNode revokeApi,
                                  JsonNode profileApi, JsonNode webhookVerifyApi,
                                  JsonNode extraFields) {}

  @Transactional
  public ConnectionDto create(UUID userId, ConnectionRequest request) {
    validate(request, true);
    ConnectionEntity entity = new ConnectionEntity();
    entity.setUserId(userId);
    entity.setStatus(ConnectionStatus.NEW);
    entity.setAutoRefresh(true);
    apply(entity, request);
    connections.save(entity);

    audit.record(userId, ActorType.USER, null, "connection.created", entity.getServiceName(),
        Json.obj().put("connectionId", entity.getId().toString())
            .put("authScheme", String.valueOf(entity.getAuthScheme())));
    return toDto(entity);
  }

  @Transactional
  public ConnectionDto update(UUID connectionId, ConnectionRequest request) {
    ConnectionEntity entity = require(connectionId);
    validate(request, false);
    apply(entity, request);
    connections.save(entity);
    audit.record(entity.getUserId(), ActorType.USER, null, "connection.updated",
        entity.getServiceName(), null);
    return toDto(entity);
  }

  @Transactional(readOnly = true)
  public List<ConnectionDto> list(UUID userId) {
    return connections.findByUserIdOrderByServiceNameAsc(userId).stream()
        .map(ConnectionService::toDto).toList();
  }

  @Transactional(readOnly = true)
  public ConnectionDto get(UUID connectionId) {
    return toDto(require(connectionId));
  }

  @Transactional
  public void delete(UUID connectionId) {
    ConnectionEntity entity = require(connectionId);
    connections.delete(entity);
    audit.record(entity.getUserId(), ActorType.USER, null, "connection.deleted",
        entity.getServiceName(), null);
  }

  /** Entity access for the OAuth service, which needs the ciphertext this class hides from DTOs. */
  @Transactional(readOnly = true)
  public ConnectionEntity require(UUID connectionId) {
    return connections.findById(connectionId)
        .orElseThrow(() -> new NotFoundException("Connection", connectionId));
  }

  public String decryptClientSecret(ConnectionEntity entity) {
    return encryption.decryptString(entity.getClientSecret());
  }

  private void validate(ConnectionRequest request, boolean creating) {
    if (creating && (request.serviceName() == null || request.serviceName().isBlank())) {
      throw new ValidationException("A connection needs a service name");
    }
    if (request.authScheme() == AuthScheme.OAUTH2) {
      if (isBlank(request.authorizationUrl()) || isBlank(request.accessTokenUrl())) {
        throw new ValidationException(
            "An OAuth2 connection needs both an authorization URL and a token URL");
      }
      if (creating && isBlank(request.clientId())) {
        throw new ValidationException("An OAuth2 connection needs a client id");
      }
    }
  }

  private void apply(ConnectionEntity entity, ConnectionRequest request) {
    if (request.serviceName() != null) entity.setServiceName(request.serviceName());
    if (request.authScheme() != null) entity.setAuthScheme(request.authScheme());
    if (request.clientId() != null) entity.setClientId(request.clientId());
    // A null secret on update means "leave it alone" rather than "clear it".
    if (request.clientSecret() != null && !request.clientSecret().isBlank()) {
      entity.setClientSecret(encryption.encryptString(request.clientSecret()));
    }
    if (request.authorizationUrl() != null) entity.setAuthorizationUrl(request.authorizationUrl());
    if (request.accessTokenUrl() != null) entity.setAccessTokenUrl(request.accessTokenUrl());
    if (request.refreshTokenUrl() != null) entity.setRefreshTokenUrl(request.refreshTokenUrl());
    if (request.scopes() != null) entity.setScopes(request.scopes());
    if (request.scopeSeparator() != null) entity.setScopeSeparator(request.scopeSeparator());
    if (request.pkce() != null) entity.setPkce(request.pkce());
    if (request.unifiedCallback() != null) entity.setUnifiedCallback(request.unifiedCallback());
    if (request.tokenPlacement() != null) entity.setTokenPlacement(request.tokenPlacement());
    if (request.grantType() != null) entity.setGrantType(request.grantType());
    if (request.baseUrl() != null) entity.setBaseUrl(request.baseUrl());
    if (request.autoRefresh() != null) entity.setAutoRefresh(request.autoRefresh());
    if (request.testApi() != null) entity.setTestApi(request.testApi());
    if (request.revokeApi() != null) entity.setRevokeApi(request.revokeApi());
    if (request.profileApi() != null) entity.setProfileApi(request.profileApi());
    if (request.webhookVerifyApi() != null) entity.setWebhookVerifyApi(request.webhookVerifyApi());
    if (request.extraFields() != null) entity.setExtraFields(request.extraFields());

    if (entity.getScopeSeparator() == null) entity.setScopeSeparator("space");
    if (entity.getTokenPlacement() == null) entity.setTokenPlacement(TokenPlacement.HEADER);
    if (entity.getGrantType() == null) entity.setGrantType("authorization_code");
  }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }

  /** Maps to the client view. Note that no branch of this method can emit secret material. */
  public static ConnectionDto toDto(ConnectionEntity e) {
    return new ConnectionDto(e.getId(), e.getUserId(), e.getServiceName(), e.getAuthScheme(),
        e.getClientId(), e.getClientSecret() != null,
        e.getAuthorizationUrl(), e.getAccessTokenUrl(), e.getRefreshTokenUrl(),
        e.getScopes(), e.getScopeSeparator(), e.isPkce(), e.isUnifiedCallback(),
        e.getTokenPlacement(), e.getGrantType(), e.getTokenExpiresAt(), e.isAutoRefresh(),
        e.getAccessToken() != null,
        e.getTestApi(), e.getRevokeApi(), e.getProfileApi(), e.getWebhookVerifyApi(),
        e.getExtraFields(), e.getStatus(), e.getCreatedAt());
  }
}
