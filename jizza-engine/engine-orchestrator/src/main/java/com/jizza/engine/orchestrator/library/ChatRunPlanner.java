package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.chat.ChatShortcut;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.WebRecordingEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.WebRecordingRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * "Book a 3A ticket Chennai to Delhi on 20 Dec for me and my mother": a plain request to run one
 * of the person's saved workflows, answered with a start card instead of a full Jizza turn.
 *
 * <p>The saved workflows are matched by their words first (name, what they are used for, the
 * requests they handle, the sites their steps open), with no model call; only when one fits does a
 * single cheap-model call read the message against the best few ({@link RunRequestReader}). It
 * decides whether the person is asking to run one now and pulls out the inputs the message gives.
 * Those are checked with the run's own rules, and the person sees them on the card: what will be
 * filled, what is still needed, what will be asked during the run, and which browser runs it. Nothing
 * starts until they press Start. Anything that is not a run request goes to Jizza as usual.
 */
@Component
@Order(20)
public class ChatRunPlanner implements ChatShortcut {

  private static final Logger log = LoggerFactory.getLogger(ChatRunPlanner.class);

  /** The start card's action type. */
  public static final String START_RUN = "start_run";
  private static final int CANDIDATES = 3;
  private static final int MAX_MESSAGE = 1500;
  /** A question about something, which is for Jizza to answer, not a request to run it. */
  private static final Pattern QUESTION = Pattern.compile(
      "^(what|why|how|when|where|who|which|whose|did|does|do|is|are|was|were|has|have|should)\\b.*\\?\\s*$",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** Not final only so the end-to-end suite can turn it on for its own tests. */
  private boolean enabled;
  private final double minScore;
  private final ObjectProvider<WorkflowLibrary> library;
  private final WorkflowRepository workflows;
  private final ObjectProvider<WebRecordingRepository> recordings;
  private final ObjectProvider<Devices> devices;
  private final ObjectProvider<LlmClient> llm;
  private final ObjectProvider<CostAccountant> costs;

  public ChatRunPlanner(@Value("${jizza.chat.run-from-chat.enabled:true}") boolean enabled,
                        @Value("${jizza.chat.run-from-chat.min-score:0.3}") double minScore,
                        ObjectProvider<WorkflowLibrary> library, WorkflowRepository workflows,
                        ObjectProvider<WebRecordingRepository> recordings,
                        ObjectProvider<Devices> devices, ObjectProvider<LlmClient> llm,
                        ObjectProvider<CostAccountant> costs) {
    this.enabled = enabled;
    this.minScore = minScore;
    this.library = library;
    this.workflows = workflows;
    this.recordings = recordings;
    this.devices = devices;
    this.llm = llm;
    this.costs = costs;
  }

  @Override
  public boolean slashOnly() {
    return false;
  }

  @Override
  public Optional<Orchestrator.Turn> handle(UUID userId, UUID conversationId, String text) {
    return handle(userId, conversationId, text, null);
  }

  @Override
  public Optional<Orchestrator.Turn> handle(UUID userId, UUID conversationId, String text,
                                            JsonNode context) {
    if (!enabled || text == null) return Optional.empty();
    String message = text.strip();
    if (message.length() < 4 || message.length() > MAX_MESSAGE || QUESTION.matcher(message).matches()) {
      return Optional.empty();
    }
    WorkflowLibrary lib = library.getIfAvailable();
    LlmClient client = llm.getIfAvailable();
    if (lib == null || client == null) return Optional.empty();
    try {
      List<WorkflowLibrary.Match> found = lib.find(userId, message, WorkflowLibrary.Use.CHAT, CANDIDATES)
          .stream().filter(m -> m.score() >= minScore).toList();
      if (found.isEmpty()) return Optional.empty();
      List<WorkflowEntity> entities = new ArrayList<>();
      List<RunRequestReader.Candidate> candidates = new ArrayList<>();
      for (WorkflowLibrary.Match m : found) {
        WorkflowEntity w = workflows.findById(m.workflowId())
            .filter(e -> userId.equals(e.getUserId())).orElse(null);
        if (w == null) continue;
        entities.add(w);
        String summary = m.profile() != null && m.profile().summary() != null
            ? m.profile().summary() : m.description();
        candidates.add(new RunRequestReader.Candidate(m.name(), summary,
            RunInputs.of(w.getGraph(), m.profile())));
      }
      RunRequestReader.Reading reading = new RunRequestReader(client, costs.getIfAvailable())
          .read(userId, message, candidates, zone(context));
      if (reading == null) return Optional.empty();
      WorkflowEntity workflow = entities.get(reading.index());
      RunRequestReader.Candidate chosen = candidates.get(reading.index());
      return Optional.of(card(userId, workflow, chosen, RunInputs.check(chosen.inputs(), reading.values())));
    } catch (RuntimeException e) {
      // Whatever went wrong here, Jizza can still answer the message the usual way.
      log.warn("Reading a run request failed, so Jizza answers it instead: {}", e.toString());
      return Optional.empty();
    }
  }

  /** The person's time zone as the app sent it, for "tomorrow"; UTC when it sent none. */
  static ZoneId zone(JsonNode context) {
    String id = context == null ? "" : context.path("timeZone").asText("");
    if (id.isBlank()) return ZoneOffset.UTC;
    try {
      return ZoneId.of(id);
    } catch (DateTimeException e) {
      return ZoneOffset.UTC;
    }
  }

  private Orchestrator.Turn card(UUID userId, WorkflowEntity workflow, RunRequestReader.Candidate chosen,
                                 RunInputs.Checked checked) {
    ObjectNode card = Json.obj()
        .put("planId", UUID.randomUUID().toString())
        .put("workflowId", workflow.getId().toString())
        .put("name", workflow.getName());
    if (chosen.summary() != null && !chosen.summary().isBlank()) card.put("summary", chosen.summary());
    ArrayNode fields = card.putArray("fields");
    List<String> needed = new ArrayList<>();
    List<String> later = new ArrayList<>();
    for (RunInputs.Input i : chosen.inputs()) {
      ObjectNode f = fields.addObject().put("name", i.name()).put("label", i.label())
          .put("type", i.type()).put("required", i.required());
      if (i.ask() != null) f.put("ask", i.ask());
      if (i.description() != null && !i.description().isBlank()) f.put("description", i.description());
      for (String key : List.of("group", "format", "patternHint")) {
        if (i.declaration().path(key).isTextual()) f.put(key, i.declaration().get(key).asText());
      }
      if (i.declaration().path("options").isArray()) f.set("options", i.declaration().get("options").deepCopy());
      if (i.declaration().path("columns").isArray()) f.set("columns", i.declaration().get("columns").deepCopy());
      if (i.declaration().has("default") && !i.declaration().get("default").isNull()) {
        f.set("default", i.declaration().get("default").deepCopy());
      }
      JsonNode value = checked.values().get(i.name());
      if (value != null) f.set("value", value);
      String problem = checked.problems().get(i.name());
      if (problem != null) f.put("problem", problem);
      boolean empty = value == null && !i.declaration().hasNonNull("default");
      if (empty && i.atStep()) later.add(i.label());
      else if ((empty && i.required()) || problem != null) needed.add(i.label());
    }

    boolean browser = RunInputs.usesBrowser(workflow.getGraph());
    card.put("needsBrowser", browser);
    boolean anyOnline = false;
    if (browser) {
      List<DeviceDto> browsers = browsers(userId);
      ArrayNode list = card.putArray("browsers");
      for (DeviceDto d : browsers) {
        list.addObject().put("id", d.id().toString()).put("name", d.name())
            .put("online", d.online()).put("state", d.state());
        anyOnline |= d.online();
      }
      UUID chosenBrowser = defaultBrowser(browsers, recordedIn(workflow.getId()));
      if (chosenBrowser != null) card.put("browserId", chosenBrowser.toString());
    }

    StringBuilder said = new StringBuilder();
    if (needed.isEmpty()) {
      said.append("Ready to run **").append(workflow.getName()).append("**. Check the details below and press Start.");
    } else {
      said.append("**").append(workflow.getName()).append("** needs ")
          .append(needed.size() == 1 ? "one more detail" : needed.size() + " more details")
          .append(" before it starts: ").append(String.join(", ", needed))
          .append(". Fill ").append(needed.size() == 1 ? "it" : "them").append(" in below and press Start.");
    }
    if (!later.isEmpty()) {
      said.append(" I'll ask you for ").append(join(later))
          .append(" while it runs, when the page shows the choices.");
    }
    String finalStep = finalStep(workflow.getGraph());
    if (finalStep != null) {
      card.put("cannotBeUndone", finalStep);
      said.append(" It includes \"").append(finalStep)
          .append("\", which can't be undone, so check the details before you start.");
    }
    if (browser && !anyOnline) {
      said.append(" None of your browsers with the Jizza extension is connected right now; open one before you start.");
    }
    return new Orchestrator.Turn(said.toString(), List.of(), List.of(new UiAction(START_RUN, card)), 0, 0);
  }

  /** The first step marked as one that cannot be undone (pay, place order), by its label. */
  static String finalStep(JsonNode graph) {
    if (graph == null) return null;
    for (JsonNode node : graph.path("nodes")) {
      JsonNode config = node.path("config");
      if (config.path("irreversible").asBoolean(false) && !config.path("disabled").asBoolean(false)) {
        String label = node.path("label").asText("").strip();
        return label.isEmpty() ? "a final step" : label;
      }
    }
    return null;
  }

  /** The person's own browsers with the Jizza extension, online and idle ones first. */
  private List<DeviceDto> browsers(UUID userId) {
    Devices registry = devices.getIfAvailable();
    if (registry == null) return List.of();
    return registry.owned(userId).stream()
        .filter(d -> d.declares("browser.open"))
        .sorted(Comparator.comparingInt(ChatRunPlanner::readiness))
        .toList();
  }

  private static int readiness(DeviceDto d) {
    if (d.online() && "idle".equals(d.state())) return 0;
    return d.online() ? 1 : 2;
  }

  /** The browser a recorded workflow was recorded in, if it was. */
  private UUID recordedIn(UUID workflowId) {
    WebRecordingRepository repo = recordings.getIfAvailable();
    if (repo == null) return null;
    return repo.findByWorkflowIdOrderByUpdatedAtDesc(workflowId).stream()
        .map(WebRecordingEntity::getDeviceId).filter(Objects::nonNull).findFirst().orElse(null);
  }

  /**
   * Which browser the card starts with: the one it was recorded in while that one is online, else
   * the readiest online one, else the one it was recorded in, else the first.
   */
  static UUID defaultBrowser(List<DeviceDto> browsers, UUID recorded) {
    if (browsers.isEmpty()) return null;
    DeviceDto recordedOne = recorded == null ? null
        : browsers.stream().filter(d -> d.id().equals(recorded)).findFirst().orElse(null);
    if (recordedOne != null && recordedOne.online()) return recordedOne.id();
    DeviceDto online = browsers.stream().filter(DeviceDto::online).findFirst().orElse(null);
    if (online != null) return online.id();
    return recordedOne != null ? recordedOne.id() : browsers.get(0).id();
  }

  private static String join(List<String> items) {
    if (items.size() == 1) return items.get(0);
    return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
  }
}
