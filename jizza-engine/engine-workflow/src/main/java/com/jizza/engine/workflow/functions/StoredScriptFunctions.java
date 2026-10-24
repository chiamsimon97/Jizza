package com.jizza.engine.workflow.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.FormulaPreview;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDraft;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;
import com.jizza.engine.api.workflow.functions.ScriptFunctions;
import com.jizza.engine.api.workflow.graph.ModuleContract;
import com.jizza.engine.persistence.entity.ScriptFunctionEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.ScriptFunctionRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.workflow.sandbox.FormulaRewriter;
import com.jizza.engine.workflow.sandbox.FunctionLookup;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link ScriptFunctions} in the database, run by the workflow sandbox. */
@Component
public class StoredScriptFunctions implements ScriptFunctions {

  /** The most functions one person keeps. */
  static final int MAX_FUNCTIONS = 500;
  static final int MAX_RETURN_FIELDS = 100;
  static final int MAX_DESCRIPTION = 2_000;

  /** Words a function can't be named: the script's own and what a call would be confused with. */
  private static final Set<String> RESERVED = Set.of("fn", "call", "fmt", "var", "if", "else", "for",
      "while", "do", "return", "function", "new", "true", "false", "null", "empty", "size", "and", "or",
      "not", "eq", "ne", "lt", "gt", "le", "ge", "div", "mod", "break", "continue", "input", "nodes", "vars");
  private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,4}");

  private final ScriptFunctionRepository functions;
  private final WorkflowRepository workflows;
  private final JexlSandbox sandbox;

  public StoredScriptFunctions(ScriptFunctionRepository functions, WorkflowRepository workflows, JexlSandbox sandbox) {
    this.functions = functions;
    this.workflows = workflows;
    this.sandbox = sandbox;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ScriptFunctionDto> list(UUID userId) {
    Map<UUID, List<String>> usedBy = usages(userId);
    return functions.findByUserIdOrderByNameAsc(userId).stream()
        .map(e -> dto(e, usedBy.getOrDefault(e.getId(), List.of()))).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ScriptFunctionDto> find(UUID functionId) {
    return functions.findById(functionId)
        .map(e -> dto(e, usages(e.getUserId()).getOrDefault(e.getId(), List.of())));
  }

  /** Without its usages: this is what a running script looks up. */
  @Override
  public Optional<ScriptFunctionDto> findByName(UUID userId, String name) {
    return functions.findByUserIdAndName(userId, name).map(e -> dto(e, List.of()));
  }

  /** How a script run for {@code userId} finds the functions it calls. */
  public FunctionLookup lookupFor(UUID userId) {
    Map<String, Optional<ScriptFunctionDto>> seen = new HashMap<>();
    return name -> seen.computeIfAbsent(name, n -> findByName(userId, n)).orElse(null);
  }

  @Override
  @Transactional
  public ScriptFunctionDto create(UUID userId, ScriptFunctionDraft draft) {
    if (functions.countByUserId(userId) >= MAX_FUNCTIONS) {
      throw new ValidationException("You can keep at most " + MAX_FUNCTIONS + " functions");
    }
    Clean clean = clean(draft);
    if (functions.existsByUserIdAndName(userId, clean.name())) {
      throw new ConflictException("You already have a function called " + clean.name());
    }
    ScriptFunctionEntity e = new ScriptFunctionEntity();
    e.setUserId(userId);
    apply(e, clean);
    return dto(functions.save(e), List.of());
  }

  @Override
  @Transactional
  public ScriptFunctionDto update(UUID functionId, ScriptFunctionDraft draft) {
    ScriptFunctionEntity e = functions.findById(functionId).orElseThrow(() -> new NotFoundException("Function", functionId));
    Clean clean = clean(draft);
    String old = e.getName();
    if (!old.equals(clean.name()) && functions.existsByUserIdAndName(e.getUserId(), clean.name())) {
      throw new ConflictException("You already have a function called " + clean.name());
    }
    apply(e, clean);
    functions.save(e);
    if (!old.equals(clean.name())) renameCalls(e.getUserId(), functionId, old, clean.name());
    return dto(e, usages(e.getUserId()).getOrDefault(functionId, List.of()));
  }

  @Override
  @Transactional
  public void delete(UUID functionId) {
    functions.deleteById(functionId);
  }

  @Override
  public FormulaPreview.Result test(UUID userId, ScriptFunctionDraft draft, JsonNode args) {
    try {
      ScriptFunctionDto f = new ScriptFunctionDto(null, userId, draft.name() == null ? "" : draft.name().strip(),
          draft.description(), inputsOf(draft), draft.script(), returnTypeOf(draft), Json.mapper().createArrayNode(),
          List.of(), null, null);
      Map<String, JsonNode> given = new LinkedHashMap<>();
      if (args != null && args.isObject()) args.fields().forEachRemaining(en -> given.put(en.getKey(), en.getValue()));
      JsonNode value = sandbox.runFunction(f, given, lookupFor(userId));
      return new FormulaPreview.Result(value, typeOf(value), null);
    } catch (SandboxViolationException | ValidationException e) {
      return new FormulaPreview.Result(null, null, e.getMessage());
    }
  }

  private record Clean(String name, String description, JsonNode inputs, String script, String returnType,
                       JsonNode returnFields) {}

  private Clean clean(ScriptFunctionDraft d) {
    String name = d.name() == null ? "" : d.name().strip();
    if (!ModuleContract.isName(name)) {
      throw new ValidationException("Name the function with letters, digits and _, starting with a letter");
    }
    if (RESERVED.contains(name) || FormulaRewriter.FUNCTIONS.contains(name)) {
      throw new ValidationException(name + " is already a built-in name; choose another");
    }
    String description = d.description() == null || d.description().isBlank() ? null : d.description().strip();
    if (description != null && description.length() > MAX_DESCRIPTION) {
      throw new ValidationException("The description is longer than " + MAX_DESCRIPTION + " characters");
    }
    JsonNode inputs = inputsOf(d);
    String returnType = returnTypeOf(d);
    try {
      sandbox.check(d.script());
    } catch (SandboxViolationException e) {
      throw new ValidationException(e.getMessage());
    }
    ArrayNode fields = Json.mapper().createArrayNode();
    if ("map".equals(returnType) && d.returnFields() != null && d.returnFields().isArray()) {
      Set<String> seen = new HashSet<>();
      for (JsonNode f : d.returnFields()) {
        String path = f.path("path").asText("").strip();
        if (!PATH.matcher(path).matches()) {
          throw new ValidationException("The key '" + path + "' needs letters, digits and _, with dots between nested keys");
        }
        if (!seen.add(path)) continue;
        String type = f.path("type").asText("any");
        fields.add(Json.obj().put("path", path).put("type", ModuleContract.TYPES.contains(type) ? type : "any"));
      }
      if (fields.size() > MAX_RETURN_FIELDS) {
        throw new ValidationException("A function can describe at most " + MAX_RETURN_FIELDS + " keys");
      }
    }
    return new Clean(name, description, inputs, d.script(), returnType, fields);
  }

  /** The inputs, checked: names, types, no repeats, none called fn. */
  private static JsonNode inputsOf(ScriptFunctionDraft d) {
    ArrayNode out = Json.mapper().createArrayNode();
    if (d.inputs() == null || d.inputs().isNull()) return out;
    if (!d.inputs().isArray()) throw new ValidationException("The inputs should be a list");
    if (d.inputs().size() > ModuleContract.MAX_FIELDS) {
      throw new ValidationException("A function can have at most " + ModuleContract.MAX_FIELDS + " inputs");
    }
    Set<String> seen = new HashSet<>();
    for (ModuleContract.Field f : ModuleContract.fields(Json.obj().set("inputs", d.inputs()), "inputs")) {
      if (!ModuleContract.isName(f.name())) {
        throw new ValidationException("The input '" + f.name() + "' needs a name of letters, digits and _, starting with a letter");
      }
      if (RESERVED.contains(f.name())) throw new ValidationException("An input can't be called " + f.name());
      if (!seen.add(f.name())) throw new ValidationException("There are two inputs named '" + f.name() + "'");
      ObjectNode node = Json.obj().put("name", f.name()).put("type", f.type());
      if (f.required()) node.put("required", true);
      if (f.description() != null && !f.description().isBlank()) node.put("description", f.description().strip());
      if (f.defaultValue() != null) node.set("default", f.defaultValue());
      out.add(node);
    }
    return out;
  }

  private static String returnTypeOf(ScriptFunctionDraft d) {
    String type = d.returnType() == null || d.returnType().isBlank() ? "any" : d.returnType();
    if (!ModuleContract.TYPES.contains(type)) throw new ValidationException("Unknown return type " + type);
    return type;
  }

  private static void apply(ScriptFunctionEntity e, Clean c) {
    e.setName(c.name());
    e.setDescription(c.description());
    e.setInputs(c.inputs());
    e.setScript(c.script());
    e.setReturnType(c.returnType());
    e.setReturnFields(c.returnFields());
  }

  /** After a rename, the person's other functions call it by its new name. */
  private void renameCalls(UUID userId, UUID renamed, String from, String to) {
    Pattern call = Pattern.compile("(?<![\\w.$])fn(\\s*\\.\\s*)" + Pattern.quote(from) + "(?=\\s*\\()");
    for (ScriptFunctionEntity other : functions.findByUserIdOrderByNameAsc(userId)) {
      if (other.getId().equals(renamed) || other.getScript() == null) continue;
      Matcher m = call.matcher(other.getScript());
      if (!m.find()) continue;
      other.setScript(m.replaceAll("fn$1" + Matcher.quoteReplacement(to)));
      functions.save(other);
    }
  }

  /** Which of the person's workflows run each function, by the function's id. */
  private Map<UUID, List<String>> usages(UUID userId) {
    Map<UUID, List<String>> out = new HashMap<>();
    for (WorkflowEntity w : workflows.findByUserIdOrderByCreatedAtDesc(userId)) {
      JsonNode nodes = w.getGraph() == null ? null : w.getGraph().get("nodes");
      if (nodes == null || !nodes.isArray()) continue;
      for (Iterator<JsonNode> it = nodes.elements(); it.hasNext(); ) {
        JsonNode n = it.next();
        if (!"SCRIPT".equals(n.path("type").asText())) continue;
        try {
          UUID id = UUID.fromString(n.path("config").path("functionId").asText(""));
          List<String> names = out.computeIfAbsent(id, k -> new ArrayList<>());
          if (!names.contains(w.getName())) names.add(w.getName());
        } catch (IllegalArgumentException ignored) {
          // an inline script, or no function chosen yet
        }
      }
    }
    return out;
  }

  private static ScriptFunctionDto dto(ScriptFunctionEntity e, List<String> usedBy) {
    return new ScriptFunctionDto(e.getId(), e.getUserId(), e.getName(), e.getDescription(), e.getInputs(),
        e.getScript(), e.getReturnType(), e.getReturnFields(), List.copyOf(usedBy), e.getCreatedAt(), e.getUpdatedAt());
  }

  private static String typeOf(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return "empty";
    if (value.isNumber()) return "number";
    if (value.isBoolean()) return "boolean";
    if (value.isArray()) return "list";
    if (value.isObject()) return "map";
    return "text";
  }
}
