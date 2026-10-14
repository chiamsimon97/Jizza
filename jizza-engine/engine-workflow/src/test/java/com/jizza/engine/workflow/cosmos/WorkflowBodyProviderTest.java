package com.jizza.engine.workflow.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.cosmos.BodyType;
import com.jizza.engine.api.cosmos.CelestialBody;
import com.jizza.engine.api.cosmos.CosmosAgent;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.SkillEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.SkillRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WorkflowBodyProviderTest {

  @Test
  void ownedWorkflowsAreMoonsAndASkillBuiltFromOneOrbitsIt() {
    UUID userId = UUID.randomUUID();
    UUID agentId = UUID.randomUUID();
    CosmosAgent kai = new CosmosAgent(agentId, "finance", "Kai", "finance", false, "AGENT:" + agentId);

    WorkflowEntity rebalance = workflow(userId, agentId, "Monthly rebalance", WorkflowOrigin.AGENT_AUTHORED);
    WorkflowEntity someoneElses = workflow(userId, UUID.randomUUID(), "Not Kai's", WorkflowOrigin.USER_BUILT);
    SkillEntity skill = mock(SkillEntity.class);
    UUID skillId = UUID.randomUUID();
    when(skill.getId()).thenReturn(skillId);
    when(skill.getUserId()).thenReturn(userId);
    when(skill.getWorkflowId()).thenReturn(rebalance.getId());
    when(skill.getName()).thenReturn("rebalance");
    AgentCapabilityEntity grant = new AgentCapabilityEntity();
    grant.setAgentId(agentId);
    grant.setCapabilityType(CapabilityType.SKILL);
    grant.setCapabilityRef(skillId);

    WorkflowRepository workflows = mock(WorkflowRepository.class);
    SkillRepository skills = mock(SkillRepository.class);
    AgentCapabilityRepository capabilities = mock(AgentCapabilityRepository.class);
    when(workflows.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(rebalance, someoneElses));
    when(capabilities.findByAgentIdAndCapabilityType(agentId, CapabilityType.SKILL)).thenReturn(List.of(grant));
    when(skills.findAllById(any())).thenReturn(List.of(skill));

    Map<BodyType, CelestialBody> bodies = new WorkflowBodyProvider(workflows, skills, capabilities)
        .bodies(userId, kai).stream().collect(Collectors.toMap(CelestialBody::type, b -> b));

    assertThat(bodies).containsOnlyKeys(BodyType.WORKFLOW, BodyType.SKILL);
    assertThat(bodies.get(BodyType.WORKFLOW).label()).isEqualTo("Monthly rebalance");
    assertThat(bodies.get(BodyType.WORKFLOW).meta()).containsEntry("procedure", true);
    assertThat(bodies.get(BodyType.SKILL).parentBodyId()).isEqualTo("WORKFLOW:" + rebalance.getId());
  }

  private static WorkflowEntity workflow(UUID userId, UUID owner, String name, WorkflowOrigin origin) {
    WorkflowEntity w = new WorkflowEntity();
    w.setId(UUID.randomUUID());
    w.setUserId(userId);
    w.setOwningAgentId(owner);
    w.setName(name);
    w.setOrigin(origin);
    w.setStatus(WorkflowStatus.APPROVED);
    return w;
  }
}
