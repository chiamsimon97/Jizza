package com.jizza.engine.api.workflow.functions;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A function the user keeps under Functions: a few lines of Jizza script with named, typed inputs
 * and a return type. Workflows run it from a Script step; other functions call it by its link name,
 * {@code fn.<name>(…)}.
 *
 * @param name         the link name: letters, digits and _, starting with a letter
 * @param inputs       [{name, type, required, description, default}], in the order a call passes them
 * @param returnType   text, number, boolean, date, list, map or any
 * @param returnFields for a map, the keys it gives, nested ones dotted: [{path, type}]
 * @param usedBy       the names of the workflows that run it
 * @param scope        own (the person's, with {@code userId}) or public (Jizza's, which everyone
 *                     calls and nobody changes; no {@code userId})
 */
public record ScriptFunctionDto(UUID id, UUID userId, String name, String description, JsonNode inputs,
                                String script, String returnType, JsonNode returnFields,
                                List<String> usedBy, Instant createdAt, Instant updatedAt, String scope) {

  public static final String OWN = "own";
  public static final String PUBLIC = "public";

  public boolean isPublic() { return PUBLIC.equals(scope); }
}
