package com.jizza.engine.api.workflow;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a skill is, as the platform hands it to the engine: a name an agent can call a workflow by,
 * and the arguments it takes.
 *
 * @param parameterSchema JSON Schema the model is shown; null means the skill takes no arguments
 */
public record SkillSpec(String name, String description, JsonNode parameterSchema) {}
