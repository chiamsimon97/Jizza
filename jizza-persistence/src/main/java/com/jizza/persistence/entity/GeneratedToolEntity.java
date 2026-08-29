package com.jizza.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
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

  private boolean enabled;

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

  public boolean getSideEffecting() { return sideEffecting; }
  public void setSideEffecting(boolean sideEffecting) { this.sideEffecting = sideEffecting; }

  public boolean getEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }

}
