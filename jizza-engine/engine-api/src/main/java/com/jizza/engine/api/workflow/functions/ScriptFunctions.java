package com.jizza.engine.api.workflow.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.workflow.FormulaPreview;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The user's script functions: storage, and running one the way a workflow step does. Each person
 * has their own; a name is unique per person, since other functions call it by that name.
 *
 * <p>Saving checks what can be checked before a run (the name, the inputs, the return type, that
 * the script compiles); renaming a function updates the calls to it in the person's other functions.
 */
public interface ScriptFunctions {

  List<ScriptFunctionDto> list(UUID userId);

  Optional<ScriptFunctionDto> find(UUID functionId);

  /** One of the person's functions by its link name. */
  Optional<ScriptFunctionDto> findByName(UUID userId, String name);

  ScriptFunctionDto create(UUID userId, ScriptFunctionDraft draft);

  ScriptFunctionDto update(UUID functionId, ScriptFunctionDraft draft);

  void delete(UUID functionId);

  /**
   * Runs {@code draft} (saved or not) with {@code args}, its inputs by name, calling the person's
   * saved functions where it calls others.
   */
  FormulaPreview.Result test(UUID userId, ScriptFunctionDraft draft, JsonNode args);
}
