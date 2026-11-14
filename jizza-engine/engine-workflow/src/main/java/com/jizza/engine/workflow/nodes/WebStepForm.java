package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a person sees, and may change, when a web step stops for their approval: each value the
 * step will send, as it resolved from earlier steps ("Ramayana", not {@code {{ input.movie }}}),
 * which of them are mandatory and still empty, and for a choice made at run time the items the
 * page lists now.
 *
 * <p>The person's answer comes back as plain values keyed like the fields here. They are applied
 * to the step's inputs as written and never evaluated, and only keys this step offered are taken.
 */
final class WebStepForm {

  private WebStepForm() {}

  /** Inputs that are about how the step runs, not what it sends: never shown or editable. */
  private static final Set<String> PLUMBING = Set.of("session", "element", "timeoutMs", "trusted",
      "screenshotOnError", "highlight", "secretRef", "waitElement", "to", "offset", "modifiers",
      "steps", "fields", "group", "in", "waitAfter", "by", "max", "within", "loadMore", "submit",
      "checkErrors", "opener", "optionElement", "prefer", "app", "itemCss", "titleCss",
      "options");

  static final String CLICK_ITEM = "browser.clickItem";
  static final String FILL_FORM = "browser.fillForm";
  static final String CHOOSE_OPTION = "browser.chooseOption";
  /** A click on one of the buttons the person grouped while recording, chosen by name. */
  static final String CLICK_ONE_OF = "browser.clickOneOf";
  /** Choices made from a list the page or app shows at run time. */
  static final Set<String> CLICK_ITEMS = Set.of(CLICK_ITEM, "ui.clickItem");
  static final Set<String> FILL_FORMS = Set.of(FILL_FORM, "ui.fillForm");

  /** The input that names the item chosen, for an action that chooses from a list. */
  static String pickKey(String action) {
    if (CLICK_ITEMS.contains(action) || CLICK_ONE_OF.equals(action)) return "item";
    if (CHOOSE_OPTION.equals(action)) return "option";
    return null;
  }

  /** The action that reads the list a choosing action chooses from. */
  static String listAction(String action) {
    return action.startsWith("ui.") ? "ui.listItems" : "browser.listItems";
  }
  private static final int MAX_TEXT = 10_000;
  private static final int MAX_LIST = 100;
  private static final int MAX_DATA = 40;

  /** One value of the step, as the approval card shows it. */
  record Field(String key, String label, String type, JsonNode value, boolean required,
               String source, List<String> options, String error) {

    Field(String key, String label, String type, JsonNode value, boolean required,
          String source, List<String> options) {
      this(key, label, type, value, required, source, options, null);
    }

    Field withError(String message) {
      return new Field(key, label, type, value, required, source, options, message);
    }

    Field withOptions(String newType, List<String> newOptions, boolean blank) {
      return new Field(key, label, newType, blank ? null : value, blank || required, source,
          newOptions, error);
    }

    boolean missing() { return required && empty(value); }

    ObjectNode toJson() {
      ObjectNode out = Json.obj().put("key", key).put("label", label).put("type", type)
          .put("required", required).put("missing", missing());
      if (value != null && !value.isMissingNode() && !value.isNull()) out.set("value", value);
      if (source != null) out.put("source", source);
      if (error != null) out.put("error", error);
      if (options != null) {
        ArrayNode list = out.putArray("options");
        options.forEach(list::add);
      }
      return out;
    }
  }

  // ── preparing the inputs ────────────────────────────────────────────────

  /**
   * Turns mapped values into the shapes the browser takes: a list for items mapped from text
   * ("Music, Travel"), "checked"/"unchecked" for a checkbox mapped from yes/no, text for numbers.
   */
  static void normalize(String action, ObjectNode inputs) {
    switch (action) {
      case "browser.selectItem" -> {
        if (inputs.has("items")) inputs.set("items", asList(inputs.get("items")));
        if (inputs.path("items").size() > 1 && !inputs.has("multiple")) {
          inputs.put("multiple", true);
        }
      }
      case "browser.checkItems" -> {
        if (inputs.has("items")) inputs.set("items", asList(inputs.get("items")));
      }
      case "browser.setCheckbox" -> {
        if (inputs.has("state")) inputs.put("state", checkboxState(inputs.get("state")));
      }
      case "browser.setText", "browser.setDate", "browser.pickDate", CHOOSE_OPTION, CLICK_ITEM,
          "ui.clickItem", CLICK_ONE_OF -> {
        for (String key : List.of("text", "value", "item", "date", "option")) {
          JsonNode value = inputs.get(key);
          if (value != null && (value.isNumber() || value.isBoolean())) {
            inputs.put(key, value.asText());
          }
        }
      }
      case FILL_FORM, "ui.fillForm" -> {
        if (!inputs.path("fields").isArray()) return;
        ArrayNode kept = Json.mapper().createArrayNode();
        for (JsonNode raw : inputs.get("fields")) {
          if (!raw.isObject()) continue;
          ObjectNode field = (ObjectNode) raw;
          field.set("value", fieldValue(field.path("kind").asText("text"), field.get("value")));
          // An optional field left empty is left as the page has it.
          if (empty(field.get("value")) && !field.path("required").asBoolean(false)) {
            field.remove("value");
            if (!"checkbox".equals(field.path("kind").asText())) continue;
          }
          kept.add(field);
        }
        inputs.set("fields", kept);
      }
      default -> { }
    }
  }

