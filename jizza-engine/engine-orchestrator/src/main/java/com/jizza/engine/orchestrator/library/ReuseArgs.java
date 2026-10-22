package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The {@code reuse} argument Jizza fills in when it offers to save a workflow, and its JSON form. */
public final class ReuseArgs {

  private ReuseArgs() {}

  public static ObjectNode schema() {
    ObjectNode input = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "name", JsonSchemas.string("Short identifier, e.g. city or recipient"),
        "description", JsonSchemas.string("What to give it, in a few words"),
        "required", JsonSchemas.bool("True when a run cannot go ahead without it"))),
        List.of("name"));
    ObjectNode guardrails = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "confirm_before_run", JsonSchemas.bool("Ask the user before each run from a chat. "
            + "Default true; suggest false only for something read-only the user runs often"),
        "allow_scheduled", JsonSchemas.bool("Let a scheduled task doing this job run it on its "
            + "own. Default false; the user decides on the card"),
        "max_runs_per_day", JsonSchemas.integer("A daily limit on reused runs, if one makes sense"),
        "rules", array("Conditions the user stated for this job, one short line each, e.g. "
            + "'Never email anyone outside the company'", Json.obj().put("type", "string")))),
        List.of());
    ObjectNode reuse = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "when_to_use", JsonSchemas.string("When this workflow is the right one, in the user's "
            + "terms, so a later request can be matched to it"),
        "examples", array("Two to five requests it handles, phrased as the user might ask",
            Json.obj().put("type", "string")),
        "inputs", array("What changes from one run to the next, passed in as "
            + "{{ input.<name> }}", input),
        "guardrails", guardrails)), List.of("when_to_use"));
    reuse.put("description", "Fill this in when you are offering to save something the user "
        + "asked you to do, so Jizza can recognise and reuse it next time");
    return reuse;
  }

  /** The profile Jizza drafted; null when it gave none. The user still sees and saves it. */
  public static ReuseProfile parse(JsonNode args) {
    if (args == null || !args.isObject() || args.path("when_to_use").asText("").isBlank()) return null;
    List<ReuseProfile.Input> inputs = new ArrayList<>();
    for (JsonNode i : args.path("inputs")) {
      String name = i.path("name").asText("").strip().replaceAll("[^A-Za-z0-9_]+", "_");
      if (!name.isEmpty()) {
        inputs.add(new ReuseProfile.Input(name, i.path("description").asText(null),
            i.path("required").asBoolean(false)));
      }
    }
    JsonNode g = args.path("guardrails");
    var guardrails = new ReuseProfile.Guardrails(g.path("confirm_before_run").asBoolean(true),
        g.path("allow_scheduled").asBoolean(false),
        g.path("max_runs_per_day").canConvertToInt() && g.path("max_runs_per_day").asInt() > 0
            ? g.path("max_runs_per_day").asInt() : null,
        strings(g.path("rules")), null);
    return new ReuseProfile(args.path("when_to_use").asText().strip(), strings(args.path("examples")),
        inputs, guardrails, null);
  }

  /** How the library describes a workflow to Jizza. */
  static ObjectNode describe(com.jizza.engine.api.workflow.library.WorkflowLibrary.Match m) {
    ObjectNode out = Json.obj().put("workflow_id", m.workflowId().toString()).put("name", m.name());
    if (m.description() != null) out.put("description", m.description());
    out.put("status", m.status().name().toLowerCase(java.util.Locale.ROOT));
    if (m.score() > 0) {
      out.put("fit", m.score());
      ArrayNode why = out.putArray("shared_words");
      m.why().forEach(why::add);
    }
    ReuseProfile p = m.profile();
    if (p == null) return out;
    if (p.summary() != null) out.put("when_to_use", p.summary());
    ArrayNode inputs = out.putArray("inputs");
    for (ReuseProfile.Input i : p.inputs()) {
      ObjectNode node = inputs.addObject().put("name", i.name()).put("required", i.required());
      if (i.description() != null) node.put("description", i.description());
    }
    ObjectNode g = out.putObject("guardrails")
        .put("confirm_before_run", p.guardrails().confirmBeforeRun())
        .put("allow_scheduled", p.guardrails().allowScheduled());
    if (p.guardrails().maxRunsPerDay() != null) g.put("max_runs_per_day", p.guardrails().maxRunsPerDay());
    ArrayNode rules = g.putArray("rules");
    p.guardrails().rules().forEach(rules::add);
    return out;
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : array) if (!n.asText("").isBlank()) out.add(n.asText().strip());
    return out;
  }

  private static ObjectNode array(String description, ObjectNode items) {
    ObjectNode node = Json.obj().put("type", "array").put("description", description);
    node.set("items", items);
    return node;
  }
}
