package com.jizza.platform.usecase.function;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.FormulaPreview;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDraft;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;
import com.jizza.engine.api.workflow.functions.ScriptFunctions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Functions, the separate module: a person keeps script functions with typed inputs and a return
 * type, tests them, and runs them from workflows' Script steps or from each other.
 *
 * <p>What is decided here: a function is its owner's alone (another person's reads as missing),
 * Jizza's public functions are everyone's to browse, install and run but nobody's to change, and
 * what is worth auditing. What makes a valid function is checked where it is stored.
 */
@Service
public class ScriptFunctionUseCase {

  private final ScriptFunctions functions;
  private final AuditLog audit;

  public ScriptFunctionUseCase(ScriptFunctions functions, AuditLog audit) {
    this.functions = functions;
    this.audit = audit;
  }

  public List<ScriptFunctionDto> list(UUID userId) {
    return functions.list(userId);
  }

  /** Every public function, for Explore public functions; {@code installed} marks the person's. */
  public record PublicFunction(ScriptFunctionDto function, boolean installed) {}

  public List<PublicFunction> publicFunctions(UUID userId) {
    var installed = functions.installed(userId);
    return functions.publicFunctions().stream().map(f -> new PublicFunction(f, installed.contains(f.id()))).toList();
  }

  public void install(UUID userId, UUID functionId) {
    if (functions.install(userId, functionId)) {
      audit.record(userId, ActorType.USER, null, "function.installed", get(userId, functionId).name(),
          Json.obj().put("functionId", functionId.toString()));
    }
  }

  public void uninstall(UUID userId, UUID functionId) {
    if (functions.uninstall(userId, functionId)) {
      audit.record(userId, ActorType.USER, null, "function.uninstalled", get(userId, functionId).name(),
          Json.obj().put("functionId", functionId.toString()));
    }
  }

  public ScriptFunctionDto get(UUID userId, UUID functionId) {
    ScriptFunctionDto f = functions.find(functionId).orElse(null);
    if (f != null && f.isPublic()) return f;
    return owned(userId, functionId);
  }

  public ScriptFunctionDto create(UUID userId, ScriptFunctionDraft draft) {
    ScriptFunctionDto created = functions.create(userId, draft);
    audit.record(userId, ActorType.USER, null, "function.created", created.name(),
        Json.obj().put("functionId", created.id().toString()));
    return created;
  }

  public ScriptFunctionDto update(UUID userId, UUID functionId, ScriptFunctionDraft draft) {
    ScriptFunctionDto before = owned(userId, functionId);
    ScriptFunctionDto updated = functions.update(functionId, draft);
    var detail = Json.obj().put("functionId", functionId.toString());
    if (!before.name().equals(updated.name())) detail.put("renamedFrom", before.name());
    audit.record(userId, ActorType.USER, null, "function.updated", updated.name(), detail);
    return updated;
  }

  public void delete(UUID userId, UUID functionId) {
    ScriptFunctionDto existing = owned(userId, functionId);
    functions.delete(functionId);
    audit.record(userId, ActorType.USER, null, "function.deleted", existing.name(),
        Json.obj().put("functionId", functionId.toString()));
  }

  /** Runs what is in the editor, saved or not, with {@code args} by input name. */
  public FormulaPreview.Result test(UUID userId, ScriptFunctionDraft draft, JsonNode args) {
    return functions.test(userId, draft, args);
  }

  private ScriptFunctionDto owned(UUID userId, UUID functionId) {
    ScriptFunctionDto f = functions.find(functionId).orElse(null);
    if (f != null && f.isPublic()) {
      throw new ValidationException("fn." + f.name() + " is a public function, so it can't be changed; make a copy to change it");
    }
    if (f == null || !userId.equals(f.userId())) throw new NotFoundException("Function", functionId);
    return f;
  }
}
