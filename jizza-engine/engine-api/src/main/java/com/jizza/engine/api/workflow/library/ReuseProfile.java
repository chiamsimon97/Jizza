package com.jizza.engine.api.workflow.library;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What Jizza keeps about a workflow so it can recognise the next request it fits and reuse it
 * instead of building a new one: when to use it, what it needs, and the guardrails the user set
 * on reusing it.
 *
 * <p>Stored on the workflow ({@code workflows.reuse}). A workflow without one is not in the
 * library: Jizza never reuses something the user did not agree to keep for reuse.
 *
 * @param summary     when this workflow is the right one, in the user's terms
 * @param examples    requests it handles, as the user might phrase them
 * @param inputs      what a run needs to be told; passed as {@code {{ input.<name> }}}
 * @param guardrails  how far Jizza may go when reusing it
 * @param learnedFrom the conversation it was saved from, if any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReuseProfile(String summary, List<String> examples, List<Input> inputs,
                           Guardrails guardrails, LearnedFrom learnedFrom) {

  public static final int MAX_EXAMPLES = 8;
  public static final int MAX_INPUTS = 12;
  public static final int MAX_RULES = 8;

  public ReuseProfile {
    summary = summary == null ? null : summary.strip();
    examples = clean(examples, MAX_EXAMPLES);
    inputs = inputs == null ? List.of()
        : inputs.stream().filter(i -> i != null && i.name() != null && !i.name().isBlank())
            .limit(MAX_INPUTS).toList();
    guardrails = guardrails == null ? Guardrails.DEFAULT : guardrails;
  }

  /** One thing a run must be told, such as the city or the recipient. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Input(String name, String description, boolean required) {}

  /**
   * @param confirmBeforeRun      Jizza asks the user before every run it starts from a chat
   * @param allowScheduled        a scheduled task whose job this is may run it on its own
   * @param maxRunsPerDay         at most this many reused runs a day; null means no limit
   * @param rules                 the user's own conditions, shown to Jizza with the workflow
   * @param unattendedFingerprint the graph the user allowed scheduled runs for. Set by the server
   *                              when the user turns {@code allowScheduled} on; any later change to
   *                              the steps makes it stale, and the permission with it
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Guardrails(boolean confirmBeforeRun, boolean allowScheduled, Integer maxRunsPerDay,
                           List<String> rules, String unattendedFingerprint) {

    /** Ask every time, never on a schedule: what a workflow gets when nobody said otherwise. */
    public static final Guardrails DEFAULT = new Guardrails(true, false, null, List.of(), null);

    public Guardrails {
      rules = clean(rules, MAX_RULES);
      if (maxRunsPerDay != null && maxRunsPerDay < 1) maxRunsPerDay = null;
    }

    /** Whether an unattended run of the graph with this fingerprint is one the user allowed. */
    public boolean allowsUnattended(String fingerprint) {
      return allowScheduled && unattendedFingerprint != null
          && unattendedFingerprint.equals(fingerprint);
    }

    public Guardrails withUnattendedFingerprint(String fingerprint) {
      return new Guardrails(confirmBeforeRun, allowScheduled, maxRunsPerDay, rules,
          allowScheduled ? fingerprint : null);
    }
  }

  public record LearnedFrom(UUID conversationId, Instant savedAt) {}

  public static ReuseProfile from(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) return null;
    return Json.mapper().convertValue(node, ReuseProfile.class);
  }

  public JsonNode toJson() {
    return Json.toNode(this);
  }

  public ReuseProfile withGuardrails(Guardrails next) {
    return new ReuseProfile(summary, examples, inputs, next, learnedFrom);
  }

  public ReuseProfile withLearnedFrom(LearnedFrom from) {
    return new ReuseProfile(summary, examples, inputs, guardrails, from);
  }

  private static List<String> clean(List<String> values, int max) {
    if (values == null) return List.of();
    return values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip)
        .distinct().limit(max).toList();
  }
}
