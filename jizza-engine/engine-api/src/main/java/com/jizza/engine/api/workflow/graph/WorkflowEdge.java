package com.jizza.engine.api.workflow.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * @param branch for a {@code BRANCH} source: which outlet this edge leaves from ("true"/"false"/case
 *               label). Null on an unconditional edge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowEdge(String from, String to, String branch) {}
