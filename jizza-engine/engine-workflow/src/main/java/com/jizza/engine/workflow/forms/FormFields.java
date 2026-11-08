package com.jizza.engine.workflow.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.StepOverrides;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A form's fields: checking what the builder saves, and checking what someone submits.
 *
 * <p>A form is a list of fields. A {@code step_layout} holds the fields made from one workflow
 * step, in {@code children}; such a field has a {@code binding} to the step setting it fills and
 * is {@code pending} until the person accepts it. Pending fields never reach the public form.
 */
public final class FormFields {

  /** Fields people fill in, by type. */
  static final Set<String> INPUT_TYPES = Set.of(
      "name", "email", "address", "phone", "single_line", "multi_line", "number", "date", "time",
      "dropdown", "radio", "multi_select", "checkbox",
      "decision", "rich_text", "url", "image", "percent", "currency", "decimal", "datetime",
      "subform", "lookup", "signature", "file", "audio", "video", "qr_code");
  /** Fields Jizza fills in when the form is submitted. */
  static final Set<String> COMPUTED_TYPES = Set.of("auto_number", "formula");
  /** Fields that only lay the form out. */
  static final Set<String> LAYOUT_TYPES = Set.of("section", "note");
  static final String STEP_LAYOUT = "step_layout";

  private static final Set<String> CHOICE_TYPES = Set.of("dropdown", "radio", "multi_select", "checkbox");
  private static final Set<String> FILE_TYPES = Set.of("image", "signature", "file", "audio", "video");
  private static final Set<String> SUBFORM_COLUMN_TYPES =
      Set.of("single_line", "multi_line", "number", "email", "date", "decision");
  private static final Set<String> MODES = Set.of("template", "expression", "input");

  static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
  private static final Pattern EMAIL = Pattern.compile("[^\\s@]{1,64}@[^\\s@]{1,255}\\.[^\\s@]{1,63}");
  private static final Pattern PHONE = Pattern.compile("[+0-9 ().\\-]{4,32}");
  private static final Pattern URL = Pattern.compile("https?://[^\\s]{1,2000}", Pattern.CASE_INSENSITIVE);
  private static final Pattern DATA_URL = Pattern.compile("data:([a-zA-Z0-9.+\\-]+/[a-zA-Z0-9.+\\-]+);base64,[A-Za-z0-9+/=\\s]+");

  static final int MAX_FIELDS = 200;
  static final int MAX_DEFINITION_BYTES = 256 * 1024;
  static final int MAX_TEXT = 10_000;
  static final int MAX_RICH_TEXT = 50_000;
  /** Base64 characters per file: about 3 MB of file. */
  static final int MAX_FILE_CHARS = 4_000_000;
  static final int MAX_SUBMISSION_CHARS = 12_000_000;
  static final int MAX_SUBFORM_ROWS = 50;

  private FormFields() {}

  /** The fields as saved: checked, with ids and names unique. */
  static JsonNode checkDefinition(JsonNode fields) {
    if (fields == null || fields.isNull()) return Json.mapper().createArrayNode();
    if (!fields.isArray()) throw new ValidationException("A form's fields are a list");
    if (Json.write(fields).length() > MAX_DEFINITION_BYTES) {
      throw new ValidationException("The form is too large; split it into smaller forms");
    }
    Set<String> ids = new HashSet<>();
    Set<String> names = new HashSet<>();
    int[] count = {0};
    for (JsonNode f : fields) {
      checkField(f, false, ids, names, count);
    }
    return fields;
  }

