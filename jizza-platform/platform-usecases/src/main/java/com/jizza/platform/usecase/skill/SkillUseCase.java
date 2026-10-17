package com.jizza.platform.usecase.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.SkillDto;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.SkillSpec;
import com.jizza.engine.api.workflow.SkillStore;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turning a workflow into something an agent can call by name (R32) -- "save as skill".
 *
 * <p>The product decisions live here: a skill must be named, the name is what an agent will see in
 * its tool list so it is tidied on the way in, and saving one is worth recording in the activity
 * log. The engine stores the result and decides, on its own authority, whether the skill is
 * currently invocable -- that follows the approval state of the workflow behind it and is not the
 * platform's to override.
 */
@Service
public class SkillUseCase {

  private final SkillStore skills;
  private final WorkflowRegistry workflows;
  private final AuditLog audit;

  public SkillUseCase(SkillStore skills, WorkflowRegistry workflows, AuditLog audit) {
    this.skills = skills;
    this.workflows = workflows;
    this.audit = audit;
  }

  @Transactional
  public SkillDto createFromWorkflow(UUID userId, UUID workflowId, String name, String description,
                                     JsonNode parameterSchema) {
    WorkflowDto workflow = ownedWorkflow(userId, workflowId);
    if (name == null || name.isBlank()) {
      throw new ValidationException("A skill needs a name");
    }

    SkillDto skill = skills.save(workflowId,
        new SkillSpec(name.trim(), description, parameterSchema));

    audit.record(userId, ActorType.USER, workflow.owningAgentId(), "workflow.saved_as_skill",
        skill.name(), Json.obj().put("workflowId", workflowId.toString())
            .put("invocable", skill.invocable()));
    return skill;
  }

  @Transactional(readOnly = true)
  public List<SkillDto> list(UUID userId) {
    return skills.list(userId);
  }

  private WorkflowDto ownedWorkflow(UUID userId, UUID workflowId) {
    return workflows.find(workflowId)
        .filter(workflow -> workflow.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }
}
