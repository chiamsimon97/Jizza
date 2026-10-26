package com.jizza.engine.api.workflow.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.workflow.FormulaPreview;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The user's script functions: storage, and running one the way a workflow step does. Each person
 * has their own; a name is unique per person, since other functions call it by that name. Jizza's
 * public functions are nobody's to change: a person installs the ones they want, and only those are
 * listed and callable for them. A person's own functions can't take their names.
 *
 * <p>Saving checks what can be checked before a run (the name, the inputs, the return type, that
 * the script compiles); renaming a function updates the calls to it in the person's other functions.
 */
public interface ScriptFunctions {

  /** The person's functions, then the public ones they installed. */
  List<ScriptFunctionDto> list(UUID userId);

  /** Every public function, installed or not. */
  List<ScriptFunctionDto> publicFunctions();

  /** The ids of the public functions the person installed. */
  Set<UUID> installed(UUID userId);

  /** Installs a public function for the person; false when it already was. */
  boolean install(UUID userId, UUID functionId);

  /** Takes an installed public function away from the person; false when it wasn't installed. */
  boolean uninstall(UUID userId, UUID functionId);

  Optional<ScriptFunctionDto> find(UUID functionId);

  /** One of the person's functions by its link name, or else a public one they installed. */
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
