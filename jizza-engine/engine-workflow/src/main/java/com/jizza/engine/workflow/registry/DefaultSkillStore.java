package com.jizza.engine.workflow.registry;

import com.jizza.engine.api.dto.SkillDto;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.workflow.SkillSpec;
import com.jizza.engine.api.workflow.SkillStore;
import com.jizza.engine.persistence.entity.SkillEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.SkillRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The skills that expose a workflow to an agent as a callable tool (R32).
 *
 * <p>Whether a skill is invocable is decided here rather than by whoever saved it: it holds only
 * while the workflow version behind it is approved and enabled, and it is re-evaluated on every
 * status change. Rejecting or rolling back a procedure therefore takes its skill out of circulation
 * immediately, which is the Charter invariant that an agent cannot run what nobody approved.
 */
@Service
public class DefaultSkillStore implements SkillStore {

  private final SkillRepository skills;
  private final WorkflowRepository workflows;

  public DefaultSkillStore(SkillRepository skills, WorkflowRepository workflows) {
    this.skills = skills;
    this.workflows = workflows;
  }

  /** One skill per workflow version: saving again replaces it rather than adding a rival entry. */
  @Override
  @Transactional
  public SkillDto save(UUID workflowId, SkillSpec spec) {
    WorkflowEntity workflow = require(workflowId);

    SkillEntity entity = skills.findByWorkflowIdAndVersion(workflowId, workflow.getVersion())
        .orElseGet(SkillEntity::new);
    entity.setUserId(workflow.getUserId());
    entity.setWorkflowId(workflowId);
    entity.setVersion(workflow.getVersion());
    entity.setName(spec.name());
    entity.setDescription(spec.description());
    entity.setParameterSchema(spec.parameterSchema());
    entity.setInvocable(invocable(workflow, entity));
    skills.save(entity);
    return toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SkillDto> list(UUID userId) {
    return skills.findByUserId(userId).stream().map(DefaultSkillStore::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<SkillDto> find(UUID skillId) {
    return skills.findById(skillId).map(DefaultSkillStore::toDto);
  }

  /** Re-evaluates every skill of a workflow after a status change (approve, reject, rollback). */
  @Transactional
  public void refreshInvocability(UUID workflowId) {
    WorkflowEntity workflow = require(workflowId);
    for (SkillEntity skill : skills.findByWorkflowId(workflowId)) {
      skill.setInvocable(invocable(workflow, skill));
      skills.save(skill);
    }
  }

  private static boolean invocable(WorkflowEntity workflow, SkillEntity skill) {
    return workflow.getStatus() == WorkflowStatus.APPROVED
        && !workflow.isDisabled()
        && skill.getVersion() == workflow.getVersion();
  }

  private WorkflowEntity require(UUID workflowId) {
    return workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }

  public static SkillDto toDto(SkillEntity s) {
    return new SkillDto(s.getId(), s.getUserId(), s.getWorkflowId(), s.getVersion(), s.getName(),
        s.getDescription(), s.getParameterSchema(), s.isInvocable());
  }
}
