package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "generated_tools")
public class GeneratedToolEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID connectionId;

  @Column(nullable = false)
  private String name;

  @Column(columnDefinition = "text")
  private String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode inputSchema;

  @Column(nullable = false)
  private String httpMethod;

  @Column(nullable = false, columnDefinition = "text")
  private String pathTemplate;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode paramMapping;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode authMapping;

  private boolean sideEffecting;

  /** What the action does; null on rows that predate it, which derive it from the method. */
  private ActionType actionType;

  private boolean enabled;

  /** Display name in the service editor; {@code name} stays the machine name. */
  private String label;

  /** {@code request}, {@code polling} (lists items for a trigger) or {@code webhook}. */
  @Column(nullable = false)
  private String kind = "request";

  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }

  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getConnectionId() { return connectionId; }
  public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public JsonNode getInputSchema() { return inputSchema; }
  public void setInputSchema(JsonNode inputSchema) { this.inputSchema = inputSchema; }

  public String getHttpMethod() { return httpMethod; }
  public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

  public String getPathTemplate() { return pathTemplate; }
  public void setPathTemplate(String pathTemplate) { this.pathTemplate = pathTemplate; }

  public JsonNode getParamMapping() { return paramMapping; }
  public void setParamMapping(JsonNode paramMapping) { this.paramMapping = paramMapping; }

  public JsonNode getAuthMapping() { return authMapping; }
  public void setAuthMapping(JsonNode authMapping) { this.authMapping = authMapping; }

  public boolean isSideEffecting() { return sideEffecting; }
  public void setSideEffecting(boolean sideEffecting) { this.sideEffecting = sideEffecting; }

  public ActionType getActionType() { return actionType; }
  public void setActionType(ActionType actionType) { this.actionType = actionType; }

  /**
   * The action type the guardrails judge this tool by: what was stored, or what its method and
   * side-effect flag imply. A tool marked as changing nothing is a read whatever its method.
   */
  public ActionType effectiveActionType() {
    if (actionType != null) return actionType;
    return sideEffecting ? ActionType.riskier(ActionType.CREATE,
        ActionType.fromHttpMethod(httpMethod)) : ActionType.READ;
  }

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }

}
