package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the reviewed steps of a recording into the graph JSON the canvas saves, so a recording
 * becomes an ordinary workflow module: a Start with the inputs the person chose, one web step per
 * kept step, a Close browser step, and an End that returns what the recording read. The browser
 * only closes because of that step: a person who wants the page left open removes it.
 *
 * <p>A desktop recording compiles the same way, without the browser steps: each step runs on the
 * PC it was recorded on.
 *
 * <p>Pure on purpose: the same steps always compile to the same graph, and nothing here talks to a
 * browser, a database or a model. A step that holds a secret never carries its value; it must name
 * a saved login before it can be saved.
 */
final class StepCompiler {

  static final String OPEN = "browser.open";
  static final String CLOSE = "browser.close";
  private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
  private static final Set<String> VALUE_FIELDS = Set.of("text", "value", "password", "secret");

  private StepCompiler() {}

  /**
   * A step's saved login as the action takes it. The browser agent takes the login and which of
   * its fields to type ({@code {"login","field"}}): a password box types the password, any other
   * box the username. The PC agent takes the secret's name.
   */
  static JsonNode secretInput(String ref, boolean sensitive, boolean web) {
    if (!web) return Json.mapper().getNodeFactory().textNode(ref);
    return Json.obj().put("login", ref).put("field", sensitive ? "password" : "username");
  }

  /** The saved login id of a step's {@code secretRef} input, in either shape. */
  static String secretId(JsonNode secretRef) {
    if (secretRef == null || secretRef.isNull()) return "";
    return secretRef.isObject() ? secretRef.path("login").asText("") : secretRef.asText("");
  }

  /**
   * @param steps the live steps in review order; each is the stored step JSON, with the person's
   *              {@code mappings} and {@code secretRef} from review
   */
  static ObjectNode compile(String startUrl, List<JsonNode> steps) {
    return compile(startUrl, steps, false);
  }

  /**
   * @param inline compile only the steps, for placing in a workflow being built: no Start or End,
   *               and a field the person made an input keeps the value that was recorded, because
   *               there is no module around it to be given one
   */
  static ObjectNode compile(String startUrl, List<JsonNode> steps, boolean inline) {
    return compile(startUrl, null, steps, inline);
  }

  /**
   * A recording made in Windows apps: the same graph, but its steps are plain device steps aimed
   * at the PC it was recorded on, with no browser to open or close.
   */
  static ObjectNode compileDesktop(UUID deviceId, List<JsonNode> steps, boolean inline) {
    return compile(null, deviceId, steps, inline);
  }

