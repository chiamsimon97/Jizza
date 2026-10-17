package com.jizza.engine.orchestrator.directory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.AgentTemplateStore;
import com.jizza.engine.persistence.entity.AgentTemplateEntity;
import com.jizza.engine.persistence.repository.AgentTemplateRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultAgentTemplateStore implements AgentTemplateStore {

  private final AgentTemplateRepository templates;

  public DefaultAgentTemplateStore(AgentTemplateRepository templates) {
    this.templates = templates;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Template> list(UUID workspaceId) {
    return templates.findByWorkspaceIdOrderByNameAsc(workspaceId).stream()
        .map(DefaultAgentTemplateStore::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Template> published(Collection<UUID> workspaceIds) {
    if (workspaceIds.isEmpty()) return List.of();
    return templates.findByWorkspaceIdInAndStatusOrderByNameAsc(workspaceIds, "published").stream()
        .map(DefaultAgentTemplateStore::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Template> find(UUID templateId) {
    return templates.findById(templateId).map(DefaultAgentTemplateStore::toDto);
  }

  @Override
  @Transactional
  public Template create(UUID workspaceId, UUID userId, String name, String description,
                         JsonNode definition) {
    AgentTemplateEntity t = new AgentTemplateEntity();
    t.setWorkspaceId(workspaceId);
    t.setCreatedByUserId(userId);
    t.setName(name);
    t.setDescription(description);
    t.setDefinition(definition);
    return toDto(templates.save(t));
  }

  @Override
  @Transactional
  public Template update(UUID templateId, String name, String description, JsonNode definition) {
    AgentTemplateEntity t = require(templateId);
    t.setName(name);
    t.setDescription(description);
    t.setDefinition(definition);
    t.setVersion(t.getVersion() + 1);
    t.setUpdatedAt(Instant.now());
    return toDto(templates.save(t));
  }

  @Override
  @Transactional
  public Template setStatus(UUID templateId, String status) {
    AgentTemplateEntity t = require(templateId);
    t.setStatus(status);
    t.setUpdatedAt(Instant.now());
    return toDto(templates.save(t));
  }

  @Override
  @Transactional
  public void delete(UUID templateId) {
    templates.deleteById(templateId);
  }

  private AgentTemplateEntity require(UUID id) {
    return templates.findById(id).orElseThrow(() -> new NotFoundException("Agent template", id));
  }

  private static Template toDto(AgentTemplateEntity t) {
    return new Template(t.getId(), t.getWorkspaceId(), t.getName(), t.getDescription(),
        t.getDefinition(), t.getStatus(), t.getVersion(), t.getCreatedByUserId(), t.getUpdatedAt());
  }
}
