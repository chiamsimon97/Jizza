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

  /** The longest chat message or run instruction accepted, in characters. */
  public static final int MAX_MESSAGE = 32_000;

  // Bounded so an oversized field cannot make the server spend Argon2 time on it.
  /**
   * {@code accountType} defaults to an Individual account. An Organization account needs
   * {@code organizationName}. {@code invitationToken} comes from an emailed membership request.
   */
  public record Register(@Email @NotBlank @Size(max = 320) String email,
                         @NotBlank @Size(max = 256) String password,
                         com.jizza.engine.api.enums.WorkspaceType accountType,
                         @Size(max = 100) String organizationName,
                         @Size(max = 128) String invitationToken) {}

  public record InviteMember(@Email @NotBlank @Size(max = 320) String email,
                             @NotNull com.jizza.engine.api.enums.WorkspaceRole role) {}

  public record ChangeRole(@NotNull com.jizza.engine.api.enums.WorkspaceRole role) {}

  public record SwitchWorkspace(@NotNull UUID workspaceId) {}

  public record CreateOrganization(@NotBlank @Size(min = 2, max = 100) String name) {}

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
  public record Chat(@NotBlank @Size(max = MAX_MESSAGE) String message, UUID conversationId,
                     @Size(max = 200) String screen) {}

  /**
   * A message sent as a background turn. {@code clientMessageId} makes a retried send safe;
   * {@code context} is what is open on the screen, such as the Builder step being configured.
   */
  public record Turn(@NotBlank @Size(max = MAX_MESSAGE) String message, UUID conversationId,
                     UUID clientMessageId, @Size(max = 200) String screen, Boolean incognito, JsonNode context) {}

  public record ConversationCreate(@Size(max = 200) String title) {}

  public record ConversationPatch(@Size(max = 200) String title, Boolean pinned, Boolean archived) {}

  /** A thread's learning file, edited by hand. */
  public record ThreadGuidePut(@NotNull @Size(max = 20_000) String markdown) {}

  public record AgentPatch(@Size(max = 80) String name, Boolean enabled) {}

  public record Connection(@NotBlank @Size(max = 200) String serviceName, @NotNull AuthScheme authScheme,
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

  /** {@code reuse}: keep it in the workflow library with these guardrails (a {@code ReuseProfile}). */
  public record WorkflowSave(UUID id, @NotBlank @Size(max = 200) String name,
                             @Size(max = 4000) String description,
                             @NotNull JsonNode graph, JsonNode inputSchema, JsonNode triggerConfig,
                             UUID owningAgentId, JsonNode reuse) {}

  public record SaveAsSkill(@NotBlank @Size(max = 200) String name,
                            @Size(max = 4000) String description, JsonNode parameterSchema) {}

  public record RunWorkflow(Integer version, JsonNode input) {}

  /** A formula to try against sample run data ({@code input}, {@code nodes}, {@code vars}). */
  /** A Script step's settings ({@code inputs}, {@code script}, {@code outputs}) to try against sample run data. */
  public record PreviewScript(@NotNull JsonNode step, JsonNode data) {}

  public record PreviewFormula(@NotBlank @Size(max = 10_000) String formula, Boolean template, JsonNode data) {}

  public record ResumeRun(@Size(max = MAX_MESSAGE) String instruction, Boolean approved) {}

  public record ReviseProcedure(@NotNull JsonNode graph) {}

  public record RejectProcedure(@Size(max = 4000) String feedback) {}

  public record Task(@NotBlank @Size(max = 200) String title, @NotNull UUID agentId, JsonNode payload,
                     @NotNull ScheduleType scheduleType, String cronOrRrule, Instant runAt,
                     String timezone, Boolean repetitive) {}

  public record TaskRepetitive(boolean repetitive) {}

  public record AnswerNotification(@NotNull com.jizza.engine.api.notification.Answer.Kind kind,
                                   @Size(max = MAX_MESSAGE) String instruction,
                                   @Size(max = 4000) String feedback) {}

  public record SnoozeNotification(java.time.Instant until) {}

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

  /** Memory pasted from another assistant; read only as data, one fact per line. */
  public record MemoryImport(@NotBlank @jakarta.validation.constraints.Size(max = 200_000) String text,
                             @jakarta.validation.constraints.Size(max = 60) String provider) {}

  public record MemoryPreview(String agentKey, @NotBlank @Size(max = 4000) String request) {}

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

  // ── Public organizations ──────────────────────────────────────────────────

  /** The organization's public profile. Fully checked in {@code PublicListingUseCase}. */
  public record ListingProfile(@NotBlank @Size(max = 100) String displayName,
                               @Size(max = 500) String description,
                               @Size(max = 40) String category,
                               @Size(max = 500) String logoUrl,
                               @Size(max = 500) String website) {}

  /** A reviewer's reason (required for reject and suspend) or note. */
  public record ReviewNote(@Size(max = 1000) String note) {}

  public record AskToJoin(@Size(max = 500) String message) {}
}
