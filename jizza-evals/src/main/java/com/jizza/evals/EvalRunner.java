package com.jizza.evals;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.llm.LlmClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs each scenario several times and reports how often it passed. Models vary from run to run,
 * so one pass proves little; a pass rate over trials is the signal, and a critical (safety)
 * scenario must pass every one.
 */
public final class EvalRunner {

  /** One trial of one scenario. */
  public record Trial(int number, boolean passed, List<String> failures, List<String> requested,
                      Map<String, String> decisions, int rounds, int inputTokens, int outputTokens,
                      int cacheReadTokens, BigDecimal cost, String answer) {}

  public record CaseReport(String id, String agent, boolean critical, List<Trial> trials) {
    public long passes() { return trials.stream().filter(Trial::passed).count(); }
    public double passRate() { return trials.isEmpty() ? 0 : (double) passes() / trials.size(); }
    /** A critical scenario counts as passed only if every trial did. */
    public boolean acceptable(double minPassRate) {
      return critical ? passes() == trials.size() : passRate() >= minPassRate;
    }
  }

  public record Report(Instant ranAt, String model, int trialsPerCase, List<CaseReport> cases) {
    public double passRate() {
      long trials = cases.stream().mapToLong(c -> c.trials().size()).sum();
      long passes = cases.stream().mapToLong(CaseReport::passes).sum();
      return trials == 0 ? 0 : (double) passes / trials;
    }

    public BigDecimal cost() {
      return cases.stream().flatMap(c -> c.trials().stream()).map(Trial::cost)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<CaseReport> failing(double minPassRate) {
      return cases.stream().filter(c -> !c.acceptable(minPassRate)).toList();
    }
  }

  private final AgentProperties props;
  private final EvalWorld.Runtime runtime;

  public EvalRunner(AgentProperties props) {
    this(props, EvalWorld.Runtime.BASE_AGENT);
  }

  /** Runs the scenarios on another agent runtime, to compare it with Jizza's own. */
  public EvalRunner(AgentProperties props, EvalWorld.Runtime runtime) {
    this.props = props;
    this.runtime = runtime;
  }

  /** @param llmFor the model to use for a scenario's trial, so a test can script one per case */
  public Report run(List<EvalCase> cases, int trials, Function<EvalCase, LlmClient> llmFor) {
    List<CaseReport> reports = new ArrayList<>();
    for (EvalCase scenario : cases) {
      List<Trial> results = new ArrayList<>();
      for (int n = 1; n <= trials; n++) {
        results.add(trial(scenario, n, llmFor.apply(scenario)));
      }
      reports.add(new CaseReport(scenario.id(), scenario.agent(), scenario.critical(), results));
    }
    String model = props.getModels().get(com.jizza.engine.api.enums.ModelTier.STANDARD);
    return new Report(Instant.now(), model, trials, reports);
  }

  private Trial trial(EvalCase scenario, int n, LlmClient llm) {
    try {
      EvalWorld.Outcome outcome = new EvalWorld(scenario, llm, props, runtime).run();
      List<String> failures = Grader.grade(scenario, outcome);
      Map<String, String> decisions = new java.util.LinkedHashMap<>();
      outcome.decisions().forEach((tool, rule) -> decisions.put(tool, rule.name()));
      return new Trial(n, failures.isEmpty(), failures, outcome.requested(), decisions,
          outcome.rounds(), outcome.inputTokens(), outcome.outputTokens(),
          outcome.cacheReadTokens(), outcome.cost(), outcome.result().text());
    } catch (RuntimeException e) {
      return new Trial(n, false, List.of("the run threw " + e.getClass().getSimpleName() + ": "
          + e.getMessage()), List.of(), Map.of(), 0, 0, 0, 0, BigDecimal.ZERO, null);
    }
  }

  /** Writes {@code report.json} and a readable {@code report.md} into the directory. */
  public static void write(Report report, Path directory, double minPassRate) throws IOException {
    Files.createDirectories(directory);
    EvalCases.MAPPER.writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve("report.json").toFile(), report);

    StringBuilder md = new StringBuilder();
    md.append("# Jizza eval report\n\n")
        .append("- Ran: ").append(report.ranAt()).append('\n')
        .append("- Model (standard tier): ").append(report.model()).append('\n')
        .append("- Trials per scenario: ").append(report.trialsPerCase()).append('\n')
        .append("- Overall pass rate: ").append(percent(report.passRate())).append('\n')
        .append("- Spend: ").append(report.cost().setScale(4, RoundingMode.HALF_UP))
        .append(" (USD at the configured prices)\n\n")
        .append("| Scenario | Agent | Critical | Passed | Verdict |\n|---|---|---|---|---|\n");
    for (CaseReport c : report.cases()) {
      md.append("| ").append(c.id()).append(" | ").append(c.agent()).append(" | ")
          .append(c.critical() ? "yes" : "").append(" | ").append(c.passes()).append('/')
          .append(c.trials().size()).append(" | ")
          .append(c.acceptable(minPassRate) ? "pass" : "**FAIL**").append(" |\n");
    }
    for (CaseReport c : report.cases()) {
      if (c.passes() == c.trials().size()) continue;
      md.append("\n## ").append(c.id()).append("\n\n");
      for (Trial t : c.trials()) {
        if (t.passed()) continue;
        md.append("- Trial ").append(t.number()).append(": ").append(String.join("; ", t.failures()))
            .append(" (called ").append(t.requested()).append(")\n");
      }
    }
    Files.writeString(directory.resolve("report.md"), md.toString());
  }

  private static String percent(double rate) {
    return Math.round(rate * 1000) / 10.0 + "%";
  }
}
