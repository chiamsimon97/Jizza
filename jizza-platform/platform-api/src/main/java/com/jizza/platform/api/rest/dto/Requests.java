package com.jizza.platform.api.rest.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TokenPlacement;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** Request bodies for the HTTP surface. Validation happens at the boundary (R12). */
public final class Requests {

  private Requests() {}

  public record Register(@Email @NotBlank String email, @NotBlank String password) {}

  public record Login(@Email @NotBlank String email, @NotBlank String password) {}

  public record MfaVerify(@NotBlank String mfaToken, @NotBlank String code) {}

  public record MfaConfirm(@NotBlank String code) {}

  public record StepUp(@NotBlank String password, String code) {}

  public record RecoverRequest(@Email @NotBlank String email) {}

  public record RecoverComplete(@NotBlank String token, @NotBlank String newPassword) {}

  public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}

  public record Chat(@NotBlank String message, UUID conversationId) {}

  public record AgentPatch(String name, Boolean enabled) {}

  public record Connection(@NotBlank String serviceName, @NotNull AuthScheme authScheme,
                           String clientId, String clientSecret, String authorizationUrl,
                           String accessTokenUrl, String refreshTokenUrl, String scopes,
                           String scopeSeparator, Boolean pkce, Boolean unifiedCallback,
                           TokenPlacement tokenPlacement, String grantType, String baseUrl,
                           Boolean autoRefresh, JsonNode testApi, JsonNode revokeApi,
                           JsonNode profileApi, JsonNode webhookVerifyApi, JsonNode extraFields) {}

  public record ConnectionPatch(String serviceName, AuthScheme authScheme, String clientId,
                                String clientSecret, String authorizationUrl, String accessTokenUrl,
                                String refreshTokenUrl, String scopes, String scopeSeparator,
                                Boolean pkce, Boolean unifiedCallback, TokenPlacement tokenPlacement,
                                String grantType, String baseUrl, Boolean autoRefresh,
                                JsonNode testApi, JsonNode revokeApi, JsonNode profileApi,
                                JsonNode webhookVerifyApi, JsonNode extraFields) {}

  public record CapabilityGrant(@NotNull com.jizza.engine.api.enums.CapabilityType type,
                                @NotNull UUID capabilityRef) {}

  public record WorkflowSave(UUID id, @NotBlank String name, String description,
                             @NotNull JsonNode graph, JsonNode inputSchema, JsonNode triggerConfig,
                             UUID owningAgentId) {}

  public record SaveAsSkill(@NotBlank String name, String description, JsonNode parameterSchema) {}

  public record RunWorkflow(Integer version, JsonNode input) {}

  public record ResumeRun(String instruction, Boolean approved) {}

  public record ReviseProcedure(@NotNull JsonNode graph) {}

  public record RejectProcedure(String feedback) {}

  public record Task(@NotBlank String title, @NotNull UUID agentId, JsonNode payload,
                     @NotNull ScheduleType scheduleType, String cronOrRrule, Instant runAt,
                     String timezone, Boolean repetitive) {}

  public record TaskRepetitive(boolean repetitive) {}

  public record AnswerNotification(@NotNull com.jizza.engine.api.notification.Answer.Kind kind,
                                   String instruction, String feedback) {}

  public record AutonomyPolicy(UUID id, UUID agentId, String toolRef, @NotNull AutonomyRule rule,
                               JsonNode limits, int precedence) {}

  public record KillSwitch(boolean paused) {}

  public record WebhookSubscribe(UUID connectionId, UUID workflowId, JsonNode verifyConfig) {}
}
