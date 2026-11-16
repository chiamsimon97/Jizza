package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreferredBrowserTest {

  @Test
  void theChosenBrowserIsKeptWithTheRun() {
    RunContext context = RunContext.start(Json.obj());
    assertThat(context.preferredBrowser()).isNull();
    UUID chrome = UUID.randomUUID();
    context.preferBrowser(chrome);
    assertThat(RunContext.of(context.toJson()).preferredBrowser()).isEqualTo(chrome);
    context.preferBrowser(null);
    assertThat(context.preferredBrowser()).isNull();
  }
}
