package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The other way round from {@link StepCompiler}: the steps of a saved browser recording, read back
 * from its workflow graph so the person can edit them in their browser (Edit recording).
 *
 * <p>A recording's graph is a line of web steps from Start to End. The conditions an edit adds
 * ({@code flow.if} … {@code flow.endIf}) compile to a check step and a branch whose two paths meet
 * again, and read back the same way. A step made in the builder that a recording cannot show (a
 * Notify, a Wait) comes back as {@code flow.keep}, carried as it was. A graph that forks any other
 * way was changed in the builder past what a recording holds, and is refused.
 *
 * <p>A value the person turned into an input ({@code {{ input.name }}}) comes back as the input's
 * default with its mapping, so recompiling gives the same graph.
 */
final class RecordingGraph {

  /** The item, and the card it is in, a list choice's name ends with. */
  private static final Pattern LABEL_CHOICE =
      Pattern.compile("\"([^\"]+)\"(?: in \"([^\"]+)\")?\s*$");
  private static final Pattern WHOLE_INPUT =
      Pattern.compile("^\\{\\{\\s*input\\.([A-Za-z][A-Za-z0-9_]{0,63})\\s*}}$");
  private static final int MAX_STEPS = 500;

  private RecordingGraph() {}

  /** What a saved recording is made of, ready to edit. */
  record Opened(String startUrl, List<ObjectNode> steps, ArrayNode inputs, ArrayNode outputs) {}

  static Opened open(JsonNode graph) {
    Map<String, JsonNode> nodes = new LinkedHashMap<>();
    for (JsonNode node : graph.path("nodes")) nodes.put(node.path("id").asText(), node);
    Map<String, List<JsonNode>> outgoing = new HashMap<>();
    for (JsonNode edge : graph.path("edges")) {
      outgoing.computeIfAbsent(edge.path("from").asText(), k -> new ArrayList<>()).add(edge);
    }
    JsonNode start = nodes.values().stream()
        .filter(n -> "TRIGGER".equals(n.path("type").asText())).findFirst()
        .orElseThrow(() -> new ValidationException("This recording has no Start step"));
    ArrayNode inputs = start.path("config").path("inputs").isArray()
        ? (ArrayNode) start.path("config").get("inputs").deepCopy() : Json.mapper().createArrayNode();
    Map<String, JsonNode> defaults = new HashMap<>();
    inputs.forEach(f -> defaults.put(f.path("name").asText(), f));

    Walk walk = new Walk(nodes, outgoing, defaults);
    String first = walk.next(start.path("id").asText());
    List<ObjectNode> steps = walk.line(first, null);
    if (steps.stream().noneMatch(s -> s.path("action").asText().startsWith("browser."))) {
      throw new ValidationException("This recording has no browser steps to edit");
    }
    String startUrl = null;
    for (ObjectNode step : steps) {
      if (StepCompiler.OPEN.equals(step.path("action").asText())) {
        String url = step.path("input").path("url").asText("");
        if (url.startsWith("http://") || url.startsWith("https://")) startUrl = url;
        break;
      }
    }
    if (startUrl == null) {
      throw new ValidationException("This recording does not start by opening a web address");
    }
    JsonNode end = nodes.values().stream().filter(n -> "END".equals(n.path("type").asText()))
        .findFirst().orElse(null);
    ArrayNode outputs = end != null && end.path("config").path("outputs").isArray()
        ? (ArrayNode) end.path("config").get("outputs").deepCopy() : Json.mapper().createArrayNode();
    return new Opened(startUrl, steps, inputs, outputs);
  }

  private static final class Walk {
    final Map<String, JsonNode> nodes;
    final Map<String, List<JsonNode>> outgoing;
    final Map<String, JsonNode> defaults;
    final Set<String> seen = new HashSet<>();
    int seq;

    Walk(Map<String, JsonNode> nodes, Map<String, List<JsonNode>> outgoing,
         Map<String, JsonNode> defaults) {
      this.nodes = nodes;
      this.outgoing = outgoing;
      this.defaults = defaults;
    }

