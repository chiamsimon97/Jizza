package com.jizza.engine.connectors.tools.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.connector.ToolExecutor;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.ServiceTriggerEntity;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import com.jizza.engine.persistence.repository.ServiceRunRepository;
import com.jizza.engine.persistence.repository.ServiceTriggerRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Triggers find new items by id, starting from a quiet baseline. */
class DefaultServiceRunnerTest {

  private final UUID serviceId = UUID.randomUUID();
  private ServiceTriggerEntity trigger;
  private ToolExecutor executor;
  private ServiceRunRepository runs;
  private DefaultServiceRunner runner;

  @BeforeEach
  void setUp() {
    GeneratedToolEntity api = new GeneratedToolEntity();
    api.setId(UUID.randomUUID());
    api.setConnectionId(serviceId);
    api.setName("list_bills");
    api.setHttpMethod("GET");
    api.setPathTemplate("/bills");
    api.setInputSchema(Json.obj());
    api.setEnabled(true);

    trigger = new ServiceTriggerEntity();
    trigger.setId(UUID.randomUUID());
    trigger.setConnectionId(serviceId);
    trigger.setLabel("Bill created");
    trigger.setApiId(api.getId());
    trigger.setIdField("bill_id");
    trigger.setSeenIds(Json.mapper().createArrayNode());

    GeneratedToolRepository apis = mock(GeneratedToolRepository.class);
    when(apis.findById(api.getId())).thenReturn(Optional.of(api));
    ServiceTriggerRepository triggers = mock(ServiceTriggerRepository.class);
    when(triggers.findById(trigger.getId())).thenReturn(Optional.of(trigger));
    executor = mock(ToolExecutor.class);
    runs = mock(ServiceRunRepository.class);
    runner = new DefaultServiceRunner(mock(ServiceActionRepository.class), triggers, apis, runs,
        executor);
  }

  private void serviceReturns(String body) {
    when(executor.execute(any(), any(), any()))
        .thenReturn(ToolResult.ok(Json.obj().put("status", 200).set("body", Json.read(body))));
  }

  @Test
  void firstPollIsABaselineAndLaterPollsReportOnlyNewItems() {
    serviceReturns("{\"bills\":[{\"bill_id\":1},{\"bill_id\":2}]}");
    var baseline = runner.pollTrigger(serviceId, trigger.getId());
    assertThat(baseline.totalItems()).isEqualTo(2);
    assertThat(baseline.newItems()).isZero();

    serviceReturns("{\"bills\":[{\"bill_id\":3},{\"bill_id\":2}]}");
    var next = runner.pollTrigger(serviceId, trigger.getId());
    assertThat(next.newItems()).isEqualTo(1);
    assertThat(next.items().get(0).path("bill_id").asInt()).isEqualTo(3);

    var quiet = runner.pollTrigger(serviceId, trigger.getId());
    assertThat(quiet.newItems()).isZero();
    // The baseline and the poll that found something are history; the quiet poll is not.
    verify(runs, times(2)).save(any());
  }

  @Test
  void aTestShowsWhatIsThereWithoutMarkingItSeen() {
    serviceReturns("[{\"bill_id\":1}]");
    var sample = runner.testTrigger(serviceId, trigger.getId());
    assertThat(sample.ok()).isTrue();
    assertThat(sample.newItems()).isEqualTo(1);
    assertThat(trigger.getSeenIds()).isEmpty();
    assertThat(trigger.getLastPolledAt()).isNull();
  }

  @Test
  void aServiceErrorIsReportedNotThrown() {
    when(executor.execute(any(), any(), any())).thenReturn(ToolResult.error("The service replied 401"));
    var sample = runner.pollTrigger(serviceId, trigger.getId());
    assertThat(sample.ok()).isFalse();
    assertThat(sample.error()).contains("401");
  }

  @Test
  void findsItemsByPathOrTheOnlyList() {
    assertThat(DefaultServiceRunner.items(Json.read("{\"data\":{\"rows\":[1,2]}}"), "data.rows"))
        .hasSize(2);
    assertThat(DefaultServiceRunner.items(Json.read("{\"page\":1,\"items\":[1]}"), null)).hasSize(1);
    assertThatThrownBy(() -> DefaultServiceRunner.items(Json.read("{\"a\":[1],\"b\":[2]}"), ""))
        .isInstanceOf(ValidationException.class);
    assertThat(DefaultServiceRunner.idOf(Json.read("{\"meta\":{\"id\":7}}"), "meta.id"))
        .isEqualTo("7");
  }

  @Test
  void aTriggerFromAnotherServiceIsNotFound() {
    assertThatThrownBy(() -> runner.pollTrigger(UUID.randomUUID(), trigger.getId()))
        .isInstanceOf(NotFoundException.class);
  }
}
