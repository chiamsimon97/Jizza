package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.workflow.FormulaPreview;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDraft;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.function.ScriptFunctionUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Functions: the person's script functions, which workflows and other functions call, and the public ones they install. */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/functions")
public class ScriptFunctionController {

  /** What the editor holds, and the inputs to test it with, by name. */
  public record TestRequest(ScriptFunctionDraft function, JsonNode args) {}

  private final ScriptFunctionUseCase functions;

  public ScriptFunctionController(ScriptFunctionUseCase functions) {
    this.functions = functions;
  }

  @GetMapping
  public List<ScriptFunctionDto> list() {
    return functions.list(CurrentUser.userId());
  }

  /** Explore public functions: every public one, with whether the person installed it. */
  @GetMapping("/public")
  public List<ScriptFunctionUseCase.PublicFunction> publicFunctions() {
    return functions.publicFunctions(CurrentUser.userId());
  }

  @PostMapping("/{id}/install")
  public Responses.Message install(@PathVariable UUID id) {
    functions.install(CurrentUser.userId(), id);
    return new Responses.Message("Function installed.");
  }

  @DeleteMapping("/{id}/install")
  public Responses.Message uninstall(@PathVariable UUID id) {
    functions.uninstall(CurrentUser.userId(), id);
    return new Responses.Message("Function uninstalled.");
  }

  @GetMapping("/{id}")
  public ScriptFunctionDto get(@PathVariable UUID id) {
    return functions.get(CurrentUser.userId(), id);
  }

  @PostMapping
  public ScriptFunctionDto create(@RequestBody ScriptFunctionDraft draft) {
    return functions.create(CurrentUser.userId(), draft);
  }

  @PutMapping("/{id}")
  public ScriptFunctionDto update(@PathVariable UUID id, @RequestBody ScriptFunctionDraft draft) {
    return functions.update(CurrentUser.userId(), id, draft);
  }

  @DeleteMapping("/{id}")
  public Responses.Message delete(@PathVariable UUID id) {
    functions.delete(CurrentUser.userId(), id);
    return new Responses.Message("Function deleted.");
  }

  /** Runs the function as the editor holds it, saved or not. */
  @PostMapping("/test")
  public FormulaPreview.Result test(@RequestBody TestRequest request) {
    if (request == null || request.function() == null) throw new ValidationException("Send the function to test");
    return functions.test(CurrentUser.userId(), request.function(), request.args());
  }
}