    /** The one node after {@code id}, or null at the end of the line. */
    String next(String id) {
      List<JsonNode> out = outgoing.getOrDefault(id, List.of());
      if (out.isEmpty()) return null;
      if (out.size() > 1) throw forked(id);
      return out.get(0).path("to").asText();
    }

    /** The steps from {@code id} up to (not including) {@code until}, or to the End. */
    List<ObjectNode> line(String id, String until) {
      List<ObjectNode> steps = new ArrayList<>();
      String at = id;
      while (at != null && !at.equals(until)) {
        JsonNode node = nodes.get(at);
        if (node == null) throw new ValidationException("This recording's steps are not joined up");
        if ("END".equals(node.path("type").asText())) {
          if (until != null) throw forked(at);
          break;
        }
        if (!seen.add(at) || ++seq > MAX_STEPS) {
          throw new ValidationException("This recording's steps go round in a loop");
        }
        JsonNode config = node.path("config");
        if ("DEVICE_ACTION".equals(node.path("type").asText()) && config.path("ifCheck").asBoolean(false)) {
          String branchId = next(at);
          JsonNode branch = branchId == null ? null : nodes.get(branchId);
          if (branch == null || !"BRANCH".equals(branch.path("type").asText())
              || !branch.path("config").path("recordedIf").asBoolean(false)) {
            throw forked(at);
          }
          seen.add(branchId);
          String whenTrue = null;
          String whenFalse = null;
          for (JsonNode edge : outgoing.getOrDefault(branchId, List.of())) {
            if ("true".equals(edge.path("branch").asText())) whenTrue = edge.path("to").asText();
            if ("false".equals(edge.path("branch").asText())) whenFalse = edge.path("to").asText();
          }
          if (whenTrue == null || whenFalse == null) throw forked(branchId);
          steps.add(condition(node, branch));
          if (branch.path("config").path("else").asBoolean(false)) {
            // With "Otherwise": both paths meet at the first step they share.
            String join = join(whenTrue, whenFalse);
            steps.addAll(line(whenTrue, join));
            steps.add(otherwise(++seq));
            steps.addAll(line(whenFalse, join));
            steps.add(endCondition(++seq));
            at = join;
          } else {
            steps.addAll(line(whenTrue, whenFalse));
            steps.add(endCondition(++seq));
            at = whenFalse;
          }
          continue;
        }
        steps.add(step(node));
        at = next(at);
      }
      return steps;
    }

    ObjectNode condition(JsonNode check, JsonNode branch) {
      ObjectNode input = Json.obj();
      JsonNode element = check.path("config").path("inputs").get("element");
      if (element != null) input.set("element", element.deepCopy());
      input.put("timeoutMs", check.path("config").path("inputs").path("timeoutMs")
          .asInt(StepCompiler.DEFAULT_IF_WAIT_MS));
      input.put("when", "missing".equals(branch.path("config").path("when").asText()) ? "missing" : "shown");
      ObjectNode step = Json.obj().put("seq", seq).put("action", StepCompiler.IF).put("version", 1)
          .put("label", branch.path("label").asText("If the element is shown"))
          .put("nodeId", check.path("id").asText());
      step.set("input", input);
      step.set("meta", Json.obj().put("sensitive", false).put("branchId", branch.path("id").asText()));
      return step;
    }

    /** The nearest node the path from {@code b} reaches that the path from {@code a} reaches too. */
    String join(String a, String b) {
      Set<String> fromA = new HashSet<>();
      java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(a));
      while (!queue.isEmpty() && fromA.size() <= MAX_STEPS * 4) {
        String id = queue.poll();
        if (!fromA.add(id)) continue;
        outgoing.getOrDefault(id, List.of()).forEach(e -> queue.add(e.path("to").asText()));
      }
      Set<String> visited = new HashSet<>();
      queue.add(b);
      while (!queue.isEmpty() && visited.size() <= MAX_STEPS * 4) {
        String id = queue.poll();
        if (!visited.add(id)) continue;
        if (fromA.contains(id)) return id;
        outgoing.getOrDefault(id, List.of()).forEach(e -> queue.add(e.path("to").asText()));
      }
      throw new ValidationException("This recording's steps are not joined up");
    }