  private static void checkField(JsonNode f, boolean inLayout, Set<String> ids, Set<String> names,
                                 int[] count) {
    if (!f.isObject()) throw new ValidationException("Each field must be an object");
    if (++count[0] > MAX_FIELDS) throw new ValidationException("A form can have at most " + MAX_FIELDS + " fields");
    String id = f.path("id").asText("");
    if (id.isBlank() || id.length() > 64 || !ids.add(id)) {
      throw new ValidationException("Each field needs its own id");
    }
    String type = f.path("type").asText("");
    String label = f.path("label").asText("");
    if (label.length() > 300) throw new ValidationException("A field's label can be at most 300 characters");
    if (STEP_LAYOUT.equals(type)) {
      if (inLayout) throw new ValidationException("A step layout cannot hold another one");
      JsonNode children = f.path("children");
      if (!children.isArray()) throw new ValidationException("A step layout holds a list of fields");
      for (JsonNode child : children) checkField(child, true, ids, names, count);
      return;
    }
    if (LAYOUT_TYPES.contains(type)) {
      if (f.path("content").asText("").length() > MAX_TEXT) {
        throw new ValidationException("A note can be at most " + MAX_TEXT + " characters");
      }
      return;
    }
    if (!INPUT_TYPES.contains(type) && !COMPUTED_TYPES.contains(type)) {
      throw new ValidationException("Unknown field type '" + type + "'");
    }
    String name = f.path("name").asText("");
    if (!NAME.matcher(name).matches()) {
      throw new ValidationException("The field '" + label + "' needs a link name of letters, digits "
          + "and _, starting with a letter");
    }
    if (name.startsWith("_")) throw new ValidationException("Link names cannot start with _");
    if (!names.add(name)) throw new ValidationException("Two fields have the link name '" + name + "'");
    if (CHOICE_TYPES.contains(type)) {
      JsonNode choices = f.path("choices");
      if (!choices.isArray() || choices.isEmpty()) {
        throw new ValidationException("The field '" + label + "' needs at least one choice");
      }
      if (choices.size() > 200) throw new ValidationException("A field can have at most 200 choices");
      for (JsonNode c : choices) {
        if (!c.isTextual() || c.asText().isBlank() || c.asText().length() > 300) {
          throw new ValidationException("Each choice of '" + label + "' must be text");
        }
      }
    }
    if ("formula".equals(type) && f.path("formula").asText("").length() > 2000) {
      throw new ValidationException("A formula can be at most 2000 characters");
    }
    if ("subform".equals(type)) {
      JsonNode columns = f.path("columns");
      if (!columns.isArray() || columns.isEmpty() || columns.size() > 20) {
        throw new ValidationException("The subform '" + label + "' needs between 1 and 20 columns");
      }
      Set<String> cols = new HashSet<>();
      for (JsonNode c : columns) {
        if (!NAME.matcher(c.path("name").asText("")).matches() || !cols.add(c.path("name").asText())) {
          throw new ValidationException("Each column of '" + label + "' needs its own link name");
        }
        if (!SUBFORM_COLUMN_TYPES.contains(c.path("type").asText("single_line"))) {
          throw new ValidationException("Unknown column type in '" + label + "'");
        }
      }
    }
    JsonNode binding = f.get("binding");
    if (binding != null && !binding.isNull()) {
      if (!binding.isObject() || !MODES.contains(binding.path("mode").asText(""))
          || binding.path("workflowId").asText("").isBlank()
          || binding.path("nodeId").asText("").isBlank()) {
        throw new ValidationException("The field '" + label + "' is linked to a step in a way Jizza can't read");
      }
      if (!"input".equals(binding.path("mode").asText())
          && !StepOverrides.allowedPath(binding.path("path"))) {
        throw new ValidationException("The field '" + label + "' fills a step setting that can't be filled from a form");
      }
    }
  }

  /** Every field, the step layouts' children included, in order. */
  static List<JsonNode> flatten(JsonNode fields) {
    List<JsonNode> out = new ArrayList<>();
    if (fields == null || !fields.isArray()) return out;
    for (JsonNode f : fields) {
      if (STEP_LAYOUT.equals(f.path("type").asText())) {
        for (JsonNode child : f.path("children")) out.add(child);
      } else {
        out.add(f);
      }
    }
    return out;
  }

  static boolean pending(JsonNode f) {
    return f.path("pending").asBoolean(false);
  }

  /** The fields a stranger sees: accepted ones only, without links to the owner's workflows. */
  static ArrayNode publicFields(JsonNode fields, Map<String, List<String>> lookupOptions) {
    ArrayNode out = Json.mapper().createArrayNode();
    if (fields == null || !fields.isArray()) return out;
    for (JsonNode f : fields) {
      if (pending(f)) continue;
      ObjectNode copy = ((ObjectNode) f).deepCopy();
      copy.remove("binding");
      copy.remove("pending");
      copy.remove("workflowId");
      copy.remove("nodeId");
      copy.remove("lookup");
      copy.remove("stepValue");
      copy.remove("workflowName");
      copy.remove("stepLabel");
      if ("formula".equals(f.path("type").asText())) copy.remove("formula");
      if ("lookup".equals(f.path("type").asText())) {
        ArrayNode choices = copy.putArray("choices");
        lookupOptions.getOrDefault(f.path("id").asText(), List.of()).forEach(choices::add);
      }
      if (STEP_LAYOUT.equals(f.path("type").asText())) {
        copy.set("children", publicFields(f.path("children"), lookupOptions));
        if (copy.path("children").isEmpty()) continue;
      }
      out.add(copy);
    }
    return out;
  }

