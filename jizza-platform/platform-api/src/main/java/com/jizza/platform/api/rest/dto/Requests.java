package com.jizza.platform.api.rest.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TokenPlacement;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request bodies for the HTTP surface. Validation happens at the boundary (R12). */
public final class Requests {

  private Requests() {}

  // Bounded so an oversized field cannot make the server spend Argon2 time on it.
  public record Register(@Email @NotBlank @Size(max = 320) String email,
                         @NotBlank @Size(max = 256) String password) {}

  public record Login(@Email @NotBlank @Size(max = 320) String email,
                      @NotBlank @Size(max = 256) String password) {}

  /** {@code code} is a 6-digit authenticator code or a one-time recovery code. */
  public record MfaVerify(@NotBlank @Size(max = 128) String mfaToken,
                          @NotBlank @Size(max = 32) String code) {}

  public record MfaConfirm(@NotBlank @Size(max = 32) String code) {}

  public record MfaCode(@NotBlank @Size(max = 32) String code) {}

  public record StepUp(@NotBlank @Size(max = 256) String password, @Size(max = 32) String code) {}

  public record RecoverRequest(@Email @NotBlank @Size(max = 320) String email) {}

  public record RecoverComplete(@NotBlank @Size(max = 128) String token,
                                @NotBlank @Size(max = 256) String newPassword) {}

  public record ChangePassword(@NotBlank @Size(max = 256) String currentPassword,
                               @NotBlank @Size(max = 256) String newPassword) {}

  /** {@code screen} is the app page the message was sent from, when it came from the app. */
  public record Chat(@NotBlank String message, UUID conversationId, String screen) {}

  /** A message sent as a background turn. {@code clientMessageId} makes a retried send safe. */
  public record Turn(@NotBlank String message, UUID conversationId, UUID clientMessageId,
                     String screen, Boolean incognito) {}

  public record ConversationCreate(String title) {}

  public record ConversationPatch(String title, Boolean pinned, Boolean archived) {}

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

  /** A fact the user tells Jizza to remember, or the corrected version of one. */
  public record MemoryInput(com.jizza.engine.api.enums.MemoryCategory category,
                            @jakarta.validation.constraints.Size(max = 2000) String content,
                            String key, JsonNode data,
                            com.jizza.engine.api.enums.DataClassification classification,
                            @jakarta.validation.constraints.DecimalMin("0")
                            @jakarta.validation.constraints.DecimalMax("1") Double importance,
                            java.util.List<UUID> subjectIds, java.util.List<String> domains,
                            Instant expiresAt) {

    public com.jizza.engine.api.memory.MemoryDraft toDraft() {
      return new com.jizza.engine.api.memory.MemoryDraft(category, content, key, data, classification,
          null, importance, null, null, null, subjectIds, domains, expiresAt);
    }
  }

  public record SubjectInput(com.jizza.engine.api.enums.SubjectKind kind,
                             @jakarta.validation.constraints.Size(max = 200) String name,
                             String relation, java.util.List<String> aliases, JsonNode attributes,
                             UUID connectionId, java.util.List<String> domains) {

    public com.jizza.engine.api.memory.SubjectDraft toDraft() {
      return new com.jizza.engine.api.memory.SubjectDraft(kind, name, relation, aliases, attributes,
          connectionId, domains);
    }
  }

  public record SubjectRelation(@NotNull UUID toSubjectId, @NotBlank String relation) {}

  public record MemorySettings(boolean autoLearn, Integer retentionDays) {}

  public record MemoryPreview(String agentKey, @NotBlank String request) {}

  /** Memory Cosmos: let one more agent see a fact. */
  public record ShareMemory(@NotNull UUID agentId) {}

  /** Memory Cosmos: who can see a fact from now on; empty means every agent. */
  public record MoveMemory(@NotNull java.util.List<String> domains) {}

  /** The body whose part of the sky to forget; the sun's id resets everything. */
  public record ResetMemory(@jakarta.validation.constraints.NotBlank String bodyId) {}

  public record EditNote(@jakarta.validation.constraints.Size(max = 200) String title,
                         @jakarta.validation.constraints.Size(max = 20000) String content) {}

  public record MoveNote(@NotNull UUID toAgentId) {}

  public record PromoteNote(com.jizza.engine.api.enums.MemoryCategory category, boolean removeNote) {}
}
