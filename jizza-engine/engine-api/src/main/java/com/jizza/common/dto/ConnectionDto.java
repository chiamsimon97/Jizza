package com.jizza.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.AuthScheme;
import com.jizza.common.enums.ConnectionStatus;
import com.jizza.common.enums.TokenPlacement;
import java.time.Instant;
import java.util.UUID;

/**
 * Client-facing view of a connection. Deliberately carries <em>no</em> secret material: the client
 * secret and the access/refresh tokens never leave the server (R57).
 */
public record ConnectionDto(UUID id, UUID userId, String serviceName, AuthScheme authScheme,
                            String clientId, boolean clientSecretSet,
                            String authorizationUrl, String accessTokenUrl, String refreshTokenUrl,
                            String scopes, String scopeSeparator, boolean pkce, boolean unifiedCallback,
                            TokenPlacement tokenPlacement, String grantType,
                            Instant tokenExpiresAt, boolean autoRefresh, boolean tokenPresent,
                            JsonNode testApi, JsonNode revokeApi, JsonNode profileApi,
                            JsonNode webhookVerifyApi, JsonNode extraFields,
                            ConnectionStatus status, Instant createdAt) {}