  /**
   * What was submitted, checked against the fields: values typed, unknown keys dropped, required
   * fields present. Computed fields are left for the caller.
   *
   * @param lookupOptions the choices of each lookup field, by field id
   */
  static ObjectNode checkSubmission(JsonNode fields, JsonNode data,
                                    Map<String, List<String>> lookupOptions) {
    if (data == null || data.isNull()) data = Json.obj();
    if (!data.isObject()) throw new ValidationException("Send the answers as named fields");
    if (Json.write(data).length() > MAX_SUBMISSION_CHARS) {
      throw new ValidationException("The answers are too large. Try smaller files.");
    }
    ObjectNode out = Json.obj();
    Map<String, String> problems = new LinkedHashMap<>();
    for (JsonNode f : flatten(fields)) {
      String type = f.path("type").asText();
      if (pending(f) || !INPUT_TYPES.contains(type)) continue;
      String name = f.path("name").asText();
      String label = f.path("label").asText(name);
      JsonNode value = data.get(name);
      if (blank(value)) {
        if (f.path("required").asBoolean(false)) problems.put(name, label + " is required");
        continue;
      }
      try {
        JsonNode checked = checkValue(f, type, value,
            lookupOptions.getOrDefault(f.path("id").asText(), List.of()));
        if (checked != null && !checked.isNull()) out.set(name, checked);
        else if (f.path("required").asBoolean(false)) problems.put(name, label + " is required");
      } catch (IllegalArgumentException e) {
        problems.put(name, label + ": " + e.getMessage());
      }
    }
    if (!problems.isEmpty()) {
      throw new ValidationException(String.join("\n", problems.values()));
    }
    return out;
  }

  private static JsonNode checkValue(JsonNode f, String type, JsonNode value, List<String> lookup) {
    return switch (type) {
      case "name" -> {
        if (value.isObject()) {
          ObjectNode n = Json.obj();
          for (String k : List.of("first", "last")) {
            String v = text(value.get(k), 200);
            if (v != null) n.put(k, v);
          }
          yield n.isEmpty() ? null : n;
        }
        yield Json.mapper().valueToTree(text(value, 300));
      }
      case "address" -> {
        if (value.isObject()) {
          ObjectNode a = Json.obj();
          for (String k : List.of("line1", "line2", "city", "state", "postalCode", "country")) {
            String v = text(value.get(k), 300);
            if (v != null) a.put(k, v);
          }
          yield a.isEmpty() ? null : a;
        }
        yield Json.mapper().valueToTree(text(value, 1000));
      }
      case "single_line", "qr_code" -> {
        int max = f.path("maxLength").asInt(0);
        yield Json.mapper().valueToTree(text(value, max > 0 ? Math.min(max, MAX_TEXT) : 1000));
      }
      case "multi_line" -> Json.mapper().valueToTree(text(value, MAX_TEXT));
      case "rich_text" -> Json.mapper().valueToTree(text(value, MAX_RICH_TEXT));
      case "email" -> {
        String v = text(value, 320);
        if (!EMAIL.matcher(v).matches()) throw new IllegalArgumentException("enter an email address");
        yield Json.mapper().valueToTree(v);
      }
      case "phone" -> {
        String v = text(value, 32);
        if (!PHONE.matcher(v).matches()) throw new IllegalArgumentException("enter a phone number");
        yield Json.mapper().valueToTree(v);
      }
      case "url" -> {
        String v = text(value, 2000);
        if (!URL.matcher(v).matches()) throw new IllegalArgumentException("enter a link starting with http:// or https://");
        yield Json.mapper().valueToTree(v);
      }
      case "number", "decimal", "percent", "currency" -> {
        BigDecimal n = number(value);
        if ("number".equals(type) && n.stripTrailingZeros().scale() > 0) {
          throw new IllegalArgumentException("enter a whole number");
        }
        if (f.has("min") && f.get("min").isNumber() && n.compareTo(f.get("min").decimalValue()) < 0) {
          throw new IllegalArgumentException("must be at least " + f.get("min").asText());
        }
        if (f.has("max") && f.get("max").isNumber() && n.compareTo(f.get("max").decimalValue()) > 0) {
          throw new IllegalArgumentException("must be at most " + f.get("max").asText());
        }
        yield Json.mapper().valueToTree(n);
      }
      case "date" -> {
        String v = text(value, 10);
        try {
          LocalDate.parse(v);
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("enter a date");
        }
        yield Json.mapper().valueToTree(v);
      }
      case "time" -> {
        String v = text(value, 8);
        try {
          LocalTime.parse(v);
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("enter a time");
        }
        yield Json.mapper().valueToTree(v);
      }
      case "datetime" -> {
        String v = text(value, 40);
        try {
          if (v.length() > 19) OffsetDateTime.parse(v);
          else LocalDateTime.parse(v);
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("enter a date and time");
        }
        yield Json.mapper().valueToTree(v);
      }
      case "dropdown", "radio" -> {
        String v = text(value, 300);
        if (!choices(f).contains(v)) throw new IllegalArgumentException("pick one of the choices");
        yield Json.mapper().valueToTree(v);
      }
      case "lookup" -> {
        String v = text(value, 300);
        if (!lookup.contains(v)) throw new IllegalArgumentException("pick one of the choices");
        yield Json.mapper().valueToTree(v);
      }
      case "multi_select", "checkbox" -> {
        if (!value.isArray()) throw new IllegalArgumentException("pick from the choices");
        List<String> allowed = choices(f);
        ArrayNode picked = Json.mapper().createArrayNode();
        for (JsonNode v : value) {
          if (!v.isTextual() || !allowed.contains(v.asText())) {
            throw new IllegalArgumentException("pick from the choices");
          }
          picked.add(v.asText());
        }
        yield picked.isEmpty() ? null : picked;
      }
      case "decision" -> {
        boolean on = value.isBoolean() ? value.asBoolean() : "true".equalsIgnoreCase(value.asText());
        if (!on && f.path("required").asBoolean(false)) yield null;
        yield Json.mapper().valueToTree(on);
      }
      case "image", "signature", "file", "audio", "video" -> file(type, value);
      case "subform" -> subform(f, value);
      default -> throw new IllegalArgumentException("can't be filled in");
    };
  }