  private static ObjectNode compile(String startUrl, UUID desktop, List<JsonNode> steps,
                                    boolean inline) {
    boolean web = desktop == null;
    steps = withoutEmptyConditions(steps);
    ArrayNode nodes = Json.mapper().createArrayNode();
    ArrayNode edges = Json.mapper().createArrayNode();
    ArrayNode inputs = Json.mapper().createArrayNode();
    ArrayNode outputs = Json.mapper().createArrayNode();
    Set<String> inputNames = new HashSet<>();
    Set<String> outputNames = new HashSet<>();
    // Node ids a step brought from the workflow it was opened from (Edit recording) stay the same,
    // so a later step's {{ nodes.step_3.text }} still finds what it reads.
    Set<String> ids = new HashSet<>(List.of("start", "end"));
    for (JsonNode step : steps) {
      String kept = keptId(step);
      if (kept != null) ids.add(kept);
      String branch = step.path("meta").path("branchId").asText("");
      if (FIELD_ID.matcher(branch).matches()) ids.add(branch);
    }
    Set<String> placed = new HashSet<>(List.of("start", "end"));
    Tails tails = new Tails(edges);
    // Each open condition: its branch, and once its "Otherwise" starts, where the steps run when
    // it holds ended.
    java.util.Deque<Condition> openConditions = new java.util.ArrayDeque<>();

    if (!inline) {
      nodes.add(node("start", "TRIGGER", "Start", Json.obj().set("inputs", inputs), 0));
      tails.from("start");
    }
    int row = 1;

    if (web && (steps.isEmpty() || !OPEN.equals(steps.get(0).path("action").asText()))) {
      ObjectNode config = webConfig(OPEN, 2, Json.obj().put("url", startUrl));
      String id = fresh("open", ids, placed);
      nodes.add(node(id, "DEVICE_ACTION", "Open " + startUrl, config, row++));
      tails.to(id);
    }

    int index = 0;
    for (JsonNode step : steps) {
      index++;
      String action = step.path("action").asText();
      String label = step.path("label").asText(action);

      if (IF.equals(action)) {
        String checkId = placedId(step, "if_" + index, ids, placed);
        String branchId = step.path("meta").path("branchId").asText("");
        branchId = FIELD_ID.matcher(branchId).matches() && !placed.contains(branchId)
            ? branchId : fresh(checkId + "_when", ids, placed);
        placed.add(branchId);
        ObjectNode checkInputs = Json.obj();
        JsonNode element = step.path("input").get("element");
        if (element == null || !element.isObject()) {
          throw new ValidationException("The condition '" + label + "' needs an element to look for");
        }
        checkInputs.set("element", element.deepCopy());
        checkInputs.put("timeoutMs", Math.max(0, Math.min(60_000,
            step.path("input").path("timeoutMs").asInt(DEFAULT_IF_WAIT_MS))));
        checkInputs.put("visibleOnly", true);
        ObjectNode checkConfig = web ? webConfig("browser.exists", 1, checkInputs)
            : deviceConfig(desktop, "browser.exists", 1, checkInputs);
        checkConfig.put("ifCheck", true);
        nodes.add(node(checkId, "DEVICE_ACTION", "Look for " + elementName(step), checkConfig, row++));
        tails.to(checkId);
        boolean shown = !"missing".equals(step.path("input").path("when").asText("shown"));
        ObjectNode branchConfig = Json.obj()
            .put("condition", "nodes." + checkId + ".exists " + (shown ? "==" : "!=") + " true")
            .put("recordedIf", true).put("when", shown ? "shown" : "missing");
        nodes.add(node(branchId, "BRANCH", label, branchConfig, row++));
        tails.to(branchId);
        tails.only(branchId, "true");
        openConditions.push(new Condition(branchId, branchConfig));
        continue;
      }
      if (ELSE.equals(action)) {
        // Otherwise: the steps until the end run when the condition does not hold.
        Condition open = openConditions.peek();
        if (open == null || open.whenHolds != null) continue;
        open.whenHolds = tails.open();
        open.config.put("else", true);
        tails.only(open.branchId, "false");
        continue;
      }
      if (END_IF.equals(action)) {
        Condition open = openConditions.poll();
        if (open == null) continue;
        if (open.whenHolds == null) {
          tails.add(new String[] {open.branchId, "false"});
        } else {
          open.whenHolds.forEach(tails::add);
        }
        continue;
      }
      if (KEEP.equals(action)) {
        // A step made in the builder that a recording cannot show (a Notify): kept as it was.
        JsonNode kept = step.path("input").path("node");
        if (!kept.isObject() || !kept.path("type").isTextual()) continue;
        ObjectNode copy = ((ObjectNode) kept).deepCopy();
        String id = placedId(step, "kept_" + index, ids, placed);
        copy.put("id", id).put("x", 300).put("y", 60 + row++ * 140);
        nodes.add(copy);
        tails.to(id);
        continue;
      }

      String id = placedId(step, "step_" + index, ids, placed);
      ObjectNode stepInputs = step.path("input").isObject()
          ? ((ObjectNode) step.get("input")).deepCopy() : Json.obj();
      boolean sensitive = step.path("meta").path("sensitive").asBoolean(false);
      String secretRef = step.path("secretRef").asText("");

      if (sensitive) {
        VALUE_FIELDS.forEach(stepInputs::remove);
        if (secretRef.isBlank()) {
          throw new ValidationException(web ? "Choose a saved login for the password step"
              : "Name the secret saved on the PC (Jizza Agent › Secrets) for the password step");
        }
      }
      if (!secretRef.isBlank()) {
        VALUE_FIELDS.forEach(stepInputs::remove);
        stepInputs.set("secretRef", secretInput(secretRef, sensitive, web));
      }

      JsonNode mappings = step.path("mappings");
      // A run-time list choice keeps no default, so the item recorded is kept beside it for Edit
      // recording to run the step with.
      ObjectNode recorded = Json.obj();
      List<String> mandatory = new ArrayList<>();
      for (Iterator<Map.Entry<String, JsonNode>> it = mappings.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> mapping = it.next();
        String name = mapping.getValue().path("name").asText("");
        if (!FIELD.matcher(name).matches()) {
          throw new ValidationException("'" + name + "' cannot be an input name. Use letters, "
              + "numbers and underscores, starting with a letter");
        }
        if (inline) {
          JsonNode kept = mapping.getValue().get("default");
          if (kept != null && !kept.isNull()) setAt(stepInputs, mapping.getKey(), kept);
          continue;
        }
        JsonNode def = mapping.getValue().get("default");
        if (def == null || def.isNull() || def.asText("").isBlank()) {
          JsonNode value = valueAt(stepInputs, mapping.getKey());
          if (value != null && value.isArray() && value.size() == 1) value = value.get(0);
          if (value != null && value.isValueNode() && !value.asText("").isBlank()) {
            recorded.set(mapping.getKey(), value.deepCopy());
          }
        }
        setAt(stepInputs, mapping.getKey(),
            Json.mapper().getNodeFactory().textNode("{{ input." + name + " }}"));
        if (inputNames.add(name)) {
          ObjectNode field = inputField(name, mapping.getValue());
          describeInput(field, mapping.getKey(), mapping.getValue(), step, stepInputs);
          // The entries of a repeated section: the run form shows them as a table of its columns.
          if ("rows".equals(mapping.getKey()) && stepInputs.path("columns").isArray()) {
            ArrayNode columns = field.putArray("columns");
            for (JsonNode column : stepInputs.get("columns")) {
              ObjectNode out = columns.addObject().put("key", column.path("key").asText())
                  .put("label", column.path("label").asText(column.path("key").asText()));
              // What the column's own field knows: mandatory, its choices, its bounds.
              for (String rule : List.of("required", "options", "min", "max", "pattern")) {
                if (column.hasNonNull(rule)) out.set(rule, column.get(rule).deepCopy());
              }
            }
          }
          inputs.add(field);
        }
        // Mandatory, or asked at its step: the step's card waits for it to be filled in.
        if (mapping.getValue().path("required").asBoolean(false)
            || "atStep".equals(mapping.getValue().path("ask").asText(""))) {
          mandatory.add(mapping.getKey());
        }
      }

      // A step that types a value is bound to the site it was recorded on: the browser refuses
      // to type while the tab shows another one (a look-alike page, a redirect elsewhere).
      if (web && TYPES_VALUES.contains(action) && !stepInputs.hasNonNull("expectOrigin")) {
        String origin = originOf(step.path("meta").path("url").asText(""));
        if (origin != null) stepInputs.put("expectOrigin", origin);
      }

      int version = step.path("version").asInt(1);
      // A mandatory value: a form's field says so itself; any other is listed for the card,
      // which waits for each to be filled in before the step can be submitted.
      ArrayNode requiredInputs = Json.mapper().createArrayNode();
      for (String key : mandatory) {
        Matcher formField = FORM_FIELD.matcher(key);
        JsonNode formFields = stepInputs.path("fields");
        if (formField.matches() && formFields.path(Integer.parseInt(formField.group(1))).isObject()) {
          ((ObjectNode) formFields.get(Integer.parseInt(formField.group(1)))).put("required", true);
        } else {
          requiredInputs.add(key);
        }
      }
      ObjectNode config = web ? webConfig(action, version, stepInputs)
          : deviceConfig(desktop, action, version, stepInputs);
      if (!requiredInputs.isEmpty()) config.set("requiredInputs", requiredInputs);
      // What the builder had set on a step opened for editing (retries, guardrails, timeouts).
      JsonNode extra = step.path("meta").path("config");
      for (Iterator<Map.Entry<String, JsonNode>> it = extra.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        if (!RESERVED_CONFIG.contains(field.getKey())) config.set(field.getKey(), field.getValue().deepCopy());
      }
      ObjectNode list = listFacts(step.path("meta"));
      if (list != null) config.set("list", list);
      if (!recorded.isEmpty()) config.set("recorded", recorded);
      boolean disabled = step.path("disabled").asBoolean(false);
      if (disabled) config.put("disabled", true);
      if (step.path("optional").asBoolean(false)) config.put("optional", true);
      nodes.add(node(id, "DEVICE_ACTION", label, config, row++));
      tails.to(id);

      String outputKey = readsData(action);
      if (!inline && outputKey != null && !disabled) {
        String name = unique(nameFrom(label, outputKey), outputNames);
        outputs.add(Json.obj().put("name", name)
            .put("type", "rows".equals(outputKey) ? "list" : "text")
            .put("value", "{{ nodes." + id + "." + outputKey + " }}"));
      }
    }
    // A condition left open ends with the steps.
    while (!openConditions.isEmpty()) {
      Condition open = openConditions.pop();
      if (open.whenHolds == null) {
        tails.add(new String[] {open.branchId, "false"});
      } else {
        open.whenHolds.forEach(tails::add);
      }
    }

    JsonNode last = lastAction(steps);
    boolean closes = last != null && CLOSE.equals(last.path("action").asText());
    if (web && !inline && !closes) {
      String id = fresh("close", ids, placed);
      nodes.add(node(id, "DEVICE_ACTION", "Close browser",
          webConfig(CLOSE, 1, Json.obj().put("what", "session")), row++));
      tails.to(id);
    }

    if (!inline) {
      nodes.add(node("end", "END", "End", Json.obj().set("outputs", outputs), row));
      tails.to("end");
    }
    ObjectNode graph = Json.obj();
    graph.set("nodes", nodes);
    graph.set("edges", edges);
    return graph;
  }

