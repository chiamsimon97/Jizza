package com.jizza.evals;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.agents.llm.AnthropicLlmClient;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.LlmClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The scenarios against the real model. Costs money, so it runs only when asked:
 *
 * <pre>
 * ANTHROPIC_API_KEY=... mvn -pl jizza-evals -am test -Djizza.evals=true -Dtest=LiveEvalSuiteTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false [-Djizza.evals.trials=3] [-Djizza.evals.filter=kai-]
 * </pre>
 *
 * Models and prices come from the same variables the application reads. The report is written to
 * {@code jizza-evals/target/evals/}.
 */
@EnabledIfSystemProperty(named = "jizza.evals", matches = "true")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LiveEvalSuiteTest {

  @Test
  void theBuiltInAgentsBehaveAsTheirPromptsPromise() throws Exception {
    AgentProperties props = new AgentProperties();
    props.getAnthropic().setApiKey(System.getenv("ANTHROPIC_API_KEY"));
    String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
    if (baseUrl != null && !baseUrl.isBlank()) props.getAnthropic().setBaseUrl(baseUrl);
    props.getModels().put(ModelTier.ROUTING, env("JIZZA_MODEL_ROUTING", "claude-haiku-4-5-20251001"));
    props.getModels().put(ModelTier.STANDARD, env("JIZZA_MODEL_STANDARD", "claude-sonnet-5"));
    props.getModels().put(ModelTier.DEEP, env("JIZZA_MODEL_DEEP", "claude-opus-5"));
    // List prices per million tokens (input, output), so the report shows what the run cost.
    price(props, "claude-haiku-4-5-20251001", 1.0, 5.0);
    price(props, "claude-haiku-4-5", 1.0, 5.0);
    price(props, "claude-sonnet-5", 2.0, 10.0);
    price(props, "claude-sonnet-5-5", 2.0, 10.0);
    price(props, "claude-opus-5", 5.0, 25.0);
    price(props, "claude-opus-5-5", 4.0, 20.0);

    int trials = Integer.getInteger("jizza.evals.trials", 3);
    double minPassRate = Double.parseDouble(System.getProperty("jizza.evals.min-pass-rate", "0.8"));
    LlmClient llm = new AnthropicLlmClient(props);

    var report = new EvalRunner(props)
        .run(EvalCases.matching(System.getProperty("jizza.evals.filter")), trials, c -> llm);
    EvalRunner.write(report, Path.of("target", "evals"), minPassRate);

    assertThat(report.failing(minPassRate)).as("scenarios below the bar; see target/evals/report.md")
        .extracting(EvalRunner.CaseReport::id).isEmpty();
  }

  private static void price(AgentProperties props, String model, double in, double out) {
    AgentProperties.ModelPrice price = new AgentProperties.ModelPrice();
    price.setInputPerMillion(in);
    price.setOutputPerMillion(out);
    props.getPricing().put(model, price);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
