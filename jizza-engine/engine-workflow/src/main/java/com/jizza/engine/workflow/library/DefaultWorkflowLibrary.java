package com.jizza.engine.workflow.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library of saved workflows Jizza reuses instead of building new ones.
 *
 * <p>The guardrails live here, not in the callers, so a chat tool and the scheduler get the same
 * answer: only a workflow the user saved for reuse is ever reused; one awaiting review or rejected
 * never is; a chat run asks first unless the user said it need not (a draft always asks); a
 * scheduled run needs the user to have allowed it for the steps as they are now; and the daily
 * limit counts every reuse, whoever started it. A workflow the user built but never saved for
 * reuse can still be run from a chat when they ask for it, always asking first.
 */
@Service
public class DefaultWorkflowLibrary implements WorkflowLibrary {

  /** Every library run's trigger contains this, which is how the daily limit finds them. */
  static final String TRIGGER = "library:";

  private final WorkflowRepository workflows;
  private final WorkflowRunRepository runs;
  private final WorkflowEngine engine;
  private final AuditLog audit;
  private final Clock clock;

  @Autowired
  public DefaultWorkflowLibrary(WorkflowRepository workflows, WorkflowRunRepository runs,
                                WorkflowEngine engine, AuditLog audit) {
    this(workflows, runs, engine, audit, Clock.systemUTC());
  }

