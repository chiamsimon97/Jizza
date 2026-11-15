package com.jizza.engine.api.workflow.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The checks a workflow input can declare beyond its type, applied wherever a run gets values:
 * the run form, chat, a schedule, a sub-workflow. All of them are optional fields on the input's
 * declaration, so an input without them behaves exactly as before.
 *
 * <ul>
 *   <li>{@code options} (with optional {@code synonyms}): the value must be one of them. A value
 *       is matched exactly, then ignoring case and spacing, then through the synonyms, so "3A",
 *       "ac 3 tier" and a synonym {@code {"Third AC": "AC 3 Tier (3A)"}} all land on the option
 *       the site shows.</li>
 *   <li>{@code min} / {@code max} for numbers; {@code pattern} and {@code maxLength} for text.</li>
 *   <li>{@code minRows} / {@code maxRows} for a list of entries, and per-column checks from
 *       {@code columns} ({@code required}, {@code options}, {@code min}, {@code max}), so six
 *       passengers on a form that takes four is refused before anything touches a page.</li>
 * </ul>
 */
public final class InputRules {

  private static final int SHOWN_OPTIONS = 8;

  private InputRules() {}

  /** The value checked and normalised against the input's declared rules. */
  public static JsonNode apply(String name, JsonNode value, JsonNode declaration) {
    if (declaration == null || !declaration.isObject()) return value;
    JsonNode out = value;
    if (declaration.path("options").isArray() && declaration.get("options").size() > 0
        && out.isValueNode()) {
      out = Json.toNode(matchOption(name, out.asText(), declaration));
    }
    checkNumber(name, out, declaration);
    checkText(name, out, declaration);
    if (out.isArray()) out = checkRows(name, (ArrayNode) out, declaration);
    return out;
  }

  /** The option the value means, or a refusal naming the choices. */
  private static String matchOption(String name, String given, JsonNode declaration) {
    List<String> options = new ArrayList<>();
    declaration.get("options").forEach(o -> {
      if (o.isValueNode()) options.add(o.asText());
    });
    String folded = fold(given);
    // Exact, then ignoring case and spacing.
    for (String option : options) {
      if (option.equals(given)) return option;
    }
    for (String option : options) {
      if (fold(option).equals(folded)) return option;
    }
    // The declaration's synonyms ("Third AC" -> "AC 3 Tier (3A)"), matched the same way.
    JsonNode synonyms = declaration.path("synonyms");
    if (synonyms.isObject()) {
      var it = synonyms.fields();
      while (it.hasNext()) {
        var entry = it.next();
        if (fold(entry.getKey()).equals(folded) && entry.getValue().isValueNode()) {
          // Only to an option itself, never on through another synonym.
          String target = fold(entry.getValue().asText());
          for (String option : options) {
            if (fold(option).equals(target)) return option;
          }
        }
      }
    }
    // One option that starts with or contains the words given, when only one does.
    List<String> close = options.stream().filter(o -> fold(o).startsWith(folded)).toList();
    if (close.size() != 1) {
      close = options.stream().filter(o -> fold(o).contains(folded)).toList();
    }
    if (close.size() == 1) return close.get(0);
    String shown = String.join(", ", options.subList(0, Math.min(options.size(), SHOWN_OPTIONS)));
    throw new ValidationException("'" + given + "' is not one of the choices for '" + name + "': "
        + shown + (options.size() > SHOWN_OPTIONS ? ", …" : ""));
  }

  private static String fold(String text) {
    return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
  }

  private static void checkNumber(String name, JsonNode value, JsonNode declaration) {
    if (!value.isNumber()) return;
    double v = value.asDouble();
    if (declaration.path("min").isNumber() && v < declaration.get("min").asDouble()) {
      throw new ValidationException("'" + name + "' must be at least " + declaration.get("min").asText());
    }
    if (declaration.path("max").isNumber() && v > declaration.get("max").asDouble()) {
      throw new ValidationException("'" + name + "' can be at most " + declaration.get("max").asText());
    }
  }

  private static void checkText(String name, JsonNode value, JsonNode declaration) {
    if (!value.isTextual()) return;
    String text = value.asText();
    int maxLength = declaration.path("maxLength").asInt(0);
    if (maxLength > 0 && text.length() > maxLength) {
      throw new ValidationException("'" + name + "' can be at most " + maxLength + " characters");
    }
    String pattern = declaration.path("pattern").asText("");
    if (!pattern.isEmpty()) {
      try {
        if (!Pattern.compile(pattern).matcher(text).matches()) {
          String hint = declaration.path("patternHint").asText("");
          throw new ValidationException("'" + text + "' is not a valid " + name
              + (hint.isEmpty() ? "" : " (" + hint + ")"));
        }
      } catch (PatternSyntaxException e) {
        // A declaration with a broken pattern never blocks a run.
      }
    }
  }

  /** Entries of a rows input: how many, and each row's columns by their own rules. */
  private static ArrayNode checkRows(String name, ArrayNode rows, JsonNode declaration) {
    int minRows = declaration.path("minRows").asInt(0);
    int maxRows = declaration.path("maxRows").asInt(0);
    if (minRows > 0 && rows.size() < minRows) {
      throw new ValidationException("'" + name + "' needs at least " + minRows
          + (minRows == 1 ? " entry" : " entries"));
    }
    if (maxRows > 0 && rows.size() > maxRows) {
      throw new ValidationException("'" + name + "' can have at most " + maxRows
          + (maxRows == 1 ? " entry" : " entries") + ", not " + rows.size());
    }
    JsonNode columns = declaration.path("columns");
    if (!columns.isArray() || columns.size() == 0) return rows;
    ArrayNode out = Json.mapper().createArrayNode();
    int at = 0;
    for (JsonNode row : rows) {
      at++;
      if (!(row instanceof ObjectNode entry)) {
        out.add(row);
        continue;
      }
      ObjectNode checked = entry.deepCopy();
      for (JsonNode column : columns) {
        String key = column.path("key").asText(column.path("name").asText(""));
        if (key.isEmpty()) continue;
        String label = column.path("label").asText(key);
        JsonNode cell = checked.get(key);
        boolean empty = cell == null || cell.isNull()
            || (cell.isTextual() && cell.asText().isBlank());
        if (empty) {
          if (column.path("required").asBoolean(false)) {
            throw new ValidationException("Entry " + at + " of '" + name + "' needs '" + label + "'");
          }
          continue;
        }
        if (column.path("options").isArray() && column.get("options").size() > 0
            && cell.isValueNode()) {
          checked.put(key, matchOption(label, cell.asText(), column));
          cell = checked.get(key);
        }
        checkNumber("'" + label + "' of entry " + at, coerced(cell), column);
      }
      out.add(checked);
    }
    return out;
  }

  private static JsonNode coerced(JsonNode cell) {
    JsonNode asNumber = ModuleContract.coerce(cell, "number");
    return asNumber != null ? asNumber : cell;
  }
}
