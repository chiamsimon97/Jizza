package com.jizza.engine.workflow.history;

import com.jizza.engine.api.dto.RunHistoryDto;
import com.jizza.engine.api.dto.RunHistoryDto.Bucket;
import com.jizza.engine.api.dto.RunHistoryDto.Page;
import com.jizza.engine.api.dto.RunHistoryDto.Query;
import com.jizza.engine.api.dto.RunHistoryDto.Stats;
import com.jizza.engine.api.dto.RunHistoryDto.Summary;
import com.jizza.engine.api.dto.RunHistoryDto.Trace;
import com.jizza.engine.api.dto.RunHistoryDto.TraceEdge;
import com.jizza.engine.api.dto.RunHistoryDto.TraceNode;
import com.jizza.engine.api.dto.RunHistoryDto.TraceStep;
import com.jizza.engine.api.dto.RunHistoryDto.WorkflowCount;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.util.TimeBuckets;
import com.jizza.engine.api.workflow.RunHistory;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRow;
import com.jizza.engine.persistence.repository.WorkflowRunStepRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import com.jizza.engine.workflow.engine.RunContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Run history read straight from the run and step tables. A query scans at most
 * {@link #MAX_SCAN} runs in its time range -- the newest ones -- and filters, counts and buckets
 * them in memory; the response says when the range held more, so the view can ask for a
 * narrower one rather than show numbers that look complete and are not.
 */
@Service
public class DefaultRunHistory implements RunHistory {

  static final int MAX_SCAN = 5_000;
  static final int MAX_PAGE = 200;
  private static final Set<RunStatus> FINAL =
      Set.of(RunStatus.DONE, RunStatus.FAILED, RunStatus.CANCELLED);

  private final WorkflowRunRepository runs;
  private final WorkflowRunStepRepository steps;
  private final WorkflowRepository workflows;
  private final WorkflowVersionRepository versions;
  private final Clock clock;

  @Autowired
  public DefaultRunHistory(WorkflowRunRepository runs, WorkflowRunStepRepository steps,
                           WorkflowRepository workflows, WorkflowVersionRepository versions) {
    this(runs, steps, workflows, versions, Clock.systemUTC());
  }

  DefaultRunHistory(WorkflowRunRepository runs, WorkflowRunStepRepository steps,
                    WorkflowRepository workflows, WorkflowVersionRepository versions,
                    Clock clock) {
    this.runs = runs;
    this.steps = steps;
    this.workflows = workflows;
    this.versions = versions;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Page search(UUID userId, Query query) {
    Instant now = clock.instant();
    Instant to = query.to() == null ? now.plusSeconds(60) : query.to();
    Instant from = query.from() == null ? Instant.EPOCH : query.from();

    List<WorkflowRunRow> scanned = runs.findRows(userId, from, to, PageRequest.of(0, MAX_SCAN + 1));
    boolean truncated = scanned.size() > MAX_SCAN;
    if (truncated) scanned = scanned.subList(0, MAX_SCAN);

    Map<UUID, String> names = namesOf(scanned.stream().map(WorkflowRunRow::workflowId).toList());

    // The workflow and trigger lists are counted before those two filters apply, so picking one
    // workflow still shows how many runs the others had.
    List<WorkflowRunRow> byText = scanned.stream()
        .filter(r -> query.statuses().isEmpty() || query.statuses().contains(r.status()))
        .filter(r -> matchesText(r, names, query.search()))
        .toList();
    List<WorkflowCount> workflowCounts = workflowCounts(
        byText.stream().filter(r -> matchesTrigger(r, query)).toList(), names);
    Map<String, Long> triggerCounts = byText.stream()
        .filter(r -> query.workflowId() == null || query.workflowId().equals(r.workflowId()))
        .collect(Collectors.groupingBy(r -> RunHistoryDto.triggerKind(r.triggerSource()),
            TreeMap::new, Collectors.counting()));

    List<WorkflowRunRow> matched = byText.stream()
        .filter(r -> query.workflowId() == null || query.workflowId().equals(r.workflowId()))
        .filter(r -> matchesTrigger(r, query))
        .toList();

    int limit = Math.max(1, Math.min(query.limit() <= 0 ? 50 : query.limit(), MAX_PAGE));
    int offset = Math.max(0, query.offset());
    List<WorkflowRunRow> page = matched.stream().skip(offset).limit(limit).toList();
    Map<UUID, int[]> stepCounts = stepCounts(page.stream().map(WorkflowRunRow::id).toList());

    List<Summary> items = page.stream()
        .map(r -> summary(r, names, stepCounts.getOrDefault(r.id(), new int[2]), now))
        .toList();

    Instant bucketFrom = query.from() != null ? query.from()
        : matched.isEmpty() ? now.minus(Duration.ofDays(1))
        : matched.get(matched.size() - 1).createdAt();
    Instant bucketTo = query.to() == null ? now : query.to();
    TimeBuckets slices = TimeBuckets.covering(bucketFrom, bucketTo, query.zone());

    return new Page(items, matched.size(), stats(matched, now), slices.unitName(),
        buckets(matched, slices), workflowCounts, triggerCounts, truncated);
  }

  @Override
  @Transactional(readOnly = true)
  public Trace trace(UUID userId, UUID runId) {
    WorkflowRunEntity run = runs.findById(runId)
        .filter(r -> Objects.equals(r.getUserId(), userId))
        .orElseThrow(() -> new NotFoundException("Workflow run", runId));
    Optional<WorkflowEntity> workflow = workflows.findById(run.getWorkflowId());
    WorkflowGraph graph = workflow.flatMap(w -> graphFor(w, run.getVersion())).orElse(null);
    Map<String, WorkflowNode> nodesById = graph == null ? Map.of()
        : graph.nodes().stream().collect(Collectors.toMap(WorkflowNode::id, n -> n,
            (a, b) -> a, LinkedHashMap::new));

    List<WorkflowRunStepEntity> stepRows = steps.findByRunIdOrderByStartedAtAsc(runId);
    int failed = (int) stepRows.stream().filter(s -> s.getStatus() == RunStepStatus.FAILED).count();
    Instant now = clock.instant();
    WorkflowRunRow row = new WorkflowRunRow(run.getId(), run.getWorkflowId(), run.getVersion(),
        run.getTriggerSource(), run.getStatus(), run.getCurrentNode(), run.getError(),
        run.getCreatedAt(), run.getUpdatedAt());
    Map<UUID, String> names = new HashMap<>();
    workflow.ifPresent(w -> names.put(w.getId(), w.getName()));
    Summary summary = summary(row, names, new int[] {stepRows.size(), failed}, now);

    List<TraceStep> traceSteps = stepRows.stream().map(s -> {
      WorkflowNode node = nodesById.get(s.getNodeId());
      Long duration = s.getStartedAt() == null || s.getFinishedAt() == null ? null
          : Duration.between(s.getStartedAt(), s.getFinishedAt()).toMillis();
      return new TraceStep(s.getId(), s.getNodeId(),
          node == null ? s.getNodeId() : labelOf(node), node == null ? null : node.type(),
          s.getStatus(), s.getInput(), s.getOutput(), s.getError(), s.getAttempts(),
          s.getStartedAt(), s.getFinishedAt(), duration);
    }).toList();

    RunContext context = RunContext.of(run.getContext());
    String waitingOn = null;
    if (run.getStatus() == RunStatus.PAUSED_WAITING_INSTRUCTION) {
      waitingOn = context.pausedNode();
    } else if (run.getStatus() == RunStatus.WAITING) {
      waitingOn = context.parked().keySet().stream().findFirst().orElse(null);
    }

    List<UUID> childIds = context.childRunIds();
    List<Summary> children = childIds.isEmpty() ? List.of() : childSummaries(childIds, userId, now);

    return new Trace(summary, context.input(),
        run.getStatus() == RunStatus.DONE ? context.result() : null,
        nodesById.values().stream().map(n -> new TraceNode(n.id(), n.type(), labelOf(n))).toList(),
        graph == null ? List.of()
            : graph.edges().stream().map(e -> new TraceEdge(e.from(), e.to(), e.branch())).toList(),
        traceSteps, waitingOn, children);
  }

  // ── pieces ───────────────────────────────────────────────────────────────

  private List<Summary> childSummaries(List<UUID> childIds, UUID userId, Instant now) {
    List<WorkflowRunRow> rows = runs.findRowsByIdIn(childIds);
    Map<UUID, String> names = namesOf(rows.stream().map(WorkflowRunRow::workflowId).toList());
    Map<UUID, int[]> counts = stepCounts(rows.stream().map(WorkflowRunRow::id).toList());
    return rows.stream()
        .sorted(Comparator.comparing(WorkflowRunRow::createdAt))
        .map(r -> summary(r, names, counts.getOrDefault(r.id(), new int[2]), now))
        .toList();
  }

  private Summary summary(WorkflowRunRow r, Map<UUID, String> names, int[] counts, Instant now) {
    Instant ended = FINAL.contains(r.status()) ? r.updatedAt() : null;
    long duration = r.createdAt() == null ? 0
        : Math.max(0, Duration.between(r.createdAt(), ended == null ? now : ended).toMillis());
    return new Summary(r.id(), r.workflowId(), names.getOrDefault(r.workflowId(), "Deleted workflow"),
        r.version(), r.triggerSource(), RunHistoryDto.triggerKind(r.triggerSource()), r.status(),
        r.createdAt(), ended, duration, counts[0], counts[1], r.error(),
        r.status() == RunStatus.FAILED ? r.currentNode() : null, parentOf(r.triggerSource()));
  }

  /** {@code [steps recorded, steps failed]} for each run. */
  private Map<UUID, int[]> stepCounts(Collection<UUID> runIds) {
    Map<UUID, int[]> counts = new HashMap<>();
    if (runIds.isEmpty()) return counts;
    for (Object[] row : steps.countByRunAndStatus(runIds)) {
      int[] c = counts.computeIfAbsent((UUID) row[0], k -> new int[2]);
      int n = ((Number) row[2]).intValue();
      c[0] += n;
      if (row[1] == RunStepStatus.FAILED) c[1] += n;
    }
    return counts;
  }

  private Map<UUID, String> namesOf(Collection<UUID> workflowIds) {
    return workflows.findAllById(Set.copyOf(workflowIds)).stream()
        .collect(Collectors.toMap(WorkflowEntity::getId, WorkflowEntity::getName, (a, b) -> a));
  }

  private static boolean matchesTrigger(WorkflowRunRow r, Query query) {
    return query.triggers().isEmpty()
        || query.triggers().contains(RunHistoryDto.triggerKind(r.triggerSource()));
  }

  private static boolean matchesText(WorkflowRunRow r, Map<UUID, String> names, String search) {
    if (search == null || search.isBlank()) return true;
    String needle = search.strip().toLowerCase(Locale.ROOT);
    return contains(names.get(r.workflowId()), needle) || contains(r.error(), needle)
        || contains(r.id().toString(), needle) || contains(r.currentNode(), needle);
  }

  private static boolean contains(String haystack, String needle) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
  }

  private static List<WorkflowCount> workflowCounts(List<WorkflowRunRow> rows,
                                                    Map<UUID, String> names) {
    return rows.stream().collect(Collectors.groupingBy(WorkflowRunRow::workflowId))
        .entrySet().stream()
        .map(e -> new WorkflowCount(e.getKey(), names.getOrDefault(e.getKey(), "Deleted workflow"),
            e.getValue().size(),
            e.getValue().stream().filter(r -> r.status() == RunStatus.FAILED).count()))
        .sorted(Comparator.comparingLong(WorkflowCount::runs).reversed()
            .thenComparing(WorkflowCount::name))
        .toList();
  }

  private Stats stats(List<WorkflowRunRow> rows, Instant now) {
    Map<RunStatus, Long> byStatus = new EnumMap<>(RunStatus.class);
    rows.forEach(r -> byStatus.merge(r.status(), 1L, Long::sum));
    List<Long> durations = rows.stream()
        .filter(r -> FINAL.contains(r.status()) && r.createdAt() != null && r.updatedAt() != null)
        .map(r -> Duration.between(r.createdAt(), r.updatedAt()).toMillis())
        .sorted().toList();
    long avg = durations.isEmpty() ? 0
        : (long) durations.stream().mapToLong(Long::longValue).average().orElse(0);
    long p95 = durations.isEmpty() ? 0
        : durations.get(Math.min(durations.size() - 1, (int) Math.ceil(durations.size() * 0.95) - 1));
    long done = byStatus.getOrDefault(RunStatus.DONE, 0L);
    long failed = byStatus.getOrDefault(RunStatus.FAILED, 0L);
    double successRate = done + failed == 0 ? 0 : (double) done / (done + failed);
    return new Stats(rows.size(), byStatus, avg, Math.max(0, p95), successRate);
  }

  private static List<Bucket> buckets(List<WorkflowRunRow> rows, TimeBuckets slices) {
    long[][] counts = new long[slices.starts().size()][3];
    for (WorkflowRunRow r : rows) {
      int at = slices.indexOf(r.createdAt());
      if (at < 0) continue;
      if (r.status() == RunStatus.DONE) counts[at][0]++;
      else if (r.status() == RunStatus.FAILED) counts[at][1]++;
      else counts[at][2]++;
    }
    List<Bucket> out = new ArrayList<>();
    for (int i = 0; i < counts.length; i++) {
      out.add(new Bucket(slices.starts().get(i), counts[i][0], counts[i][1], counts[i][2]));
    }
    return out;
  }

  private Optional<WorkflowGraph> graphFor(WorkflowEntity workflow, int version) {
    try {
      if (workflow.getVersion() == version) return Optional.of(WorkflowGraph.from(workflow.getGraph()));
      return versions.findByWorkflowIdAndVersion(workflow.getId(), version)
          .map(v -> WorkflowGraph.from(v.getGraph()));
    } catch (RuntimeException e) {
      // A graph that no longer parses still leaves the steps worth showing.
      return Optional.empty();
    }
  }

  private static String labelOf(WorkflowNode node) {
    return node.label() == null || node.label().isBlank() ? node.id() : node.label();
  }

  /** The run that started this one, from a {@code subworkflow:<run id>} trigger source. */
  static UUID parentOf(String triggerSource) {
    if (triggerSource == null) return null;
    int at = triggerSource.indexOf("subworkflow:");
    if (at < 0) return null;
    try {
      return UUID.fromString(triggerSource.substring(at + "subworkflow:".length()));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