  DefaultWorkflowLibrary(WorkflowRepository workflows, WorkflowRunRepository runs,
                         WorkflowEngine engine, AuditLog audit, Clock clock) {
    this.workflows = workflows;
    this.runs = runs;
    this.engine = engine;
    this.audit = audit;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Match> find(UUID userId, String request, Use use, int limit) {
    double floor = use == Use.SCHEDULE ? WorkflowMatcher.STRONG : WorkflowMatcher.PLAUSIBLE;
    return scored(userId, request, use, floor, limit);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Match> search(UUID userId, String request, int limit) {
    return scored(userId, request, Use.CHAT, Double.MIN_VALUE, limit);
  }

  private List<Match> scored(UUID userId, String request, Use use, double floor, int limit) {
    List<Match> matches = new ArrayList<>();
    for (WorkflowEntity workflow : workflows.findByUserIdOrderByCreatedAtDesc(userId)) {
      ReuseProfile profile = profileFor(workflow, use);
      if (profile == null || !usable(workflow, profile, use)) continue;
      var score = WorkflowMatcher.score(request, workflow.getName(), workflow.getDescription(),
          profile, stepsText(workflow.getGraph()));
      if (score.value() >= floor) matches.add(match(workflow, profile, score));
    }
    matches.sort(Comparator.comparingDouble(Match::score).reversed());
    return matches.subList(0, Math.min(Math.max(limit, 0), matches.size()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Match> list(UUID userId) {
    return workflows.findByUserIdOrderByCreatedAtDesc(userId).stream()
        .filter(w -> !w.isDisabled())
        .map(w -> match(w, profileFor(w, Use.CHAT), new WorkflowMatcher.Score(0, List.of())))
        .toList();
  }

  /**
   * What the library knows about a workflow for this use. One the user built but never saved for
   * reuse is still theirs to run from a chat, on the strictest terms (ask every time, never on a
   * schedule); a scheduled task only ever gets workflows the user saved for reuse.
   */
  static ReuseProfile profileFor(WorkflowEntity workflow, Use use) {
    ReuseProfile saved = ReuseProfile.from(workflow.getReuse());
    if (saved != null || use == Use.SCHEDULE) return saved;
    return new ReuseProfile(workflow.getDescription(), List.of(), declaredInputs(workflow),
        ReuseProfile.Guardrails.DEFAULT, null);
  }

  /** The inputs a workflow's start step declares, from its saved input schema. */
  static List<ReuseProfile.Input> declaredInputs(WorkflowEntity workflow) {
    JsonNode schema = workflow.getInputSchema();
    if (schema == null || !schema.path("properties").isObject()) return List.of();
    java.util.Set<String> required = new java.util.HashSet<>();
    schema.path("required").forEach(r -> required.add(r.asText()));
    List<ReuseProfile.Input> inputs = new ArrayList<>();
    schema.path("properties").fields().forEachRemaining(e -> inputs.add(new ReuseProfile.Input(
        e.getKey(), e.getValue().path("description").asText(null), required.contains(e.getKey()))));
    return inputs;
  }

  /** What the steps say about themselves: their labels and the sites they open or call. */
  static String stepsText(JsonNode graph) {
    if (graph == null) return null;
    StringBuilder text = new StringBuilder();
    for (JsonNode node : graph.path("nodes")) {
      text.append(' ').append(node.path("label").asText(""));
      JsonNode config = node.path("config");
      for (String key : List.of("url", "startUrl", "title", "description")) {
        if (config.path(key).isTextual()) text.append(' ').append(config.path(key).asText());
      }
      if (text.length() > 4000) break;
    }
    return text.toString();
  }

  /**
   * A guardrail saying no is an answer, not a failure: it must not spoil the caller's transaction
   * (a scheduled task falls back to its agent and still records how it ran).
   */
  @Override
  @Transactional(noRollbackFor = ValidationException.class)
  public RunDto reuse(UUID userId, UUID workflowId, JsonNode input, Use use, String source,
                      boolean confirmed) {
    WorkflowEntity workflow = owned(userId, workflowId);
    ReuseProfile profile = profileFor(workflow, use);
    if (profile == null) {
      throw new ValidationException("'" + workflow.getName() + "' is not saved for reuse");
    }
    if (!usable(workflow, profile, use)) {
      throw new ValidationException(refusal(workflow, profile, use));
    }
    ReuseProfile.Guardrails rules = profile.guardrails();
    if (use == Use.CHAT && !confirmed
        && (rules.confirmBeforeRun() || workflow.getStatus() != WorkflowStatus.APPROVED)) {
      throw new ValidationException("'" + workflow.getName() + "' runs only once the user says "
          + "so in this conversation");
    }
    JsonNode given = input == null || input.isNull() ? Json.obj() : input;
    List<String> missing = profile.inputs().stream()
        .filter(ReuseProfile.Input::required)
        .map(ReuseProfile.Input::name)
        .filter(name -> !given.hasNonNull(name) || given.path(name).asText("").isBlank())
        .toList();
    if (!missing.isEmpty()) {
      throw new ValidationException("'" + workflow.getName() + "' needs " + String.join(", ", missing));
    }
    if (rules.maxRunsPerDay() != null) {
      Instant today = clock.instant().truncatedTo(ChronoUnit.DAYS);
      long used = runs.countStartedSince(workflowId, today, "%" + TRIGGER + "%");
      if (used >= rules.maxRunsPerDay()) {
        throw new ValidationException("'" + workflow.getName() + "' has already been reused "
            + used + " times today, its limit");
      }
    }

    // A chat run is the user's own request, so it may run a draft the way pressing Run does; a
    // scheduled one is unattended and the engine holds it to approval or the user's allowance.
    String trigger = (use == Use.CHAT ? "manual:" : "") + TRIGGER + source;
    RunDto run = engine.start(workflowId, workflow.getVersion(), given, trigger);
    audit.record(userId, use == Use.CHAT ? ActorType.USER : ActorType.SYSTEM, null,
        "workflow.reused", workflow.getName(), Json.obj()
            .put("workflowId", workflowId.toString()).put("runId", run.id().toString())
            .put("use", use.name().toLowerCase(java.util.Locale.ROOT))
            .put("source", source).put("confirmed", confirmed));
    return run;
  }

  @Override
  @Transactional
  public Match describe(UUID userId, UUID workflowId, ReuseProfile profile) {
    WorkflowEntity workflow = owned(userId, workflowId);
    if (profile == null) {
      workflow.setReuse(null);
      workflows.save(workflow);
      audit.record(userId, ActorType.USER, null, "workflow.library_removed", workflow.getName(),
          Json.obj().put("workflowId", workflowId.toString()));
      return match(workflow, null, new WorkflowMatcher.Score(0, List.of()));
    }
    ReuseProfile saved = profile
        .withGuardrails(profile.guardrails().withUnattendedFingerprint(workflow.getGraphFingerprint()))
        .withLearnedFrom(profile.learnedFrom() == null
            ? new ReuseProfile.LearnedFrom(null, clock.instant())
            : new ReuseProfile.LearnedFrom(profile.learnedFrom().conversationId(),
                Objects.requireNonNullElse(profile.learnedFrom().savedAt(), clock.instant())));
    workflow.setReuse(saved.toJson());
    workflows.save(workflow);
    audit.record(userId, ActorType.USER, null, "workflow.library_saved", workflow.getName(),
        Json.obj().put("workflowId", workflowId.toString())
            .put("confirmBeforeRun", saved.guardrails().confirmBeforeRun())
            .put("allowScheduled", saved.guardrails().allowScheduled()));
    return match(workflow, saved, new WorkflowMatcher.Score(0, List.of()));
  }

  /** Whether this use may reuse the workflow at all, before looking at the request. */
  static boolean usable(WorkflowEntity workflow, ReuseProfile profile, Use use) {
    if (workflow.isDisabled()) return false;
    WorkflowStatus status = workflow.getStatus();
    if (status == WorkflowStatus.PENDING_REVIEW || status == WorkflowStatus.REJECTED) return false;
    if (use == Use.CHAT) return true;
    return profile.guardrails().allowScheduled()
        && (status == WorkflowStatus.APPROVED
            || profile.guardrails().allowsUnattended(workflow.getGraphFingerprint()));
  }

  private static String refusal(WorkflowEntity workflow, ReuseProfile profile, Use use) {
    String name = "'" + workflow.getName() + "'";
    if (workflow.isDisabled()) return name + " is turned off";
    if (workflow.getStatus() == WorkflowStatus.PENDING_REVIEW) return name + " is waiting for review";
    if (workflow.getStatus() == WorkflowStatus.REJECTED) return name + " was rejected";
    if (!profile.guardrails().allowScheduled()) return name + " is not allowed to run on a schedule";
    return name + " has changed since scheduled runs were allowed, so the user has to allow them again";
  }

  private WorkflowEntity owned(UUID userId, UUID workflowId) {
    return workflows.findById(workflowId)
        .filter(w -> w.getUserId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }

  private static Match match(WorkflowEntity w, ReuseProfile profile, WorkflowMatcher.Score score) {
    return new Match(w.getId(), w.getName(), w.getDescription(), w.getStatus(), w.getVersion(),
        profile, score.value(), score.shared());
  }
}