  private static JsonNode file(String type, JsonNode value) {
    if (!value.isObject()) throw new IllegalArgumentException("attach a file");
    String dataUrl = value.path("dataUrl").asText("");
    if (dataUrl.length() > MAX_FILE_CHARS) throw new IllegalArgumentException("the file is larger than 3 MB");
    var m = DATA_URL.matcher(dataUrl);
    if (!m.matches()) throw new IllegalArgumentException("attach a file");
    String mime = m.group(1).toLowerCase(java.util.Locale.ROOT);
    boolean ok = switch (type) {
      case "image", "signature" -> mime.startsWith("image/") && !mime.equals("image/svg+xml");
      case "audio" -> mime.startsWith("audio/");
      case "video" -> mime.startsWith("video/");
      default -> true;
    };
    if (!ok) throw new IllegalArgumentException("that kind of file can't go here");
    String name = text(value.get("name"), 255);
    ObjectNode out = Json.obj().put("name", name == null ? type : name).put("type", mime)
        .put("size", (long) (dataUrl.length() - dataUrl.indexOf(',') - 1) * 3 / 4);
    out.put("dataUrl", dataUrl);
    return out;
  }

  private static JsonNode subform(JsonNode f, JsonNode value) {
    if (!value.isArray()) throw new IllegalArgumentException("add rows");
    if (value.size() > MAX_SUBFORM_ROWS) throw new IllegalArgumentException("at most " + MAX_SUBFORM_ROWS + " rows");
    ArrayNode rows = Json.mapper().createArrayNode();
    for (JsonNode row : value) {
      if (!row.isObject()) throw new IllegalArgumentException("each row is a set of named values");
      ObjectNode r = Json.obj();
      for (JsonNode c : f.path("columns")) {
        String name = c.path("name").asText();
        JsonNode v = row.get(name);
        if (blank(v)) continue;
        String colType = c.path("type").asText("single_line");
        r.set(name, checkValue(c, colType, v, List.of()));
      }
      if (!r.isEmpty()) rows.add(r);
    }
    return rows.isEmpty() ? null : rows;
  }

  private static List<String> choices(JsonNode f) {
    List<String> out = new ArrayList<>();
    f.path("choices").forEach(c -> out.add(c.asText()));
    return out;
  }

  private static BigDecimal number(JsonNode value) {
    try {
      if (value.isNumber()) return value.decimalValue();
      return new BigDecimal(value.asText().strip().replace(",", ""));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("enter a number");
    }
  }

  private static String text(JsonNode value, int max) {
    if (value == null || value.isNull()) return null;
    if (value.isContainerNode()) throw new IllegalArgumentException("enter text");
    String v = value.asText().strip();
    if (v.isEmpty()) return null;
    if (v.length() > max) throw new IllegalArgumentException("can be at most " + max + " characters");
    return v;
  }

  static boolean blank(JsonNode v) {
    return v == null || v.isNull() || v.isMissingNode()
        || (v.isTextual() && v.asText().isBlank())
        || (v.isContainerNode() && v.isEmpty());
  }
}