    ObjectNode otherwise(int at) {
      ObjectNode step = Json.obj().put("seq", at).put("action", StepCompiler.ELSE).put("version", 1)
          .put("label", "Otherwise");
      step.set("input", Json.obj());
      step.set("meta", Json.obj().put("sensitive", false));
      return step;
    }

    ObjectNode endCondition(int at) {
      ObjectNode step = Json.obj().put("seq", at).put("action", StepCompiler.END_IF).put("version", 1)
          .put("label", "End of condition");
      step.set("input", Json.obj());
      step.set("meta", Json.obj().put("sensitive", false));
      return step;
    }

    ObjectNode step(JsonNode node) {
      JsonNode config = node.path("config");
      String action = config.path("action").asText("");
      boolean web = "DEVICE_ACTION".equals(node.path("type").asText()) && action.startsWith("browser.")
          && "sameBrowser".equals(config.path("target").asText("sameBrowser"));
      ObjectNode step = Json.obj().put("seq", seq).put("version", 1)
          .put("nodeId", node.path("id").asText());
      ObjectNode meta = Json.obj().put("sensitive", false);
      if (!web) {
        // Made in the builder, and not a browser step: kept exactly as it is.
        step.put("action", StepCompiler.KEEP).put("label", node.path("label").asText(
            node.path("type").asText("Step")));
        step.set("input", Json.obj().set("node", node.deepCopy()));
        step.set("meta", meta.put("kind", node.path("type").asText()));
        return step;
      }
      step.put("action", action).put("version", config.path("version").asInt(1))
          .put("label", node.path("label").asText(action));
      ObjectNode input = config.path("inputs").isObject()
          ? ((ObjectNode) config.get("inputs")).deepCopy() : Json.obj();
      if (input.hasNonNull("secretRef")) {
        JsonNode ref = input.get("secretRef");
        step.put("secretRef", StepCompiler.secretId(ref));
        input.remove("secretRef");
        // A username typed from a saved login is not a password box.
        meta.put("sensitive", !"username".equals(ref.path("field").asText("")));
      }
      ObjectNode mappings = Json.obj();
      unmap(input, "", mappings);
      if (!mappings.isEmpty()) step.set("mappings", mappings);
      restoreRecorded(input, mappings, config, node.path("label").asText(""));
      // "Wait until the page answers": each outcome's rule goes back beside it, as it was recorded.
      if (StepCompiler.WAIT_FOR.equals(action) && input.path("outcomes").isArray()) {
        for (JsonNode outcome : input.get("outcomes")) {
          JsonNode rule = config.path("onOutcome").path(outcome.path("name").asText(""));
          if (outcome instanceof ObjectNode o && rule.isObject()) {
            o.put("then", rule.path("then").asText("continue"));
            if (rule.hasNonNull("message")) o.put("message", rule.get("message").asText());
          }
        }
      }
      if (config.path("irreversible").asBoolean(false)) meta.put("irreversible", true);
      if (config.path("readAs").isObject()) meta.set("readAs", config.get("readAs").deepCopy());
      if (config.path("poll").isObject()) meta.set("poll", config.get("poll").deepCopy());
      step.set("input", input);
      if (config.path("list").isObject()) meta.set("list", config.get("list").deepCopy());
      ObjectNode extra = Json.obj();
      for (Iterator<Map.Entry<String, JsonNode>> it = config.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        if (!Set.of("action", "version", "inputs", "list", "preset", "target", "disabled",
            "optional", "recorded", "onOutcome", "irreversible", "readAs", "poll")
            .contains(field.getKey())) {
          extra.set(field.getKey(), field.getValue().deepCopy());
        }
      }
      if (!extra.isEmpty()) meta.set("config", extra);
      if (config.path("disabled").asBoolean(false)) step.put("disabled", true);
      if (config.path("optional").asBoolean(false)) step.put("optional", true);
      step.set("meta", meta);
      return step;
    }

