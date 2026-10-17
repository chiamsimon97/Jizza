package com.jizza.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.AutonomyRule;
import java.util.List;
import java.util.Map;

/**
 * One scenario: an agent, what it is asked, the tools it holds (fixtures, so nothing real is ever
 * touched), and what a good turn looks like.
 *
 * @param critical a safety scenario: it must pass every trial, not merely most of them
 */
public record EvalCase(String id, String agent, String description, boolean critical,
                       List<Turn> history, String instruction, boolean autonomous,
                       List<FixtureTool> tools, Expect expect) {

  public EvalCase {
    history = history == null ? List.of() : history;
    tools = tools == null ? List.of() : tools;
    expect = expect == null ? new Expect(null, null, null, null, null, null, null, null) : expect;
  }

  /** An earlier exchange in the conversation; role is {@code user} or {@code assistant}. */
  public record Turn(String role, String text) {}

  /**
   * A tool the agent holds for this scenario. {@code result} is what it returns when it is allowed
   * to run; {@code actionType} is how the guardrails judge it, as the user would have set it.
   */
  public record FixtureTool(String name, String description, JsonNode schema,
                            ActionType actionType, JsonNode result) {}

  /**
   * What the turn must show. Every field is optional.
   *
   * @param calls          tools that must have been asked for
   * @param callsAnyOf     at least one of these must have been asked for
   * @param notCalls       tools that must not have been asked for
   * @param callArgsMention for a tool, phrases that must each appear in the arguments of at least
   *                       one call to it (case-insensitive), e.g. who a task was delegated to
   * @param decisions      for each tool, what the guardrails must have decided if it was asked for
   * @param answerMentions phrases the final answer must contain (case-insensitive); an entry of
   *                       the form {@code a|b} is satisfied by either
   * @param answerAvoids   phrases the final answer must not contain (case-insensitive)
   * @param maxRounds      most model rounds the turn may take
   */
  public record Expect(List<String> calls, List<String> callsAnyOf, List<String> notCalls,
                       Map<String, List<String>> callArgsMention,
                       Map<String, AutonomyRule> decisions, List<String> answerMentions,
                       List<String> answerAvoids, Integer maxRounds) {

    public Expect {
      calls = calls == null ? List.of() : calls;
      callsAnyOf = callsAnyOf == null ? List.of() : callsAnyOf;
      notCalls = notCalls == null ? List.of() : notCalls;
      callArgsMention = callArgsMention == null ? Map.of() : callArgsMention;
      decisions = decisions == null ? Map.of() : decisions;
      answerMentions = answerMentions == null ? List.of() : answerMentions;
      answerAvoids = answerAvoids == null ? List.of() : answerAvoids;
    }
  }
}
