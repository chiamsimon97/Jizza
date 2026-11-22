package com.jizza.engine.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AuthScheme;
import com.jizza.engine.api.enums.ConnectionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The pieces a Connector (Service) is built from.
 *
 * <p>A <b>service</b> is a third-party integration: metadata, an authentication scheme and the
 * user's authenticated connection to it. Underneath it, <b>APIs</b> are raw HTTP requests;
 * <b>resources</b> are the entities the service deals in; <b>actions</b> are what an agent can do
 * (each built on one API); <b>triggers</b> are events found by polling an API; and
 * <b>variables</b> are constants the APIs reference as {@code {{var.name}}}.
 */
public final class ServiceModel {

  private ServiceModel() {}

  public static final String DRAFT = "draft";
  public static final String LIVE = "live";

  public record Info(UUID id, String name, String slug, String description, String category,
                     String baseUrl, AuthScheme authScheme, ConnectionStatus connectionStatus,
                     boolean credentialPresent, String versionStatus, int liveVersion,
                     Instant publishedAt, int apiCount, int actionCount, int triggerCount,
                     Instant createdAt, Instant updatedAt) {}

  public record InfoPatch(String name, String description, String category, String baseUrl) {}

  /**
   * A raw HTTP request. {@code paramMapping} says which inputs go in the path, query, headers and
   * body, and may carry fixed {@code headers}; any of those may use {@code {{var.name}}}.
   */
  public record Api(UUID id, UUID serviceId, String name, String label, String description,
                    String kind, String httpMethod, String pathTemplate, JsonNode inputSchema,
                    JsonNode paramMapping, boolean sideEffecting, boolean enabled) {}

  public record ApiInput(String name, String label, String description, String kind,
                         String httpMethod, String pathTemplate, JsonNode inputSchema,
                         JsonNode paramMapping, Boolean sideEffecting) {}

  /** {@code fields} is a list of {@code {key, label, type, required}}. */
  public record Resource(UUID id, UUID serviceId, String name, String label, String description,
                         JsonNode fields, String idField) {}

  public record ResourceInput(String name, String label, String description, JsonNode fields,
                              String idField) {}

  /** {@code agentIds} are the agents allowed to use this action as a tool. */
  public record Action(UUID id, UUID serviceId, String name, String label, String description,
                       UUID apiId, UUID resourceId, boolean enabled, boolean sideEffecting,
                       String toolName, java.util.List<UUID> agentIds) {}

  public record ActionInput(String name, String label, String description, UUID apiId,
                            UUID resourceId, Boolean enabled) {}

  /**
   * @param kind           {@code polling}, or {@code webhook}: the service calls {@code webhookUrl}
   * @param webhookUrl     where the service sends its events; null for a polling trigger
   * @param signed         whether each delivery must carry a signature made with a shared secret
   * @param workflowIds    the workflows that start whenever it fires
   */
  public record Trigger(UUID id, UUID serviceId, String name, String label, String description,
                        UUID apiId, UUID resourceId, String kind, int pollIntervalMinutes,
                        String itemsPath, String idField, boolean enabled, Instant lastPolledAt,
                        String webhookUrl, boolean signed, String signatureHeader,
                        String signaturePrefix, Instant lastReceivedAt,
                        java.util.List<UUID> workflowIds) {}

  /**
   * @param kind          {@code polling} (the default) or {@code webhook}
   * @param signingSecret null keeps the current one; blank removes it
   */
  public record TriggerInput(String name, String label, String description, UUID apiId,
                             UUID resourceId, Integer pollIntervalMinutes, String itemsPath,
                             String idField, Boolean enabled, String kind, String signingSecret,
                             String signatureHeader, String signaturePrefix) {
    public TriggerInput(String name, String label, String description, UUID apiId,
                        UUID resourceId, Integer pollIntervalMinutes, String itemsPath,
                        String idField, Boolean enabled) {
      this(name, label, description, apiId, resourceId, pollIntervalMinutes, itemsPath, idField,
          enabled, null, null, null, null);
    }
  }

  /** What became of one delivery to a webhook trigger. */
  public record WebhookDelivery(boolean accepted, String reason, int items, int runs) {}

  /** {@code value} is never returned for a secret variable; {@code valueSet} says one exists. */
  public record Variable(UUID id, UUID serviceId, String name, String description, String value,
                         boolean secret, boolean valueSet) {}

  public record VariableInput(String name, String description, String value, Boolean secret) {}

  public record Run(UUID id, UUID serviceId, String kind, UUID refId, String refName,
                    String source, String status, Long durationMs, JsonNode input,
                    JsonNode output, String error, Instant createdAt) {}

  /** Items a trigger found; {@code newItems} are those it had not seen before. */
  public record TriggerSample(boolean ok, String error, int totalItems, int newItems,
                              JsonNode items) {}

  public static final String POLLING = "polling";
  public static final String WEBHOOK = "webhook";
}