  static final String IF = "flow.if";
  static final String END_IF = "flow.endIf";
  static final String ELSE = "flow.else";
  static final String KEEP = "flow.keep";
  static final int DEFAULT_IF_WAIT_MS = 3_000;
  private static final Pattern FORM_FIELD = Pattern.compile("fields\\.(\\d+)\\.value");
  private static final Pattern FIELD_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
  private static final Set<String> RESERVED_CONFIG = Set.of("action", "version", "inputs", "list",
      "preset", "target", "disabled", "optional", "ifCheck", "recorded", "requiredInputs");

  /** A condition being compiled: its branch node, and the open paths where its steps run when
   *  it holds ended, once its "Otherwise" starts. */
  private static final class Condition {
    final String branchId;
    final ObjectNode config;
    List<String[]> whenHolds;

    Condition(String branchId, ObjectNode config) {
      this.branchId = branchId;
      this.config = config;
    }
  }

  /** The paths still open at the point the next node goes: each edge it gets, and its outlet. */
  private static final class Tails {
    private final ArrayNode edges;
    private List<String[]> open = new java.util.ArrayList<>();

    Tails(ArrayNode edges) {
      this.edges = edges;
    }

    void from(String id) {
      open = new java.util.ArrayList<>();
      open.add(new String[] {id, null});
    }

