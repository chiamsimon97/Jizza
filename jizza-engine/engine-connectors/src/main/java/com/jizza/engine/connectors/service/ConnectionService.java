package com.jizza.engine.connectors.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.connector.ConnectionSettings;
import com.jizza.engine.api.connector.ConnectionStore;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.enums.ConnectionStatus;
import com.jizza.engine.api.enums.TokenPlacement;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connection storage (R23). The client secret is envelope-encrypted on the way in and never comes
 * back out: {@link ConnectionDto} reports only whether a secret is <em>set</em>.
 *
 * <p>Holds no policy. What makes a connection valid, what the defaults are and what gets audited
 * are product decisions and live in the platform's use-cases; this class stores what it is handed.
 * It also exposes the entity to the engine's own OAuth and tool-execution paths, which need the
 * ciphertext that the DTO hides.
 */
@Service
public class ConnectionService implements ConnectionStore {

  private final ConnectionRepository connections;
  private final EncryptionService encryption;

  public ConnectionService(ConnectionRepository connections, EncryptionService encryption) {
    this.connections = connections;
    this.encryption = encryption;
  }

  @Override
  @Transactional
  public ConnectionDto create(UUID userId, ConnectionSettings settings) {
    ConnectionEntity entity = new ConnectionEntity();
    entity.setUserId(userId);
    entity.setStatus(ConnectionStatus.NEW);
    entity.setAutoRefresh(true);
    apply(entity, settings);
    connections.save(entity);
    return toDto(entity);
  }

  @Override
  @Transactional
  public ConnectionDto update(UUID connectionId, ConnectionSettings settings) {
    ConnectionEntity entity = require(connectionId);
    apply(entity, settings);
    connections.save(entity);
    return toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ConnectionDto> list(UUID userId) {
    return connections.findByUserIdOrderByServiceNameAsc(userId).stream()
        .map(ConnectionService::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ConnectionDto> find(UUID connectionId) {
    return connections.findById(connectionId).map(ConnectionService::toDto);
  }

  @Override
  @Transactional
  public void delete(UUID connectionId) {
    connections.delete(require(connectionId));
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

  private void apply(ConnectionEntity entity, ConnectionSettings request) {
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
  }

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
