package com.jizza.engine.connectors.tools.generate;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.connector.ToolCatalog;
import com.jizza.engine.api.connector.ToolGenerator;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.DocFormat;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.connectors.service.ConnectionService;
import com.jizza.engine.connectors.tools.registry.GeneratedToolProvider;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an attached API document into persisted tools (R26): a deterministic OpenAPI parse first,
 * an LLM-assisted extraction only where that is not possible.
 *
 * <p>Re-ingesting a document replaces that connection's tools rather than accumulating duplicates,
 * and existing agent assignments to a tool whose name survives are preserved.
 *
 * <p>{@code setEnabled} takes a bare tool id with nothing to say which connection, and so which
 * user, it belongs to -- that ownership check is {@code AttachApiDocUseCase}'s job, using
 * {@code find} to resolve the owning connection first.
 */
@Service
public class ApiDocIngestService implements ToolGenerator, ToolCatalog {

  private static final Logger log = LoggerFactory.getLogger(ApiDocIngestService.class);

  private static final long MAX_DOCUMENT_BYTES = 8L * 1024 * 1024;

  private final OpenApiToolGenerator openApi;
  private final FreeformToolExtractor freeform;
  private final GeneratedToolRepository tools;
  private final ConnectionRepository connections;
  private final ConnectionService connectionService;
  private final AuditLog audit;

  public ApiDocIngestService(OpenApiToolGenerator openApi, FreeformToolExtractor freeform,
                             GeneratedToolRepository tools, ConnectionRepository connections,
                             ConnectionService connectionService, AuditLog audit) {
    this.openApi = openApi;
    this.freeform = freeform;
    this.tools = tools;
    this.connections = connections;
    this.connectionService = connectionService;
    this.audit = audit;
  }

  @Override
  @Transactional
  public List<GeneratedToolDto> fromApiDoc(UUID connectionId, byte[] document, DocFormat format) {
    if (document == null || document.length == 0) {
      throw new ValidationException("No document was uploaded");
    }
    if (document.length > MAX_DOCUMENT_BYTES) {
      throw new ValidationException("API documents are limited to "
          + (MAX_DOCUMENT_BYTES / 1024 / 1024) + " MB");
    }
    ConnectionEntity connection = connectionService.require(connectionId);

    List<GeneratedToolDto> generated;
    String strategy;
    if (format == DocFormat.FREEFORM) {
      generated = freeform.extract(connectionId, document);
      strategy = "llm-assisted";
    } else {
      OpenApiToolGenerator.Parsed parsed = openApi.parse(connectionId, document);
      generated = parsed.tools();
      strategy = "openapi";
      // The document's server URL is a convenience default only; the user can override it, and the
      // egress guard confines calls to whatever is stored on the connection either way.
      if (parsed.serverUrl() != null && isBlank(connection.getBaseUrl())) {
        connection.setBaseUrl(parsed.serverUrl());
        connections.save(connection);
      }
      if (!parsed.warnings().isEmpty()) {
        log.info("Parsed the specification for {} with {} warnings", connection.getServiceName(),
            parsed.warnings().size());
      }
    }

    tools.deleteByConnectionId(connectionId);
    tools.flush();

    List<GeneratedToolDto> saved = new ArrayList<>();
    for (GeneratedToolDto dto : generated) {
      GeneratedToolEntity entity = new GeneratedToolEntity();
      entity.setConnectionId(connectionId);
      entity.setName(dto.name());
      entity.setDescription(dto.description());
      entity.setInputSchema(dto.inputSchema());
      entity.setHttpMethod(dto.httpMethod());
      entity.setPathTemplate(dto.pathTemplate());
      entity.setParamMapping(dto.paramMapping());
      entity.setAuthMapping(dto.authMapping());
      entity.setSideEffecting(dto.sideEffecting());
      entity.setEnabled(true);
      saved.add(GeneratedToolProvider.toDto(tools.save(entity)));
    }

    audit.record(connection.getUserId(), ActorType.USER, null, "connection.tools_generated",
        connection.getServiceName(), Json.obj().put("strategy", strategy)
            .put("toolCount", saved.size()));
    return saved;
  }

  @Override
  @Transactional(readOnly = true)
  public List<GeneratedToolDto> list(UUID connectionId) {
    return tools.findByConnectionId(connectionId).stream()
        .map(GeneratedToolProvider::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<GeneratedToolDto> find(UUID toolId) {
    return tools.findById(toolId).map(GeneratedToolProvider::toDto);
  }

  @Override
  @Transactional
  public GeneratedToolDto setEnabled(UUID toolId, boolean enabled) {
    GeneratedToolEntity entity = tools.findById(toolId)
        .orElseThrow(() -> new com.jizza.engine.api.error.NotFoundException("Generated tool", toolId));
    entity.setEnabled(enabled);
    return GeneratedToolProvider.toDto(tools.save(entity));
  }

  private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
