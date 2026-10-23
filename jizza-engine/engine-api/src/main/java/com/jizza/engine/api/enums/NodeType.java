package com.jizza.engine.api.enums;

/**
 * Workflow builder node kinds (R31).
 *
 * <p>{@code PARALLEL} starts every path leaving it; any node with more than one incoming path waits
 * for all of them (paths a branch did not take count as finished), and {@code JOIN} is that same
 * wait as a step of its own. {@code WAIT} pauses a path until a time, {@code SUB_WORKFLOW} runs
 * another workflow as one step, and {@code FOR_EACH} runs another workflow once per item of a list.
 *
 * <p>The built-in actions run without an agent, like Zapier's and Zoho Flow's: {@code APPROVAL}
 * waits for the user to approve or reject (two paths out), {@code HTTP_REQUEST} calls a URL,
 * {@code FILTER} stops its path unless a condition holds, {@code SWITCH} takes the first of several
 * paths whose condition holds, {@code SET_VARIABLE} sets run variables, {@code STORAGE} keeps values
 * between runs, and {@code NOTIFY} tells the user in the app or by email.
 *
 * <p>{@code MCP_TOOL} calls one tool of an MCP server added under Connectors, as
 * {@code CONNECTOR_TOOL} calls a service action, under the same guardrails.
 */
public enum NodeType {
  TRIGGER, CONNECTOR_TOOL, AGENT_STEP, TRANSFORM, BRANCH, HUMAN_INTERVENTION, END,
  PARALLEL, JOIN, WAIT, SUB_WORKFLOW, FOR_EACH,
  APPROVAL, HTTP_REQUEST, FILTER, SWITCH, SET_VARIABLE, STORAGE, NOTIFY, MCP_TOOL
}
