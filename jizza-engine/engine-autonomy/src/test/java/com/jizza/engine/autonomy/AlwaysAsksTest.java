package com.jizza.engine.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.autonomy.policy.PolicyAutonomyGate;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** A source can insist on approval for a tool, even where the person's rules say allow. */
class AlwaysAsksTest {

  private final UUID agentId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  private PolicyAutonomyGate gate(AutonomyRule rule) {
    AgentRepository agents = mock(AgentRepository.class);
    AgentEntity jizza = new AgentEntity();
    jizza.setId(agentId);
    jizza.setUserId(userId);
    jizza.setEnabled(true);
    when(agents.findById(agentId)).thenReturn(Optional.of(jizza));
    AutonomyPolicyRepository policies = mock(AutonomyPolicyRepository.class);
    AutonomyPolicyEntity policy = new AutonomyPolicyEntity();
    policy.setUserId(userId);
    policy.setRule(rule);
    when(policies.findByUserIdOrderByPrecedenceDesc(any())).thenReturn(List.of(policy));
    PolicyAutonomyGate gate = new PolicyAutonomyGate(policies, agents,
        mock(GeneratedToolRepository.class));
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("source", new ActionTypeSource() {
      @Override public Optional<ActionType> actionTypeOf(UUID agent, String tool) {
        return Optional.of(ActionType.UPDATE);
      }
      @Override public boolean alwaysAsks(UUID agent, String tool) {
        return tool.equals("device_chrome__browser_click");
      }
    });
    gate.setActionTypeSources(beans.getBeanProvider(ActionTypeSource.class));
    return gate;
  }

  @Test
  void anAllowRuleDoesNotSkipTheApproval() {
    var decision = gate(AutonomyRule.ALLOW).evaluate(agentId, "device_chrome__browser_click", null);
    assertThat(decision.rule()).isEqualTo(AutonomyRule.ASK);
  }

  @Test
  void otherToolsStillFollowTheRule() {
    var decision = gate(AutonomyRule.ALLOW).evaluate(agentId, "device_chrome__other", null);
    assertThat(decision.rule()).isEqualTo(AutonomyRule.ALLOW);
  }

  @Test
  void aDenyRuleStillDenies() {
    var decision = gate(AutonomyRule.DENY).evaluate(agentId, "device_chrome__browser_click", null);
    assertThat(decision.rule()).isEqualTo(AutonomyRule.DENY);
  }
}
