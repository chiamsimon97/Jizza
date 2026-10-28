package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.LocatorFixes;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import com.jizza.engine.persistence.entity.WebLocatorFixEntity;
import com.jizza.engine.persistence.repository.WebLocatorFixRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a web step that lost its element into a fix the person can review. The candidates come
 * from the browser, which scored elements by the step's fingerprint; this keeps them, at most one
 * open fix per workflow step, and tells the person once. Applying saves a new workflow version
 * with the chosen element first and the old strategies after it as fallbacks, so a page that
 * changes back still works. Nothing here picks a candidate by itself or invents a selector.
 */
@Service
public class LocatorFixService implements LocatorFixes {

  static final String OPEN = "open";
  static final String APPLIED = "applied";
  static final String DISMISSED = "dismissed";

  private static final int MAX_CANDIDATES = 5;
  private static final int MAX_CANDIDATE_CHARS = 16 * 1024;
  private static final int MAX_TEXT = 200;

  private final WebLocatorFixRepository fixes;
  private final WorkflowRegistry workflows;
  private final ObjectProvider<ProcedureService> procedures;
  private final ObjectProvider<NotificationBus> notifications;
  private final FixRanker ranker;

  public LocatorFixService(WebLocatorFixRepository fixes, WorkflowRegistry workflows,
                           ObjectProvider<ProcedureService> procedures,
                           ObjectProvider<NotificationBus> notifications, FixRanker ranker) {
    this.fixes = fixes;
    this.workflows = workflows;
    this.procedures = procedures;
    this.notifications = notifications;
    this.ranker = ranker;
  }

  // ---- from the engine ---------------------------------------------------------------------

  /**
   * Runs in its own transaction: a fix that cannot be kept must never change how the run that
   * failed is recorded.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void propose(FailedStep failed) {
    if (failed == null || failed.workflowId() == null || failed.nodeId() == null
        || failed.error() == null
        || !"element_not_found".equals(failed.error().path("kind").asText())) {
      return;
    }
    ArrayNode candidates = clean(failed.error().path("candidates"));
    if (candidates.isEmpty()) return;
    WorkflowDto workflow = workflows.find(failed.workflowId())
        .filter(w -> w.userId().equals(failed.userId())).orElse(null);
    if (workflow == null) return;
    JsonNode node = nodeOf(workflow.graph(), failed.nodeId()).orElse(null);
    if (node == null) return;
    candidates = ranker.rank(failed.userId(), node.path("config").path("inputs").path("element"),
        candidates);

    Optional<WebLocatorFixEntity> open =
        fixes.findByWorkflowIdAndNodeIdAndStatus(failed.workflowId(), failed.nodeId(), OPEN);
    if (open.isPresent()) {
      // The same step failed again: newer candidates, same fix, no second notification.
      WebLocatorFixEntity fix = open.get();
      fix.setCandidates(candidates);
      fix.setRunId(failed.runId());
      fixes.save(fix);
      return;
    }
    WebLocatorFixEntity fix = new WebLocatorFixEntity();
    fix.setId(UUID.randomUUID());
    fix.setOwnerId(failed.userId());
    fix.setWorkflowId(failed.workflowId());
    fix.setNodeId(failed.nodeId());
    fix.setRunId(failed.runId());
    fix.setCandidates(candidates);
    fix.setStatus(OPEN);
    fix.setCreatedAt(Instant.now());
    fixes.saveAndFlush(fix);
    notify(fix, workflow, node);
  }

  private void notify(WebLocatorFixEntity fix, WorkflowDto workflow, JsonNode node) {
    NotificationBus bus = notifications.getIfAvailable();
    if (bus == null) return;
    String body = "Step " + stepNumber(workflow.graph(), fix.getNodeId()) + " of "
        + workflow.name() + " could not find " + elementName(node) + ". Review a fix";
    ObjectNode detail = Json.obj().put("source", "webauto").put("kind", "locator_fix")
        .put("fixId", fix.getId().toString()).put("nodeId", fix.getNodeId())
        .put("link", reviewLink(workflow.id(), fix.getNodeId()));
    bus.raise(NotificationKind.INFO, null, new NotificationContext(fix.getOwnerId(), body, detail,
        null, workflow.id(), fix.getRunId(), null));
  }

  // ---- person's side -----------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public List<Fix> list(Access access, String status) {
    List<WebLocatorFixEntity> found = status == null || status.isBlank()
        ? fixes.findByOwnerIdOrderByCreatedAtDesc(access.userId())
        : fixes.findByOwnerIdAndStatusOrderByCreatedAtDesc(access.userId(), status);
    List<Fix> out = new ArrayList<>();
    for (WebLocatorFixEntity fix : found) {
      WorkflowDto workflow = workflows.find(fix.getWorkflowId()).orElse(null);
      if (workflow == null) continue;
      String label = nodeOf(workflow.graph(), fix.getNodeId())
          .map(n -> n.path("label").asText(fix.getNodeId())).orElse(fix.getNodeId());
      out.add(new Fix(fix.getId(), workflow.id(), workflow.name(), fix.getNodeId(), label,
          fix.getCandidates(), fix.getStatus(), fix.getRunId(), fix.getCreatedAt()));
    }
    return out;
  }

  @Override
  @Transactional
  public Applied apply(Access access, UUID fixId, int candidateIndex) {
    WebLocatorFixEntity fix = owned(access, fixId);
    if (!OPEN.equals(fix.getStatus())) throw new ConflictException("This fix was already " + fix.getStatus());
    JsonNode candidates = fix.getCandidates();
    if (candidateIndex < 0 || candidateIndex >= candidates.size()) {
      throw new ValidationException("Choose one of the " + candidates.size() + " suggestions");
    }
    WorkflowDto workflow = workflows.find(fix.getWorkflowId())
        .filter(w -> w.userId().equals(access.userId()))
        .orElseThrow(() -> new NotFoundException("Fix", fixId));
    ObjectNode graph = workflow.graph().deepCopy();
    ObjectNode node = (ObjectNode) nodeOf(graph, fix.getNodeId())
        .filter(n -> NodeType.DEVICE_ACTION.name().equals(n.path("type").asText()))
        .filter(n -> n.path("config").path("inputs").isObject())
        .orElseThrow(() -> new ConflictException("That step is no longer in " + workflow.name()
            + ". Dismiss this fix."));
    ObjectNode inputs = (ObjectNode) node.get("config").get("inputs");
    inputs.set("element", merge(candidates.get(candidateIndex).path("element"),
        inputs.path("element")));

    WorkflowDto saved;
    if (workflow.status() == WorkflowStatus.DRAFT) {
      saved = workflows.saveNewVersion(workflow.id(), graph);
    } else {
      ProcedureService review = procedures.getIfAvailable();
      if (review == null) {
        throw new ConflictException("'" + workflow.name() + "' is reviewed, and review is not available here");
      }
      saved = review.revise(workflow.id(), graph);
    }
    fix.setStatus(APPLIED);
    fix.setDecidedAt(Instant.now());
    fixes.save(fix);
    return new Applied(saved.id(), saved.version());
  }

  @Override
  @Transactional
  public void dismiss(Access access, UUID fixId) {
    WebLocatorFixEntity fix = owned(access, fixId);
    if (DISMISSED.equals(fix.getStatus())) return;
    if (APPLIED.equals(fix.getStatus())) throw new ConflictException("This fix was already applied");
    fix.setStatus(DISMISSED);
    fix.setDecidedAt(Instant.now());
    fixes.save(fix);
  }

  private WebLocatorFixEntity owned(Access access, UUID id) {
    return fixes.lockById(id).filter(f -> f.getOwnerId().equals(access.userId()))
        .orElseThrow(() -> new NotFoundException("Fix", id));
  }

  // ---- helpers -----------------------------------------------------------------------------

  /**
   * The chosen element first, then every strategy the step had that the candidate does not, so the
   * step still finds the element if the page changes back. Frames and shadow hosts carry over when
   * the candidate does not name its own.
   */
  static ObjectNode merge(JsonNode chosen, JsonNode old) {
    ObjectNode element = chosen.deepCopy();
    element.remove("preview");
    ArrayNode strategies = element.path("strategies").isArray()
        ? (ArrayNode) element.get("strategies") : element.putArray("strategies");
    for (JsonNode strategy : old.path("strategies")) {
      boolean known = false;
      for (JsonNode have : strategies) known |= have.equals(strategy);
      if (!known) strategies.add(strategy.deepCopy());
    }
    for (String chain : List.of("frames", "shadow")) {
      if (!element.has(chain) && old.has(chain)) element.set(chain, old.get(chain).deepCopy());
    }
    if (!element.hasNonNull("label") && old.hasNonNull("label")) element.set("label", old.get("label"));
    return element;
  }

