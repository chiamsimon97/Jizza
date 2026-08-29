package com.jizza.engine.workflow.tools;

import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.SkillEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.SkillRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes approved skills as callable tools (R32).
 *
 * <p>Two gates, both re-checked at call time rather than trusted from when the skill was saved: the
 * agent must hold the capability, and the skill's workflow version must still be approved. A
 * rolled-back or rejected procedure therefore stops being callable the moment its status changes.
 */
@Component
public class SkillToolProvider implements ToolProvider {

  private final AgentCapabilityRepository capabilities;
  private final SkillRepository skills;
  private final WorkflowRepository workflows;
  private final WorkflowEngine engine;

  public SkillToolProvider(AgentCapabilityRepository capabilities, SkillRepository skills,
                           WorkflowRepository workflows, WorkflowEngine engine) {
    this.capabilities = capabilities;
    this.skills = skills;
    this.workflows = workflows;
    this.engine = engine;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ToolSpec> toolsFor(UUID agentId) {
    return invocableSkills(agentId).stream()
        .map(skill -> new ToolSpec(toolName(skill), skill.getDescription(),
            skill.getParameterSchema() == null
                ? JsonSchemas.emptyObject() : skill.getParameterSchema(),
            true))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean supports(UUID agentId, String toolName) {
    return find(agentId, toolName).isPresent();
  }

  @Override
  @Transactional
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<SkillEntity> skill = find(agentId, call.name());
    if (skill.isEmpty()) {
      return ToolResult.error("Skill '" + call.name() + "' is not available to this agent. A skill "
          + "is callable only while its workflow version is approved.");
    }
    var run = engine.start(skill.get().getWorkflowId(), skill.get().getVersion(), call.args(),
        "skill:" + agentId);
    return ToolResult.ok(Json.obj()
        .put("run_id", run.id().toString())
        .put("status", run.status().name().toLowerCase(java.util.Locale.ROOT))
        .put("current_node", run.currentNode()));
  }

  private Optional<SkillEntity> find(UUID agentId, String toolName) {
    return invocableSkills(agentId).stream()
        .filter(skill -> toolName(skill).equals(toolName))
        .findFirst();
  }

  private List<SkillEntity> invocableSkills(UUID agentId) {
    List<UUID> refs = capabilities.findByAgentIdAndCapabilityType(agentId, CapabilityType.SKILL)
        .stream().map(AgentCapabilityEntity::getCapabilityRef).toList();
    if (refs.isEmpty()) return List.of();

    return skills.findAllById(refs).stream()
        .filter(SkillEntity::isInvocable)
        .filter(skill -> workflows.findById(skill.getWorkflowId())
            .filter(w -> w.getStatus() == WorkflowStatus.APPROVED)
            .filter(w -> !w.isDisabled())
            .filter(w -> w.getVersion() == skill.getVersion())
            .isPresent())
        .toList();
  }

  /** Model-facing name: the skill's own name, normalised to a legal tool identifier. */
  static String toolName(SkillEntity skill) {
    return "skill_" + skill.getName().toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
  }
}
