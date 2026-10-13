package com.jizza.engine.connectors.tools.service;

import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.persistence.entity.ServiceVariableEntity;
import com.jizza.engine.persistence.repository.ServiceVariableRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code {{var.name}}} references in a service's request templates.
 *
 * <p>Secret values are decrypted only here, on the way into an outbound request, the same way the
 * executor treats credentials: they never reach a model, a log or the client.
 */
@Component
public class ServiceVariables {

  static final Pattern REFERENCE = Pattern.compile("\\{\\{\\s*var\\.([A-Za-z0-9_]+)\\s*}}");

  private final ServiceVariableRepository variables;
  private final EncryptionService encryption;

  public ServiceVariables(ServiceVariableRepository variables, EncryptionService encryption) {
    this.variables = variables;
    this.encryption = encryption;
  }

  public Map<String, String> valuesFor(UUID serviceId) {
    Map<String, String> values = new HashMap<>();
    for (ServiceVariableEntity v : variables.findByConnectionIdOrderByNameAsc(serviceId)) {
      values.put(v.getName(), v.isSecret() ? encryption.decryptString(v.getSecretValue())
          : v.getValue());
    }
    return values;
  }

  /** Replaces every reference; an undefined variable is an error rather than an empty string. */
  public static String render(String template, Map<String, String> values) {
    if (template == null || !template.contains("{{")) return template;
    Matcher m = REFERENCE.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = values.get(m.group(1));
      if (value == null) {
        throw new ValidationException("Variable '" + m.group(1) + "' is not defined for this service");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
