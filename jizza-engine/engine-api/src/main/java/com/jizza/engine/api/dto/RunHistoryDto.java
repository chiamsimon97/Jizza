package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shapes for the workflow run history view. */
public final class RunHistoryDto {

  private RunHistoryDto() {}

  /**
   * @param from       inclusive lower bound on when the run started; null for no bound
   * @param to         exclusive upper bound; null for now
   * @param workflowId only this workflow's runs
   * @param statuses   only these statuses; empty for all
   * @param triggers   only these trigger kinds ({@link #triggerKind}); empty for all
   * @param search     text matched against the workflow name, the error and the run id
   * @param limit      page size
   * @param offset     rows to skip
   * @param zone       the viewer's time zone, for daily timeline slices
   */
  public record Query(Instant from, Instant to, UUID workflowId, Set<RunStatus> statuses,
                      Set<String> triggers, String search, int limit, int offset,
                      java.time.ZoneId zone) {

    public Query {
      statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
      triggers = triggers == null ? Set.of() : Set.copyOf(triggers);
    }
  }

  /**
   * One run as a row.
   *
   * @param endedAt    when it reached a final status; null while it is still going
   * @param durationMs start to end, or start to now while it is still going
   * @param failedNode the step it stopped on, when it failed
   */
  public record Summary(UUID id, UUID workflowId, String workflowName, int version,
                        String triggerSource, String triggerKind, RunStatus status,
                        Instant startedAt, Instant endedAt, long durationMs, int stepCount,
                        int failedSteps, String error, String failedNode, UUID parentRunId) {}

  /** How the whole match went, not just the page shown. */
  public record Stats(long total, Map<RunStatus, Long> byStatus, long avgDurationMs,
                      long p95DurationMs, double successRate) {}

  /** Runs that started in one slice of the timeline. */
  public record Bucket(Instant start, long done, long failed, long other) {}

  /** A workflow with runs in the match, for the workflow filter. */
  public record WorkflowCount(UUID id, String name, long runs, long failed) {}

  /**
   * @param bucketUnit {@code "hour"} or {@code "day"}, whichever fits the range
   * @param truncated  true when the range held more runs than are scanned for stats
   */
  public record Page(List<Summary> items, long total, Stats stats, String bucketUnit,
                     List<Bucket> buckets, List<WorkflowCount> workflows,
                     Map<String, Long> triggers, boolean truncated) {}

  /** A step of the workflow as it was drawn when the run started. */
  public record TraceNode(String id, NodeType type, String label) {}

  public record TraceEdge(String from, String to, String branch) {}

  /**
   * What one step did.
   *
   * @param input  what the step was handed: its settings with {@code {{ }}} filled in, and the
   *               data that reached it; null for runs recorded before inputs were kept
   * @param failure  for a failed device step, what the device said went wrong: {@code kind},
   *                 {@code detail}, {@code url}, screenshot {@code files} and how many
   *                 {@code candidates} it suggested; null otherwise
   */
  public record TraceStep(UUID id, String nodeId, String label, NodeType type,
                          RunStepStatus status, JsonNode input, JsonNode output, String error,
                          int attempts, Instant startedAt, Instant finishedAt, Long durationMs,
                          JsonNode failure) {

    public TraceStep(UUID id, String nodeId, String label, NodeType type, RunStepStatus status,
                     JsonNode input, JsonNode output, String error, int attempts,
                     Instant startedAt, Instant finishedAt, Long durationMs) {
      this(id, nodeId, label, type, status, input, output, error, attempts, startedAt, finishedAt,
          durationMs, null);
    }
  }

  /**
   * One run, step by step.
   *
   * @param waitingOn  the step the run is paused or parked on, if any
   * @param children   runs this one started through sub-workflow or for-each steps
   */
  public record Trace(Summary run, JsonNode input, JsonNode result, List<TraceNode> nodes,
                      List<TraceEdge> edges, List<TraceStep> steps, String waitingOn,
                      List<Summary> children) {}

  /**
   * The kind of thing that started a run, from its trigger source: {@code manual},
   * {@code webhook}, {@code schedule}, {@code agent}, {@code event}, {@code device} or {@code subworkflow}.
   */
  public static String triggerKind(String triggerSource) {
    if (triggerSource == null || triggerSource.isBlank()) return "other";
    if (triggerSource.contains("subworkflow:")) return "subworkflow";
    String head = triggerSource.split(":", 2)[0];
    return switch (head) {
      case "manual" -> "manual";
      case "webhook" -> "webhook";
      case "scheduled_task", "schedule" -> "schedule";
      case "skill" -> "agent";
      case "event" -> "event";
      case "device" -> "device";
      case "form" -> "form";
      default -> "other";
    };
  }
}
