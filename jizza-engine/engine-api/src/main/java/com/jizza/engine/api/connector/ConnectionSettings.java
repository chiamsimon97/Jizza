package com.jizza.engine.api.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.TokenPlacement;

/**
 * Everything a connection is configured with, as the platform hands it to the engine.
 *
 * <p>A null field means "leave whatever is stored alone", which is what makes editing a connection
 * possible without retyping the secret. Deciding that, and deciding which combinations are valid at
 * all, is the platform's business; the engine only stores what it is given.
 *
 * @param clientSecret plaintext on the way in only. The engine encrypts it and never hands it back.
 */
public record ConnectionSettings(String serviceName, AuthScheme authScheme, String clientId,
                                 String clientSecret, String authorizationUrl,
                                 String accessTokenUrl, String refreshTokenUrl, String scopes,
                                 String scopeSeparator, Boolean pkce, Boolean unifiedCallback,
                                 TokenPlacement tokenPlacement, String grantType, String baseUrl,
                                 Boolean autoRefresh, JsonNode testApi, JsonNode revokeApi,
                                 JsonNode profileApi, JsonNode webhookVerifyApi,
                                 JsonNode extraFields) {}
