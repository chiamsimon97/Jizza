package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import org.springframework.stereotype.Component;

/**
 * Where parallel paths meet. The engine only runs a step once every path into it has resolved, so
 * by the time this runs the waiting is over; what it adds is one output holding each arriving
 * step's result by id, for the steps after it to read as {@code nodes.<joinId>.<stepId>}.
 *
 * <p>A join stops waiting once its {@code timeout} (ISO-8601, an hour by default, {@code "none"}
 * to wait for ever) has passed since the first path reached it. With {@code onTimeout} at
 * {@code "continue"}, the default, it carries on with the paths that made it and lists the steps
 * it gave up on under {@code _timedOut}; with {@code "fail"} the run fails instead. The engine
 * keeps the timer; see {@code SagaWorkflowEngine}.
 */
@Component
public class JoinNodeExecutor implements NodeExecutor {

  /** Output key listing the incoming steps a timed-out join stopped waiting for. */
  public static final String TIMED_OUT = "_timedOut";

  @Override
  public NodeType type() { return NodeType.JOIN; }

  @Override
  public Outcome execute(Execution execution) {
    ObjectNode merged = Json.obj();
    for (String from : execution.context().liveInputs(execution.node().id())) {
      merged.set(from, execution.context().outputOf(from));
    }
    var timedOut = execution.context().peekState(execution.node().id()).path("timedOut");
    if (timedOut.isArray() && !timedOut.isEmpty()) merged.set(TIMED_OUT, timedOut.deepCopy());
    return Outcome.completed(merged);
  }
}
