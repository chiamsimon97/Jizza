package com.jizza.engine.workflow.cosmos;

import com.jizza.engine.api.cosmos.BodyType;
import com.jizza.engine.api.cosmos.CelestialBody;
import com.jizza.engine.api.cosmos.CosmosAgent;
import com.jizza.engine.api.cosmos.CosmosBodyProvider;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.persistence.entity.SkillEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.SkillRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The workflows an agent owns (learned procedures among them) and the skills granted to it, as
 * moons. A skill built from a workflow the same agent owns orbits that workflow instead of the
 * planet.
 */
@Component
public class WorkflowBodyProvider implements CosmosBodyProvider {

  private final WorkflowRepository workflows;
  private final SkillRepository skills;
  private final AgentCapabilityRepository capabilities;

  public WorkflowBodyProvider(WorkflowRepository workflows, SkillRepository skills,
                              AgentCapabilityRepository capabilities) {
    this.workflows = workflows;
    this.skills = skills;
    this.capabilities = capabilities;
  }

  @Override
  public List<CelestialBody> bodies(UUID userId, CosmosAgent agent) {
    List<CelestialBody> out = new ArrayList<>();
    Set<UUID> owned = new HashSet<>();
    for (WorkflowEntity w : workflows.findByUserIdOrderByCreatedAtDesc(userId)) {
      if (!agent.id().equals(w.getOwningAgentId())) continue;
      owned.add(w.getId());
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("workflowId", w.getId().toString());
      meta.put("status", String.valueOf(w.getStatus()).toLowerCase(Locale.ROOT));
      meta.put("procedure", w.getOrigin() == WorkflowOrigin.AGENT_AUTHORED);
      out.add(new CelestialBody(BodyType.WORKFLOW.name() + ":" + w.getId(), BodyType.WORKFLOW,
          agent.bodyId(), w.getName(), agent.key(), 0, 0, 0, w.getCreatedAt(), meta));
    }

    List<UUID> skillIds = capabilities.findByAgentIdAndCapabilityType(agent.id(), CapabilityType.SKILL)
        .stream().map(c -> c.getCapabilityRef()).toList();
    if (!skillIds.isEmpty()) {
      for (SkillEntity s : skills.findAllById(skillIds)) {
        if (!s.getUserId().equals(userId)) continue;
        String parent = owned.contains(s.getWorkflowId())
            ? BodyType.WORKFLOW.name() + ":" + s.getWorkflowId() : agent.bodyId();
        out.add(new CelestialBody(BodyType.SKILL.name() + ":" + s.getId() + ":" + agent.id(),
            BodyType.SKILL, parent, s.getName(), agent.key(), 0, 0, 0, null,
            Map.of("skillId", s.getId().toString(), "workflowId", s.getWorkflowId().toString())));
      }
    }
    return out;
  }
}