    /**
     * Each value that is exactly {@code {{ input.name }}} becomes the input's default again, with
     * a mapping that turns it back into the input on save.
     */
    void unmap(JsonNode node, String path, ObjectNode mappings) {
      if (node instanceof ObjectNode object) {
        List<String> keys = new ArrayList<>();
        object.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
          String at = path.isEmpty() ? key : path + "." + key;
          JsonNode value = object.get(key);
          String name = inputName(value);
          if (name != null) {
            JsonNode field = defaults.get(name);
            object.set(key, recorded(field));
            mappings.set(at, mapping(name, field));
          } else {
            unmap(value, at, mappings);
          }
        }
      } else if (node instanceof ArrayNode array) {
        for (int i = 0; i < array.size() && i < 1000; i++) {
          String at = path + "." + i;
          String name = inputName(array.get(i));
          if (name != null) {
            JsonNode field = defaults.get(name);
            array.set(i, recorded(field));
            mappings.set(at, mapping(name, field));
          } else {
            unmap(array.get(i), at, mappings);
          }
        }
      }
    }

    /**
     * A run-time list choice is saved with no default, so its value comes back empty. Steps can
     * run with the item recorded: kept in {@code config.recorded} since Edit recording, and for
     * older recordings read from the step's name ({@code Choose Time "06:30 PM" in "Rakki"}), when
     * the list it read had that name.
     */
    static void restoreRecorded(ObjectNode input, ObjectNode mappings, JsonNode config, String label) {
      Matcher named = LABEL_CHOICE.matcher(label.strip());
      boolean fromLabel = named.find();
      for (Iterator<String> it = mappings.fieldNames(); it.hasNext(); ) {
        String field = it.next();
        JsonNode current = StepCompiler.valueAt(input, field);
        if (current == null || !current.isValueNode() || !current.asText("").isBlank()) continue;
        JsonNode kept = config.path("recorded").get(field);
        String value = kept != null && kept.isValueNode() ? kept.asText("") : "";
        if (value.isBlank() && fromLabel && "browser.clickItem".equals(config.path("action").asText())) {
          String guess = "item".equals(field) ? named.group(1)
              : "within.item".equals(field) ? named.group(2) : null;
          if (guess != null && listed(config.path("list"), field, guess)) value = guess;
        }
        if (!value.isBlank()) StepCompiler.setAt(input, field, Json.mapper().getNodeFactory().textNode(value));
      }
    }

    /** Whether a list's names include {@code value}; true when it kept no names. */
    private static boolean listed(JsonNode list, String field, String value) {
      boolean known = false;
      if ("item".equals(field)) {
        for (JsonNode option : list.path("options")) {
          known = true;
          if (value.equals(option.asText())) return true;
        }
        for (JsonNode group : list.path("groups")) {
          for (JsonNode item : group.path("items")) {
            known = true;
            if (value.equals(item.asText())) return true;
          }
        }
      } else {
        for (JsonNode group : list.path("groups")) {
          known = true;
          if (value.equals(group.path("label").asText())) return true;
        }
      }
      return !known;
    }

    private static String inputName(JsonNode value) {
      if (value == null || !value.isTextual()) return null;
      Matcher m = WHOLE_INPUT.matcher(value.asText().strip());
      return m.matches() ? m.group(1) : null;
    }

    private static JsonNode recorded(JsonNode field) {
      JsonNode def = field == null ? null : field.get("default");
      return def == null || def.isNull() ? Json.mapper().getNodeFactory().textNode("") : def.deepCopy();
    }

    private static ObjectNode mapping(String name, JsonNode field) {
      ObjectNode mapping = Json.obj().put("name", name)
          .put("type", field == null ? "text" : field.path("type").asText("text"));
      JsonNode def = field == null ? null : field.get("default");
      if (def != null) mapping.set("default", def.deepCopy());
      return mapping;
    }

    private static ValidationException forked(String id) {
      return new ValidationException("This recording has branches or paths added in the builder "
          + "(at step '" + id + "'), so it can only be changed in the builder");
    }
  }

  /**
   * The outputs the person had on the End step that a recompile does not make itself, kept when
   * the steps they read are still there.
   */
  static void keepOutputs(ObjectNode graph, ArrayNode before) {
    Set<String> ids = new HashSet<>();
    graph.path("nodes").forEach(n -> ids.add(n.path("id").asText()));
    for (JsonNode node : graph.path("nodes")) {
      if (!"END".equals(node.path("type").asText())) continue;
      ObjectNode config = (ObjectNode) node.path("config");
      ArrayNode outputs = config.path("outputs").isArray() ? (ArrayNode) config.get("outputs")
          : config.putArray("outputs");
      Set<String> names = new HashSet<>();
      outputs.forEach(o -> names.add(o.path("name").asText()));
      Set<String> values = new HashSet<>();
      outputs.forEach(o -> values.add(o.path("value").asText()));
      for (JsonNode old : before) {
        if (names.contains(old.path("name").asText()) || values.contains(old.path("value").asText())) {
          continue;
        }
        Matcher refs = Pattern.compile("nodes\\.([A-Za-z0-9_-]+)").matcher(old.path("value").asText());
        boolean ok = true;
        while (refs.find()) ok &= ids.contains(refs.group(1));
        if (ok) outputs.add(old.deepCopy());
      }
    }
  }

  /** The result message the person wrote on the End step ("Booked. PNR {{ result.pnr }}"). */
  static void keepResultMessage(ObjectNode graph, JsonNode before) {
    String message = null;
    for (JsonNode node : before.path("nodes")) {
      if ("END".equals(node.path("type").asText())
          && node.path("config").path("resultMessage").isTextual()) {
        message = node.path("config").get("resultMessage").asText();
      }
    }
    if (message == null) return;
    for (JsonNode node : graph.path("nodes")) {
      if ("END".equals(node.path("type").asText()) && node.path("config") instanceof ObjectNode c
          && !c.has("resultMessage")) {
        c.put("resultMessage", message);
      }
    }
  }

  /**
   * The step groups the person drew in the builder, for the steps that are still there (an edit
   * keeps each step's node id). A group left with no steps goes.
   */
  static void keepGroups(ObjectNode graph, JsonNode before) {
    if (!before.path("groups").isArray() || graph.has("groups")) return;
    Set<String> ids = new HashSet<>();
    for (JsonNode node : graph.path("nodes")) {
      if (!"TRIGGER".equals(node.path("type").asText())) ids.add(node.path("id").asText());
    }
    ArrayNode groups = graph.arrayNode();
    for (JsonNode group : before.path("groups")) {
      if (!(group instanceof ObjectNode g)) continue;
      ArrayNode members = graph.arrayNode();
      for (JsonNode id : g.path("nodes")) if (ids.contains(id.asText())) members.add(id.asText());
      if (members.isEmpty()) continue;
      ObjectNode copy = g.deepCopy();
      copy.set("nodes", members);
      groups.add(copy);
    }
    if (!groups.isEmpty()) graph.set("groups", groups);
  }

  /** Descriptions and other details the person gave inputs that are still there. */
  static void keepInputDetails(ObjectNode graph, ArrayNode before) {
    Map<String, JsonNode> old = new HashMap<>();
    before.forEach(f -> old.put(f.path("name").asText(), f));
    for (JsonNode node : graph.path("nodes")) {
      if (!"TRIGGER".equals(node.path("type").asText())) continue;
      for (JsonNode field : node.path("config").path("inputs")) {
        JsonNode was = old.get(field.path("name").asText());
        if (!(was instanceof ObjectNode wasObject) || !(field instanceof ObjectNode now)) continue;
        for (Iterator<Map.Entry<String, JsonNode>> it = wasObject.fields(); it.hasNext(); ) {
          Map.Entry<String, JsonNode> e = it.next();
          if (!now.has(e.getKey())) now.set(e.getKey(), e.getValue().deepCopy());
        }
      }
    }
  }
}