  /** Up to five candidates, each with a bundle that has strategies; nothing else is kept. */
  static ArrayNode clean(JsonNode raw) {
    ArrayNode out = Json.mapper().createArrayNode();
    if (raw == null || !raw.isArray()) return out;
    for (JsonNode c : raw) {
      if (out.size() >= MAX_CANDIDATES) break;
      JsonNode element = c.path("element");
      if (!element.isObject() || !element.path("strategies").isArray()
          || element.path("strategies").isEmpty()
          || Json.write(element).length() > MAX_CANDIDATE_CHARS) {
        continue;
      }
      ObjectNode kept = Json.obj();
      kept.set("element", element.deepCopy());
      if (c.path("score").isNumber()) {
        kept.put("score", Math.max(0, Math.min(1, c.get("score").asDouble())));
      }
      if (c.path("text").isTextual()) kept.put("text", clip(c.get("text").asText()));
      out.add(kept);
    }
    return out;
  }

  static Optional<JsonNode> nodeOf(JsonNode graph, String nodeId) {
    if (graph == null) return Optional.empty();
    for (JsonNode node : graph.path("nodes")) {
      if (nodeId.equals(node.path("id").asText())) return Optional.of(node);
    }
    return Optional.empty();
  }

  /** Counted as the person reads the workflow: its steps, without start and end. */
  static int stepNumber(JsonNode graph, String nodeId) {
    int n = 0;
    for (JsonNode node : graph.path("nodes")) {
      String type = node.path("type").asText();
      if ("TRIGGER".equals(type) || "END".equals(type)) continue;
      n++;
      if (nodeId.equals(node.path("id").asText())) return n;
    }
    return n + 1;
  }

  private static String elementName(JsonNode node) {
    String label = node.path("config").path("inputs").path("element").path("label").asText("");
    if (label.isBlank()) label = node.path("label").asText("");
    return label.isBlank() ? "its element" : clip(label);
  }

  static String reviewLink(UUID workflowId, String nodeId) {
    return "/workflows?open=" + workflowId + "&node="
        + java.net.URLEncoder.encode(nodeId, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String clip(String s) {
    return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
  }
}
