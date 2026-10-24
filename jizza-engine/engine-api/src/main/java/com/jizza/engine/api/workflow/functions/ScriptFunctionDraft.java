package com.jizza.engine.api.workflow.functions;

import com.fasterxml.jackson.databind.JsonNode;

/** What a function is made of, as the editor sends it; see {@link ScriptFunctionDto}. */
public record ScriptFunctionDraft(String name, String description, JsonNode inputs, String script,
                                  String returnType, JsonNode returnFields) {}
