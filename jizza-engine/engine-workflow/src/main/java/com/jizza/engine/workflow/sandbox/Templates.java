package com.jizza.engine.workflow.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text with {@code {{ expression }}} placeholders, the way Zapier and Zoho Flow map one step's
 * data into another's fields. Each placeholder is a sandboxed expression over the run's data
 * ({@code input}, {@code nodes}, {@code vars}, {@code instructions}, and the {@code fmt:}
 * functions); text values go in as they are, anything else as JSON, and null as nothing.
 */
public final class Templates {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.+?)}}", Pattern.DOTALL);

  private Templates() {}

  public static String render(String template, JexlSandbox sandbox, Map<String, Object> variables) {
    if (template == null || !template.contains("{{")) return template;
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      JsonNode value = sandbox.evaluate(m.group(1).strip(), variables);
      String text = value == null || value.isNull() || value.isMissingNode() ? ""
          : value.isValueNode() ? value.asText() : value.toString();
      m.appendReplacement(out, Matcher.quoteReplacement(text));
    }
    m.appendTail(out);
    return out.toString();
  }
}
