package com.jizza.engine.api.enums;

/**
 * Workflow builder node kinds (R31).
 *
 * <p>{@code PARALLEL} starts every path leaving it; any node with more than one incoming path waits
 * for all of them (paths a branch did not take count as finished), and {@code JOIN} is that same
 * wait as a step of its own. {@code WAIT} pauses a path until a time, {@code SUB_WORKFLOW} runs
 * another workflow as one step, and {@code FOR_EACH} runs another workflow once per item of a list.
 */
public enum NodeType {
  TRIGGER, CONNECTOR_TOOL, AGENT_STEP, TRANSFORM, BRANCH, HUMAN_INTERVENTION, END,
  PARALLEL, JOIN, WAIT, SUB_WORKFLOW, FOR_EACH
}
