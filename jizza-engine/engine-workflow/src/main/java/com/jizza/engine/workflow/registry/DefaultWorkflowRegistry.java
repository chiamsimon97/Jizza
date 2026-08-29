package com.jizza.engine.workflow.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.SkillDto;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.persistence.entity.SkillEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowVersionEntity;
import com.jizza.engine.persistence.repository.SkillRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import com.jizza.engine.workflow.engine.WorkflowValidator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflows, their immutable version snapshots, and the skills that expose them (R31/R32).
 *
 * <p>A skill is invocable only while its workflow version is approved, which is checked on read
 * rather than frozen at save time -- so rejecting or rolling back a procedure takes its skill out
 * of circulation immediately.
 */
@Service
public class DefaultWorkflowRegistry implements WorkflowRegistry {

  private final WorkflowRepository workflows;
  private final WorkflowVersionRepository versions;
  private final SkillRepository skills;
  private final WorkflowValidator validator;
  private final AuditLog audit;

  public DefaultWorkflowRegistry(WorkflowRepository workflows, WorkflowVersionRepository versions,
                                 SkillRepository skills, WorkflowValidator validator,
                                 AuditLog audit) {
    this.workflows = workflows;
    this.versions = versions;
    this.skills = skills;
    this.validator = validator;
    this.audit = audit;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<WorkflowDto> find(UUID workflowId) {
    return workflows.findById(workflowId).map(DefaultWorkflowRegistry::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<WorkflowDto> findVersion(UUID workflowId, int version) {
    return workflows.findById(workflowId).flatMap(workflow -> {
      if (workflow.getVersion() == version) return Optional.of(toDto(workflow));
      return versions.findByWorkflowIdAndVersion(workflowId, version)
          .map(snapshot -> snapshotDto(workflow, snapshot));
    });
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkflowDto> versions(UUID workflowId) {
    WorkflowEntity workflow = require(workflowId);
    return versions.findByWorkflowIdOrderByVersionAsc(workflowId).stream()
        .map(snapshot -> snapshotDto(workflow, snapshot)).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkflowDto> list(UUID userId) {
    return workflows.findByUserIdOrderByCreatedAtDesc(userId).stream()
        .map(DefaultWorkflowRegistry::toDto).toList();
  }

  @Override
  @Transactional
  public WorkflowDto save(WorkflowDto dto) {
    WorkflowGraph graph = WorkflowGraph.from(dto.graph());
    validator.validate(graph);

    WorkflowEntity entity = dto.id() == null ? new WorkflowEntity() : require(dto.id());
    boolean creating = dto.id() == null;
    if (creating) {
      entity.setUserId(dto.userId());
      entity.setOrigin(dto.origin() == null
          ? com.jizza.engine.api.enums.WorkflowOrigin.USER_BUILT : dto.origin());
      entity.setStatus(dto.status() == null ? WorkflowStatus.DRAFT : dto.status());
      entity.setVersion(1);
      entity.setOwningAgentId(dto.owningAgentId());
      entity.setSourceTaskId(dto.sourceTaskId());
    }
    entity.setName(dto.name());
    entity.setDescription(dto.description());
    entity.setGraph(graph.toJson());
    entity.setGraphFingerprint(graph.fingerprint());
    entity.setInputSchema(dto.inputSchema());
    entity.setTriggerConfig(dto.triggerConfig());
    workflows.save(entity);
    snapshot(entity);

    audit.record(entity.getUserId(), ActorType.USER, entity.getOwningAgentId(),
        creating ? "workflow.created" : "workflow.saved", entity.getName(),
        Json.obj().put("workflowId", entity.getId().toString()).put("version", entity.getVersion()));
    return toDto(entity);
  }

  /** Writes the immutable snapshot for the workflow's current version (R2 {@code workflow_versions}). */
  @Transactional
  public void snapshot(WorkflowEntity entity) {
    WorkflowVersionEntity snapshot = versions
        .findByWorkflowIdAndVersion(entity.getId(), entity.getVersion())
        .orElseGet(WorkflowVersionEntity::new);
    snapshot.setWorkflowId(entity.getId());
    snapshot.setVersion(entity.getVersion());
    snapshot.setGraph(entity.getGraph());
    snapshot.setStatus(entity.getStatus());
    snapshot.setGraphFingerprint(entity.getGraphFingerprint());
    versions.save(snapshot);
  }

  @Override
  @Transactional
  public SkillDto saveAsSkill(UUID workflowId, String name, String description,
                              JsonNode parameterSchema) {
    WorkflowEntity workflow = require(workflowId);
    if (name == null || name.isBlank()) throw new ValidationException("A skill needs a name");

    SkillEntity entity = skills.findByWorkflowIdAndVersion(workflowId, workflow.getVersion())
        .orElseGet(SkillEntity::new);
    entity.setUserId(workflow.getUserId());
    entity.setWorkflowId(workflowId);
    entity.setVersion(workflow.getVersion());
    entity.setName(name.trim());
    entity.setDescription(description);
    entity.setParameterSchema(parameterSchema);
    entity.setInvocable(workflow.getStatus() == WorkflowStatus.APPROVED && !workflow.isDisabled());
    skills.save(entity);

    audit.record(workflow.getUserId(), ActorType.USER, workflow.getOwningAgentId(),
        "workflow.saved_as_skill", name,
        Json.obj().put("workflowId", workflowId.toString())
            .put("invocable", entity.isInvocable()));
    return toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SkillDto> skills(UUID userId) {
    return skills.findByUserId(userId).stream().map(DefaultWorkflowRegistry::toDto).toList();
  }

  /** Re-evaluates every skill's invocability after a status change (approve, reject, rollback). */
  @Transactional
  public void refreshSkillInvocability(UUID workflowId) {
    WorkflowEntity workflow = require(workflowId);
    for (SkillEntity skill : skills.findByWorkflowId(workflowId)) {
      boolean invocable = workflow.getStatus() == WorkflowStatus.APPROVED
          && !workflow.isDisabled()
          && skill.getVersion() == workflow.getVersion();
      skill.setInvocable(invocable);
      skills.save(skill);
    }
  }

  public WorkflowEntity require(UUID workflowId) {
    return workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }

  public static WorkflowDto toDto(WorkflowEntity w) {
    return new WorkflowDto(w.getId(), w.getUserId(), w.getName(), w.getDescription(), w.getGraph(),
        w.getInputSchema(), w.getTriggerConfig(), w.getOrigin(), w.getStatus(),
        w.getOwningAgentId(), w.getVersion(), w.getSourceTaskId(), w.getCreatedAt());
  }

  private static WorkflowDto snapshotDto(WorkflowEntity workflow, WorkflowVersionEntity snapshot) {
    return new WorkflowDto(workflow.getId(), workflow.getUserId(), workflow.getName(),
        workflow.getDescription(), snapshot.getGraph(), workflow.getInputSchema(),
        workflow.getTriggerConfig(), workflow.getOrigin(), snapshot.getStatus(),
        workflow.getOwningAgentId(), snapshot.getVersion(), workflow.getSourceTaskId(),
        snapshot.getCreatedAt());
  }

  public static SkillDto toDto(SkillEntity s) {
    return new SkillDto(s.getId(), s.getUserId(), s.getWorkflowId(), s.getVersion(), s.getName(),
        s.getDescription(), s.getParameterSchema(), s.isInvocable());
  }
}
