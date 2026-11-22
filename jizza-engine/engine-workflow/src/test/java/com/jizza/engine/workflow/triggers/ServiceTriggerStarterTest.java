package com.jizza.engine.workflow.triggers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** A service trigger starts its owner's enabled workflows, one run per item, capped per burst. */
class ServiceTriggerStarterTest {

  private final UUID owner = UUID.randomUUID();
  private final UUID triggerId = UUID.randomUUID();

  @Test
  @SuppressWarnings("unchecked")
  void startsEnabledWorkflowsOfTheOwnerOnly() {
    WorkflowEntity on = workflow(owner, true);
    WorkflowEntity off = workflow(owner, false);
    WorkflowEntity stranger = workflow(UUID.randomUUID(), true);
    WorkflowRepository repo = mock(WorkflowRepository.class);
    when(repo.findByServiceTriggerIdAndDeletedAtIsNull(triggerId))
        .thenReturn(List.of(on, off, stranger));
    WorkflowEngine engine = mock(WorkflowEngine.class);
    ObjectProvider<WorkflowEngine> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(engine);

    List<JsonNode> items = IntStream.range(0, 25)
        .mapToObj(i -> (JsonNode) Json.obj().put("id", i)).toList();
    int started = new ServiceTriggerStarter(repo, provider)
        .start(triggerId, owner, items, Json.obj().put("source", "webhook"));

    assertThat(started).isEqualTo(ServiceTriggerStarter.MAX_RUNS_PER_FIRE);
    verify(engine, times(ServiceTriggerStarter.MAX_RUNS_PER_FIRE))
        .start(eq(on.getId()), anyInt(), any(), eq("trigger:" + triggerId));
    verify(engine, never()).start(eq(off.getId()), anyInt(), any(), any());
    verify(engine, never()).start(eq(stranger.getId()), anyInt(), any(), any());
  }

  @Test
  void anItemsFieldsBecomeTheRunInput() {
    var input = ServiceTriggerStarter.input(Json.read("{\"subject\":\"Maths\"}"),
        Json.obj().put("service", "School portal"));
    assertThat(input.path("subject").asText()).isEqualTo("Maths");
    assertThat(input.path("_trigger").path("service").asText()).isEqualTo("School portal");
    assertThat(ServiceTriggerStarter.input(Json.read("5"), null).path("item").asInt()).isEqualTo(5);
  }

  private static WorkflowEntity workflow(UUID userId, boolean enabled) {
    WorkflowEntity w = new WorkflowEntity();
    w.setId(UUID.randomUUID());
    w.setUserId(userId);
    w.setStatus(com.jizza.engine.api.enums.WorkflowStatus.APPROVED);
    w.setDisabled(!enabled);
    w.setVersion(1);
    return w;
  }
}