    void only(String id, String branch) {
      open = new java.util.ArrayList<>();
      open.add(new String[] {id, branch});
    }

    void add(String[] tail) {
      open.add(tail);
    }

    List<String[]> open() {
      return new java.util.ArrayList<>(open);
    }

    void to(String id) {
      for (String[] tail : open) {
        ObjectNode edge = edge(tail[0], id);
        if (tail[1] != null) edge.put("branch", tail[1]);
        edges.add(edge);
      }
      from(id);
    }
  }

  private static String keptId(JsonNode step) {
    String id = step.path("nodeId").asText("");
    return FIELD_ID.matcher(id).matches() ? id : null;
  }

  /** The step's own node id when it has one not yet used, else a new one. */
  private static String placedId(JsonNode step, String fallback, Set<String> ids, Set<String> placed) {
    String kept = keptId(step);
    if (kept != null && placed.add(kept)) return kept;
    return fresh(fallback, ids, placed);
  }

  private static String fresh(String base, Set<String> ids, Set<String> placed) {
    String id = base;
    for (int i = 2; ids.contains(id) || placed.contains(id); i++) id = base + "_" + i;
    ids.add(id);
    placed.add(id);
    return id;
  }

  /** A condition with no steps inside does nothing, so it is left out; so is an empty
   *  "Otherwise", and one that is not in a condition. */
  private static List<JsonNode> withoutEmptyConditions(List<JsonNode> steps) {
    List<JsonNode> out = new java.util.ArrayList<>(steps);
    boolean removed = true;
    while (removed) {
      removed = false;
      int depth = 0;
      for (int i = 0; i < out.size() && !removed; i++) {
        String action = out.get(i).path("action").asText();
        String next = i + 1 < out.size() ? out.get(i + 1).path("action").asText() : "";
        if (IF.equals(action)) depth++;
        if (END_IF.equals(action)) depth = Math.max(0, depth - 1);
        if (ELSE.equals(action) && (depth == 0 || END_IF.equals(next) || ELSE.equals(next))) {
          out.remove(i);
          removed = true;
        } else if (IF.equals(action) && END_IF.equals(next)) {
          out.remove(i + 1);
          out.remove(i);
          removed = true;
        }
      }
    }
    return out;
  }

