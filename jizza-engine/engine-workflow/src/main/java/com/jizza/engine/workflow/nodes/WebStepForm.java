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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
      "options", "format", "mode");

  static final String CLICK_ITEM = "browser.clickItem";
  static final String FILL_FORM = "browser.fillForm";
  /** A section the page repeats, one row per entry (passengers). */
  static final String FILL_ROWS = "browser.fillRows";
  /** Between a column's key and its label in a rows field's options (see {@link Field#toJson}). */
  static final String COLUMN_SEP = "\u001F";
  private static final Pattern INPUT_REF = Pattern.compile("\\{\\{\\s*input\\.([A-Za-z][A-Za-z0-9_]*)\\s*}}");
  static final String CHOOSE_OPTION = "browser.chooseOption";
  /** A click on one of the buttons the person grouped while recording, chosen by name. */
  static final String CLICK_ONE_OF = "browser.clickOneOf";
  /** A choice in a dropdown, native or built by the site, chosen by name. */
  static final String SELECT_OPTION = "browser.selectOption";
  /** Choices made from a list the page or app shows at run time. */
  static final Set<String> CLICK_ITEMS = Set.of(CLICK_ITEM, "ui.clickItem");
  static final Set<String> FILL_FORMS = Set.of(FILL_FORM, "ui.fillForm");

  /** The input that names the item chosen, for an action that chooses from a list. */
  static String pickKey(String action) {
    if (CLICK_ITEMS.contains(action) || CLICK_ONE_OF.equals(action)
        || SELECT_OPTION.equals(action)) {
      return "item";
    }
    if (CHOOSE_OPTION.equals(action)) return "option";
    return null;
  }

  /** The action that reads the list a choosing action chooses from. */
  static String listAction(String action) {
    if (SELECT_OPTION.equals(action)) return "browser.listOptions";
    return action.startsWith("ui.") ? "ui.listItems" : "browser.listItems";
  }
  private static final int MAX_TEXT = 10_000;
  private static final int MAX_LIST = 100;
  private static final int MAX_DATA = 40;

  /** One value of the step, as the approval card shows it. */
  record Field(String key, String label, String type, JsonNode value, boolean required,
               String source, List<String> options, String error, String format) {

    Field(String key, String label, String type, JsonNode value, boolean required,
          String source, List<String> options, String error) {
      this(key, label, type, value, required, source, options, error, null);
    }

    Field(String key, String label, String type, JsonNode value, boolean required,
          String source, List<String> options) {
      this(key, label, type, value, required, source, options, null, null);
    }

    Field withError(String message) {
      return new Field(key, label, type, value, required, source, options, message, format);
    }

    Field withOptions(String newType, List<String> newOptions, boolean blank) {
      return new Field(key, label, newType, blank ? null : value, blank || required, source,
          newOptions, error, format);
    }

    /** A date field that writes its value the site's way (DD/MM/YYYY, YYYY-MM-DD HH:mm). */
    Field withFormat(String newFormat) {
      if (newFormat == null || newFormat.isBlank()) return this;
      String newType = "date".equals(type) && hasTime(newFormat) ? "datetime" : type;
      return new Field(key, label, newType, value, required, source, options, error, newFormat);
    }

    boolean missing() { return required && empty(value); }

    ObjectNode toJson() {
      ObjectNode out = Json.obj().put("key", key).put("label", label).put("type", type)
          .put("required", required).put("missing", missing());
      if (value != null && !value.isMissingNode() && !value.isNull()) out.set("value", value);
      if (source != null) out.put("source", source);
      if (error != null) out.put("error", error);
      if (format != null) out.put("format", format);
      if (options != null && "rows".equals(type)) {
        // A rows field's options are its columns: one entry's values by key, with labels.
        ArrayNode columns = out.putArray("columns");
        for (String option : options) {
          int at = option.indexOf(COLUMN_SEP);
          columns.addObject().put("key", at < 0 ? option : option.substring(0, at))
              .put("label", at < 0 ? option : option.substring(at + 1));
        }
      } else if (options != null) {
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
          "ui.clickItem", CLICK_ONE_OF, SELECT_OPTION -> {
        for (String key : List.of("text", "value", "item", "date", "option")) {
          JsonNode value = inputs.get(key);
          if (value != null && (value.isNumber() || value.isBoolean())) {
            inputs.put(key, value.asText());
          }
        }
      }
      case FILL_ROWS -> {
        JsonNode rows = entries(inputs.get("rows"));
        if (rows != null) inputs.set("rows", rows);
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
            template(source == null ? null : source.get("value")), null)
            .withFormat(field.path("format").asText(null)));
        index++;
      }
      return out;
    }

    if (FILL_ROWS.equals(spec.action())) {
      List<String> columns = new ArrayList<>();
      for (JsonNode column : inputs.path("columns")) {
        String key = column.path("key").asText("");
        if (!key.isEmpty()) columns.add(key + COLUMN_SEP + column.path("label").asText(key));
      }
      String source = template(configured.get("rows"));
      Matcher named = source == null ? null : INPUT_REF.matcher(source.strip());
      String label = named != null && named.matches() ? label(named.group(1), FILL_ROWS) : "Rows";
      out.add(new Field("rows", label, "rows", inputs.get("rows"), true, source, columns));
      return out;
    }
    JsonNode schema = spec.input() == null ? Json.obj() : spec.input();
    JsonNode properties = schema.path("properties");
    Set<String> required = new LinkedHashSet<>();
    schema.path("required").forEach(r -> required.add(r.asText()));
    // Values marked mandatory while recording: the card waits for them to be filled in.
    config.path("requiredInputs").forEach(r -> required.add(r.asText()));
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
      // A dropdown's items read while recording are its choices too.
      List<String> buttons = (CLICK_ONE_OF.equals(spec.action())
          || SELECT_OPTION.equals(spec.action())) && "item".equals(key)
          ? optionNames(inputs.path("options")) : null;
      List<String> options = pick ? choices : buttons != null ? buttons : enumOptions(property);
      String type = pick || buttons != null ? "select" : typeOf(spec.action(), key, property);
      String name = SELECT_OPTION.equals(spec.action()) && "item".equals(key)
          ? dropdownName(inputs) : label(key, spec.action());
      Field field = new Field(key, name, type, inputs.get(key),
          required.contains(key), template(configured.get(key)), options);
      out.add("date".equals(type) ? field.withFormat(dateFormat(inputs, key)) : field);
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
        || SELECT_OPTION.equals(spec.action()) ? named(out, config.path("list")) : out;
  }

  /** What a dropdown step's field is called: the dropdown's own label, else "Option to choose". */
  private static String dropdownName(JsonNode inputs) {
    String label = inputs.path("element").path("label").asText("").strip();
    return label.isEmpty() || label.length() > 60 ? "Option to choose" : label;
  }

  /**
   * The format a date field is written in on the site: the step's own, else read from a recorded
   * ISO value (with a time, 2026-11-14T18:30, it is a date and time).
   */
  private static String dateFormat(JsonNode inputs, String key) {
    String format = inputs.path("format").asText("").strip();
    if (!format.isEmpty()) return format;
    return inputs.path(key).asText("").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*")
        ? "YYYY-MM-DDTHH:mm" : null;
  }

  /** Whether a date format has a time of day in it. */
  static boolean hasTime(String format) {
    return format != null && format.matches(".*[Hh].*");
  }

  /**
   * The names of a grouped-buttons step's buttons, or a dropdown step's items, or null when it
   * has none. Plain text items are taken as they are.
   */
  static List<String> optionNames(JsonNode options) {
    if (!options.isArray() || options.isEmpty()) return null;
    List<String> names = new ArrayList<>();
    for (JsonNode option : options) {
      String name = option.isTextual() ? option.asText("").strip()
          : option.path("name").asText("");
      if (!name.isBlank()) names.add(name);
    }
    return names.isEmpty() ? null : names;
  }

  /** The types a detail question may take; anything else is asked as plain text. */
  private static final Set<String> DETAIL_TYPES = Set.of("text", "number", "checkbox", "select",
      "date", "datetime");
  private static final int MAX_DETAILS = 20;

  /**
   * The notes and questions the person attached to this step while building it (config
   * {@code details.blocks}), as extra fields on the approval card: a note is shown as it is, and
   * a question must be answered before the step goes on. Answers are kept as the step's
   * {@code details} output, never sent to the site.
   */
  static List<Field> detailFields(JsonNode config, JsonNode inputs) {
    JsonNode blocks = config.path("details").path("blocks");
    if (!blocks.isArray() || blocks.isEmpty()) return List.of();
    List<Field> out = new ArrayList<>();
    int index = 0;
    for (JsonNode block : blocks) {
      if (out.size() >= MAX_DETAILS) break;
      int at = index++;
      if ("note".equals(block.path("kind").asText("field"))) {
        String text = block.path("text").asText("").strip();
        if (!text.isEmpty()) {
          out.add(new Field("_note." + at, text.length() > 500 ? text.substring(0, 500) : text,
              "note", null, false, null, null));
        }
        continue;
      }
      String name = detailName(block);
      if (name == null) continue;
      String label = block.path("label").asText("").strip();
      String type = block.path("type").asText("text");
      if (!DETAIL_TYPES.contains(type)) type = "text";
      List<String> options = optionNames(block.path("options"));
      if ("select".equals(type) && options == null) type = "text";
      Field field = new Field("details." + name, label.isEmpty() ? name : label, type,
          inputs.path("details").get(name), block.path("required").asBoolean(true), null,
          "select".equals(type) ? options : null);
      out.add(field.withFormat(block.path("format").asText(null)));
    }
    return out;
  }

  /** A detail question's key, from its name: letters, digits and underscores only. */
  static String detailName(JsonNode block) {
    String name = block.path("name").asText("").strip().replaceAll("[^A-Za-z0-9_]", "_");
    if (name.isEmpty() || name.chars().allMatch(c -> c == '_')) return null;
    return name.length() > 60 ? name.substring(0, 60) : name;
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
          f.source(), f.options(), f.error(), f.format()));
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
  /**
   * The entries of a repeated section as a list: given as JSON text (a run form, the card's
   * table) or as a list, with entries that have nothing filled in dropped. Null when it is
   * neither.
   */
  static ArrayNode entries(JsonNode rows) {
    if (rows != null && rows.isTextual()) {
      try {
        rows = Json.mapper().readTree(rows.asText().isBlank() ? "[]" : rows.asText());
      } catch (Exception e) {
        return null;
      }
    }
    if (rows == null || !rows.isArray() || rows.size() > MAX_LIST) return null;
    ArrayNode kept = Json.mapper().createArrayNode();
    for (JsonNode row : rows) {
      if (!row.isObject()) continue;
      boolean any = false;
      for (JsonNode value : row) any |= !empty(value);
      if (any) kept.add(row);
    }
    return kept;
  }

  static void apply(ObjectNode inputs, JsonNode edits, Set<String> offered) {
    if (edits == null || !edits.isObject()) return;
    for (Iterator<Map.Entry<String, JsonNode>> it = edits.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> edit = it.next();
      if (!offered.contains(edit.getKey()) || edit.getKey().startsWith("_")) continue;
      // A repeated section's table: its entries, as a list of rows.
      if ("rows".equals(edit.getKey()) && inputs.has("columns")) {
        ArrayNode rows = entries(edit.getValue());
        if (rows != null) inputs.set("rows", rows);
        continue;
      }
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
