package com.jizza.engine.api.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for organizations' agent templates. Policy-free: who may publish or install one, and
 * what a valid definition is, are the platform's decisions.
 */
public interface AgentTemplateStore {

  /**
   * @param definition one agent or a team: {@code {"agents":[{ref, name, description,
   *                   instructions, avatar, modelTier, reportsTo, responsibilities, tools,
   *                   modes, locks}]}}
   * @param status     {@code draft}, {@code published} or {@code unpublished}
   */
  record Template(UUID id, UUID workspaceId, String name, String description, JsonNode definition,
                  String status, int version, UUID createdByUserId, Instant updatedAt) {}

  List<Template> list(UUID workspaceId);

  List<Template> published(Collection<UUID> workspaceIds);

  Optional<Template> find(UUID templateId);

  Template create(UUID workspaceId, UUID userId, String name, String description,
                  JsonNode definition);

  /** Saves a new definition and moves the version on by one. */
  Template update(UUID templateId, String name, String description, JsonNode definition);

  Template setStatus(UUID templateId, String status);

  void delete(UUID templateId);
}