  private static JsonNode lastAction(List<JsonNode> steps) {
    for (int i = steps.size() - 1; i >= 0; i--) {
      String action = steps.get(i).path("action").asText();
      if (!END_IF.equals(action) && !ELSE.equals(action)) return IF.equals(action) ? null : steps.get(i);
    }
    return null;
  }

  private static String elementName(JsonNode step) {
    String name = step.path("input").path("element").path("label").asText("").strip();
    if (name.isEmpty()) name = "the element";
    return name.length() > 80 ? name.substring(0, 80) : name;
  }

  /**
   * A field of a step's inputs, where {@code within.item} is field {@code item} of object
   * {@code within} (the card a "Choose from a list" step chooses inside). Null when absent.
   */
  static JsonNode valueAt(JsonNode inputs, String field) {
    JsonNode node = inputs;
    for (String part : field.split("\\.", -1)) {
      if (node == null) return null;
      if (node.isObject()) {
        node = node.get(part);
      } else if (node.isArray() && part.matches("\\d{1,3}")) {
        // A form step's field: fields.2.value.
        node = node.get(Integer.parseInt(part));
      } else {
        return null;
      }
    }
    return node;
  }

  /** Sets a field of a step's inputs by the same dotted path, when its parent object exists. */
  static void setAt(ObjectNode inputs, String field, JsonNode value) {
    int dot = field.lastIndexOf('.');
    JsonNode parent = dot < 0 ? inputs : valueAt(inputs, field.substring(0, dot));
    if (parent instanceof ObjectNode object) object.set(field.substring(dot + 1), value);
  }

  /**
   * What a recorded "Choose from a list" step knew about its list: the names the person gave the
   * items and the cards, and the names the list showed. The builder and the approval card show
   * them as field names and drop-down choices. Null when the step has none of it.
   */
  private static ObjectNode listFacts(JsonNode meta) {
    JsonNode list = meta.path("list");
    if (!list.isObject()) return null;
    ObjectNode out = Json.obj();
    for (String key : List.of("title", "withinTitle", "kind")) {
      if (list.path(key).isTextual() && !list.get(key).asText().isBlank()) {
        out.put(key, list.get(key).asText());
      }
    }
    if (list.path("named").asBoolean(false)) out.put("named", true);
    if (list.path("options").isArray()) out.set("options", list.get("options"));
    if (list.path("groups").isArray()) out.set("groups", list.get("groups"));
    // Mapped before recording (browser agent 0.9.0+): the list this one follows.
    JsonNode follows = list.path("dependsOn");
    if (follows.isObject() && follows.path("title").isTextual()) {
      ObjectNode dependsOn = out.putObject("dependsOn");
      dependsOn.put("title", follows.get("title").asText());
      if (follows.path("inputName").isTextual()) {
        dependsOn.put("inputName", follows.get("inputName").asText());
      }
      String relation = follows.path("relation").asText("after");
      dependsOn.put("relation", "inside".equals(relation) ? "inside" : "after");
    }
    return out.isEmpty() ? null : out;
  }

  /** The mapped inputs of the steps, in the shape a workflow keeps as its input schema. */
  static ArrayNode inputsOf(ObjectNode graph) {
    for (JsonNode n : graph.path("nodes")) {
      if ("TRIGGER".equals(n.path("type").asText())) return (ArrayNode) n.path("config").path("inputs");
    }
    return Json.mapper().createArrayNode();
  }

  private static String readsData(String action) {
    return switch (action) {
      case "browser.getText" -> "text";
      case "browser.extractTable" -> "rows";
      case "ui.readWindow" -> "text";
      default -> null;
    };
  }

  private static String nameFrom(String label, String fallback) {
    String cleaned = label.replaceAll("[^A-Za-z0-9]+", " ").strip();
    StringBuilder out = new StringBuilder();
    for (String word : cleaned.split(" ")) {
      if (word.isEmpty()) continue;
      out.append(out.length() == 0 ? word.substring(0, 1).toLowerCase() : word.substring(0, 1).toUpperCase())
          .append(word.substring(1));
    }
    String name = out.toString();
    if (name.isEmpty() || !Character.isLetter(name.charAt(0))) name = fallback;
    return name.length() > 64 ? name.substring(0, 64) : name;
  }

