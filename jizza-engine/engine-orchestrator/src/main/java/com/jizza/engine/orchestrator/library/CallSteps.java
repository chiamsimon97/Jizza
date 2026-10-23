package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.agents.registry.ServiceCallJournal;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Turns the service calls a conversation made into workflow steps that make the same calls.
 *
 * <p>This is what lets a job done in a chat be saved as fixed, built-in steps (Zapier-style)
 * rather than as instructions an agent works out again on every run: the step carries the tool
 * and the very arguments the chat used, with the values the user will change from run to run
 * swapped for {@code {{ input.<name> }}}. Only a connected service's own actions can run as a
 * step; anything else (an MCP tool, a built-in action) stays with an agent.
 */
@Component
public class CallSteps {

  private static final Pattern INPUT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,40}");

  private final ObjectProvider<ServiceCallJournal> journal;
  private final GeneratedToolRepository tools;

  public CallSteps(ObjectProvider<ServiceCallJournal> journal, GeneratedToolRepository tools) {
    this.journal = journal;
    this.tools = tools;
  }

  /** One call the conversation made, numbered from 1, and the action that can repeat it, if any. */
  public record Listed(int number, ServiceCallJournal.Call call, Optional<GeneratedToolEntity> action) {}

  /** A step that makes a recorded call again, and the inputs it reads. */
  public record Step(GeneratedToolEntity action, ServiceCallJournal.Call call, ObjectNode arguments,
                     Set<String> inputs) {}

  /** The calls made in the conversation the current job belongs to, oldest first. */
  public List<Listed> recent(UUID userId) {
    ServiceCallJournal j = journal.getIfAvailable();
    UUID conversationId = Activity.current().map(ActivitySink::conversationId).orElse(null);
    if (j == null || conversationId == null) return List.of();
    List<ServiceCallJournal.Call> calls = j.recent(conversationId);
    if (calls.isEmpty()) return List.of();
    List<GeneratedToolEntity> actions = tools.findEnabledForUser(userId);
    List<Listed> out = new ArrayList<>();
    for (int i = 0; i < calls.size(); i++) {
      String tool = calls.get(i).tool();
      out.add(new Listed(i + 1, calls.get(i),
          actions.stream().filter(a -> a.getName().equals(tool)).findFirst()));
    }
    return out;
  }

  /**
   * The step for call {@code number}: its arguments as made, then {@code overrides} on top (for
   * values taken from an earlier step), then each argument named in {@code inputs} read from the
   * run's input instead.
   *
   * @throws IllegalArgumentException with a message for Jizza when the step cannot be built
   */
  public Step step(UUID userId, int number, JsonNode overrides, JsonNode inputs) {
    List<Listed> calls = recent(userId);
    if (calls.isEmpty()) {
      throw new IllegalArgumentException("No service calls were made in this conversation, so "
          + "there is nothing to repeat as a step");
    }
    if (number < 1 || number > calls.size()) {
      throw new IllegalArgumentException("There is no call " + number + "; recent_service_calls "
          + "lists 1 to " + calls.size());
    }
    Listed listed = calls.get(number - 1);
    if (listed.action().isEmpty()) {
      throw new IllegalArgumentException("'" + listed.call().tool() + "' is not one of the user's "
          + "service actions, so it cannot run as a step. Use an agent step for it");
    }
    ObjectNode arguments = listed.call().arguments() != null && listed.call().arguments().isObject()
        ? (ObjectNode) listed.call().arguments().deepCopy() : Json.obj();
    if (overrides != null && overrides.isObject()) {
      overrides.fields().forEachRemaining(f -> set(arguments, f.getKey(), f.getValue()));
    }
    Set<String> used = new LinkedHashSet<>();
    if (inputs != null && inputs.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> it = inputs.fields();
      while (it.hasNext()) {
        var field = it.next();
        String input = field.getValue().asText("").strip();
        if (!INPUT_NAME.matcher(input).matches()) {
          throw new IllegalArgumentException("'" + input + "' is not a usable input name; use "
              + "letters, digits and underscores");
        }
        set(arguments, field.getKey(), Json.mapper().getNodeFactory()
            .textNode("{{ input." + input + " }}"));
        used.add(input);
      }
    }
    return new Step(listed.action().get(), listed.call(), arguments, used);
  }

  /** Sets {@code path} (dots for nested fields) in {@code target}, creating objects on the way. */
  static void set(ObjectNode target, String path, JsonNode value) {
    String[] parts = path.split("\\.");
    ObjectNode at = target;
    for (int i = 0; i < parts.length - 1; i++) {
      JsonNode next = at.path(parts[i]);
      at = next.isObject() ? (ObjectNode) next : at.putObject(parts[i]);
    }
    at.set(parts[parts.length - 1], value);
  }
}