  private static JsonNode fieldValue(String kind, JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) return null;
    return switch (kind) {
      case "checkboxes" -> asList(value);
      case "checkbox" -> Json.mapper().getNodeFactory().booleanNode(truthy(value));
      default -> value.isTextual() ? value
          : value.isArray() ? Json.mapper().getNodeFactory().textNode(joined(value))
          : Json.mapper().getNodeFactory().textNode(value.asText());
    };
  }

  /** A list from a list, from comma- or line-separated text, or from one value. */
  static ArrayNode asList(JsonNode value) {
    ArrayNode out = Json.mapper().createArrayNode();
    if (value == null || value.isNull() || value.isMissingNode()) return out;
    if (value.isArray()) {
      value.forEach(v -> {
        if (v.isIntegralNumber()) out.add(v.asInt());
        else if (!v.isNull() && !v.asText().isBlank()) out.add(v.asText().trim());
      });
      return out;
    }
    if (value.isIntegralNumber()) return out.add(value.asInt());
    String text = value.asText().trim();
    if (text.startsWith("[")) {
      try {
        JsonNode parsed = Json.mapper().readTree(text);
        if (parsed.isArray()) return asList(parsed);
      } catch (Exception ignored) {
        // Plain text that happens to start with a bracket.
      }
    }
    for (String part : text.split("[,\\n]")) {
      if (!part.isBlank()) out.add(part.trim());
    }
    return out;
  }

  private static String checkboxState(JsonNode value) {
    String text = value.asText("").trim().toLowerCase(Locale.ROOT);
    if (text.equals("checked") || text.equals("unchecked") || text.equals("toggle")) return text;
    return truthy(value) ? "checked" : "unchecked";
  }

  static boolean truthy(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return false;
    if (value.isBoolean()) return value.asBoolean();
    if (value.isNumber()) return value.asDouble() != 0;
    String text = value.asText("").trim().toLowerCase(Locale.ROOT);
    return Set.of("true", "yes", "y", "on", "1", "checked", "tick", "ticked").contains(text);
  }

  // ── what the card shows ─────────────────────────────────────────────────

  /**
   * The values this step sends, resolved. A value the step's settings leave out is shown only
   * when the action needs it; for a choice made at run time, the item is a pick from
   * {@code choices}.
   */
  static List<Field> fields(DeviceActionSpec spec, JsonNode config, ObjectNode inputs,
                            List<String> choices) {
    List<Field> out = new ArrayList<>();
    JsonNode configured = config.path("inputs");
    if (FILL_FORMS.contains(spec.action())) {
      JsonNode raw = configured.path("fields");
      int index = 0;
      for (JsonNode field : inputs.path("fields")) {
        String kind = field.path("kind").asText("text");
        // The rendered list drops optional empty fields, so match it back by element label.
        JsonNode source = sourceField(raw, field, index);
        String label = field.path("label").asText(field.path("element").isTextual()
            ? field.path("element").asText()
            : field.path("element").path("label").asText("Field " + (index + 1)));
        out.add(new Field("fields." + index + ".value", label, formType(kind),
            field.get("value"), field.path("required").asBoolean(false),
            template(source == null ? null : source.get("value")), null));
        index++;
      }
      return out;
    }

    JsonNode schema = spec.input() == null ? Json.obj() : spec.input();
    JsonNode properties = schema.path("properties");
    Set<String> required = new LinkedHashSet<>();
    schema.path("required").forEach(r -> required.add(r.asText()));
    // setText needs text unless it types a saved login.
    if (schema.path("oneOf").isArray() && !inputs.has("secretRef")) {
      for (JsonNode choice : schema.get("oneOf")) {
        choice.path("required").forEach(r -> {
          if (!"secretRef".equals(r.asText())) required.add(r.asText());
        });
        break;
      }
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = it.next();
      String key = entry.getKey();
      JsonNode property = entry.getValue();
      if (PLUMBING.contains(key) || property.has("$ref")) continue;
      boolean chosen = key.equals(pickKey(spec.action())) && choices != null;
      if (!required.contains(key) && !configured.has(key) && !chosen) continue;
      if ("object".equals(property.path("type").asText())) continue;
      boolean pick = key.equals(pickKey(spec.action())) && choices != null;
      // The grouped buttons are the choices: their names, as the step keeps them.
      List<String> buttons = CLICK_ONE_OF.equals(spec.action()) && "item".equals(key)
          ? optionNames(inputs.path("options")) : null;
      List<String> options = pick ? choices : buttons != null ? buttons : enumOptions(property);
      String type = pick || buttons != null ? "select" : typeOf(spec.action(), key, property);
      out.add(new Field(key, label(key, spec.action()), type, inputs.get(key),
          required.contains(key), template(configured.get(key)), options));
    }
    if (CLICK_ITEMS.contains(spec.action()) && inputs.path("within").isObject()
        && out.stream().noneMatch(f -> "within.item".equals(f.key()))) {
      String what = inputs.path("within").path("element").path("label").asText("");
      // A card's recorded label is all its text run together; a short one names the list.
      out.add(0, new Field("within.item", what.isBlank() || what.length() > 40
          ? "Card to choose in" : what, "text",
          inputs.path("within").get("item"), true,
          template(configured.path("within").get("item")), null));
    }
    return CLICK_ITEMS.contains(spec.action()) || CLICK_ONE_OF.equals(spec.action())
        ? named(out, config.path("list")) : out;
  }

  /** The names of a grouped-buttons step's buttons, or null when it has none. */
  static List<String> optionNames(JsonNode options) {
    if (!options.isArray() || options.isEmpty()) return null;
    List<String> names = new ArrayList<>();
    for (JsonNode option : options) {
      String name = option.path("name").asText("");
      if (!name.isBlank()) names.add(name);
    }
    return names.isEmpty() ? null : names;
  }

  /**
   * The names the person gave a marked list while recording (config {@code list}: "Showtime"
   * for its items, "Theatre" for its cards) as the labels of the fields that choose them.
   */
  private static List<Field> named(List<Field> fields, JsonNode list) {
    if (!list.path("named").asBoolean(false)) return fields;
    String item = list.path("title").asText("").strip();
    String card = list.path("withinTitle").asText("").strip();
    List<Field> out = new ArrayList<>(fields.size());
    for (Field f : fields) {
      String name = "item".equals(f.key()) ? item : "within.item".equals(f.key()) ? card : "";
      out.add(name.isEmpty() ? f : new Field(f.key(), name, f.type(), f.value(), f.required(),
          f.source(), f.options(), f.error()));
    }
    return out;
  }

  private static JsonNode sourceField(JsonNode raw, JsonNode rendered, int index) {
    if (!raw.isArray()) return null;
    String label = rendered.path("element").path("label").asText(null);
    for (JsonNode candidate : raw) {
      if (label != null && label.equals(candidate.path("element").path("label").asText(null))
          && rendered.path("kind").asText().equals(candidate.path("kind").asText())) {
        return candidate;
      }
    }
    return index < raw.size() ? raw.get(index) : null;
  }

  private static String formType(String kind) {
    return switch (kind) {
      case "checkbox" -> "checkbox";
      case "checkboxes" -> "list";
      case "date", "datepicker" -> "date";
      default -> "text";
    };
  }

  private static String typeOf(String action, String key, JsonNode property) {
    if (property.has("enum")) return "select";
    if ("browser.setDate".equals(action) && "value".equals(key)) return "date";
    if ("browser.pickDate".equals(action) && "date".equals(key)) return "date";
    return switch (property.path("type").asText("string")) {
      case "boolean" -> "checkbox";
      case "array" -> "list";
      case "integer", "number" -> "number";
      default -> "text";
    };
  }

  private static List<String> enumOptions(JsonNode property) {
    if (!property.path("enum").isArray()) return null;
    List<String> out = new ArrayList<>();
    property.get("enum").forEach(v -> out.add(v.asText()));
    return out;
  }

  private static final Map<String, String> LABELS = Map.ofEntries(
      Map.entry("text", "Text"), Map.entry("value", "Value"), Map.entry("items", "Items"),
      Map.entry("item", "Item to choose"), Map.entry("state", "Checkbox"),
      Map.entry("url", "Address"), Map.entry("keys", "Keys"),
      Map.entry("only", "Untick the others"), Map.entry("format", "Date format"),
      Map.entry("date", "Date"), Map.entry("option", "Option to choose"));

  private static String label(String key, String action) {
    if ("browser.setDate".equals(action) && "value".equals(key)) return "Date";
    String known = LABELS.get(key);
    if (known != null) return known;
    String spaced = key.replaceAll("([A-Z])", " $1").toLowerCase(Locale.ROOT);
    return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
  }

  private static String template(JsonNode configured) {
    return configured != null && configured.isTextual() && configured.asText().contains("{{")
        ? configured.asText() : null;
  }

  static boolean empty(JsonNode value) {
    return value == null || value.isNull() || value.isMissingNode()
        || value.isTextual() && value.asText().isBlank()
        || value.isArray() && value.isEmpty();
  }

  // ── the person's changes ────────────────────────────────────────────────

  /**
   * Puts the person's values into the inputs: only for keys the card offered, only as plain text,
   * yes/no, numbers or lists of text, and never evaluated.
   */
  static void apply(ObjectNode inputs, JsonNode edits, Set<String> offered) {
    if (edits == null || !edits.isObject()) return;
    for (Iterator<Map.Entry<String, JsonNode>> it = edits.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> edit = it.next();
      if (!offered.contains(edit.getKey()) || edit.getKey().startsWith("_")) continue;
      JsonNode value = plain(edit.getValue());
      if (value == null) continue;
      String[] path = edit.getKey().split("\\.");
      if (path.length == 1) {
        if (empty(value)) inputs.remove(path[0]);
        else inputs.set(path[0], value);
      } else if (path.length == 2 && inputs.path(path[0]).isObject()) {
        ((ObjectNode) inputs.get(path[0])).set(path[1], value);
      } else if (path.length == 3 && "fields".equals(path[0]) && inputs.path("fields").isArray()) {
        int index;
        try {
          index = Integer.parseInt(path[1]);
        } catch (NumberFormatException e) {
          continue;
        }
        JsonNode field = inputs.get("fields").get(index);
        if (field instanceof ObjectNode object && "value".equals(path[2])) {
          object.set("value", fieldValue(object.path("kind").asText("text"), value));
        }
      }
    }
  }

  private static JsonNode plain(JsonNode value) {
    if (value == null || value.isNull()) return Json.mapper().getNodeFactory().textNode("");
    if (value.isBoolean() || value.isNumber()) return value;
    if (value.isTextual()) {
      String text = value.asText();
      return Json.mapper().getNodeFactory().textNode(
          text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
    }
    if (value.isArray() && value.size() <= MAX_LIST) {
      ArrayNode out = Json.mapper().createArrayNode();
      for (JsonNode item : value) {
        if (!item.isValueNode()) return null;
        out.add(item.asText());
      }
      return out;
    }
    return null;
  }

  // ── choosing from what the page lists ──────────────────────────────────

  /** The item labels a listing returned, in page order, without repeats. */
  static List<String> labels(JsonNode listed) {
    return ItemMatch.items(listed.path("items")).stream().map(ItemMatch.Item::label).toList();
  }

  /** The listed item the wanted text names, or null when none or several do. */
  static String match(List<String> labels, String wanted) {
    return ItemMatch.match(labels.stream().map(l -> new ItemMatch.Item(l, "")).toList(), wanted,
        null).chosen();
  }

  static String fold(String text) {
    return ItemMatch.fold(text);
  }

  // ── values from earlier steps, to map an empty field ───────────────────

  /**
   * The run's values so far, flattened to plain values, so the card can offer them for a field
   * that is empty. Internal keys (starting with an underscore), device details and file ids are
   * left out.
   */
  static ArrayNode data(Map<String, Object> variables) {
    ArrayNode out = Json.mapper().createArrayNode();
    for (String root : List.of("input", "nodes", "vars")) {
      Object value = variables.get(root);
      if (value != null) flatten(root, Json.mapper().valueToTree(value), out, 0);
    }
    return out;
  }

  private static void flatten(String path, JsonNode value, ArrayNode out, int depth) {
    if (out.size() >= MAX_DATA || value == null || value.isNull()) return;
    if (value.isValueNode()) {
      String text = value.asText();
      if (text.isBlank()) return;
      out.addObject().put("path", path)
          .put("value", text.length() > 200 ? text.substring(0, 200) : text);
      return;
    }
    if (depth >= 4) return;
    if (value.isArray()) {
      if (!value.isEmpty() && value.size() <= 20 && value.get(0).isValueNode()) {
        out.addObject().put("path", path).put("value", joined(value));
      }
      return;
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = it.next();
      String key = entry.getKey();
      if (key.startsWith("_") || "device".equals(key) || "files".equals(key)) continue;
      flatten(path + "." + key, entry.getValue(), out, depth + 1);
    }
  }

  private static String joined(JsonNode list) {
    List<String> parts = new ArrayList<>();
    list.forEach(v -> parts.add(v.asText()));
    return String.join(", ", parts);
  }
}