  private static String unique(String base, Set<String> used) {
    String name = base;
    for (int i = 2; !used.add(name); i++) name = base + i;
    return name;
  }

  private static ObjectNode inputField(String name, JsonNode mapping) {
    ObjectNode field = Json.obj().put("name", name)
        .put("type", mapping.path("type").asText("text"));
    JsonNode def = mapping.get("default");
    boolean hasDefault = def != null && !def.isNull();
    field.put("required", !hasDefault);
    if (hasDefault) field.set("default", def);
    // A date the site writes its own way: the module's input keeps that format.
    String format = mapping.path("format").asText("");
    if (!format.isBlank()) field.put("format", format);
    return field;
  }

  /** The catalogue keys a mapping may carry, passed to the module input as they are. */
  private static final List<String> CATALOGUE = List.of("label", "group", "ask", "options",
      "synonyms", "min", "max", "pattern", "patternHint");
  private static final int GROUP_LENGTH = 40;

  /**
   * What the run form and every run path learn about an input beyond its type: the person's own
   * details from the mapping, else what the recording itself shows. Its group is the page it was
   * typed on; a mandatory value is asked at its step; a dropdown kept as Static accepts only the
   * items it listed. Nothing here depends on any one site.
   */
  static void describeInput(ObjectNode field, String key, JsonNode mapping, JsonNode step,
                            JsonNode stepInputs) {
    for (String rule : CATALOGUE) {
      if (mapping.hasNonNull(rule)) field.set(rule, mapping.get(rule).deepCopy());
    }
    if (!field.has("group")) {
      String group = pageName(step.path("meta"));
      if (!group.isEmpty()) field.put("group", group);
    }
    if (!field.has("ask") && mapping.path("required").asBoolean(false)) field.put("ask", "atStep");
    if (!field.has("options") && "browser.selectOption".equals(step.path("action").asText())
        && "static".equals(stepInputs.path("mode").asText()) && stepInputs.path("options").isArray()) {
      ArrayNode options = Json.mapper().createArrayNode();
      for (JsonNode option : stepInputs.get("options")) {
        String text = option.isObject() ? option.path("name").asText("") : option.asText("");
        if (!text.isBlank()) options.add(text.strip());
      }
      if (options.size() > 1) field.set("options", options);
    }
  }

  /** Web steps that type values into the page. */
  static final Set<String> TYPES_VALUES = Set.of("browser.setText", "browser.typeInto",
      "browser.fillForm", "browser.fillRows", "browser.chooseOption", "browser.setDate",
      "browser.ask");

  /** {@code https://host[:port]} of a recorded page, or null for anything but http(s). */
  static String originOf(String url) {
    try {
      java.net.URI uri = java.net.URI.create(url);
      String scheme = uri.getScheme();
      if (uri.getHost() == null || scheme == null
          || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
        return null;
      }
      return scheme.toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
          + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The page a step was recorded on, by its title, else its site's host; empty when neither. */
  static String pageName(JsonNode meta) {
    String title = meta.path("title").asText("").strip();
    if (!title.isEmpty()) {
      return title.length() <= GROUP_LENGTH ? title : title.substring(0, GROUP_LENGTH - 1).strip() + "…";
    }
    try {
      String host = java.net.URI.create(meta.path("url").asText("")).getHost();
      return host == null ? "" : host.replaceFirst("^www\\.", "");
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static ObjectNode webConfig(String action, int version, ObjectNode inputs) {
    ObjectNode config = Json.obj().put("preset", "web").put("action", action)
        .put("target", "sameBrowser");
    config.put("version", version);
    config.set("inputs", inputs);
    return config;
  }

  private static ObjectNode deviceConfig(UUID deviceId, String action, int version,
                                         ObjectNode inputs) {
    ObjectNode config = Json.obj().put("action", action).put("target", deviceId.toString());
    config.put("version", version);
    config.set("inputs", inputs);
    return config;
  }

  private static ObjectNode node(String id, String type, String label, ObjectNode config, int row) {
    ObjectNode node = Json.obj().put("id", id).put("type", type).put("label", label);
    node.set("config", config);
    return node.put("x", 300).put("y", 60 + row * 140);
  }

  private static ObjectNode edge(String from, String to) {
    return Json.obj().put("from", from).put("to", to);
  }
}
