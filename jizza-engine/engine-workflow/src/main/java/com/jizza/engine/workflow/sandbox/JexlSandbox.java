package com.jizza.engine.workflow.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.springframework.stereotype.Component;

/**
 * The Transform node's expression sandbox (R9). Expressions are formulas as the builder writes
 * them, with chained functions ({@code input.name.trim().upper()}), which {@link FormulaRewriter}
 * turns into the {@code fmt:} calls JEXL runs. Apache Commons JEXL3, locked down so a workflow can
 * shape data and nothing else.
 *
 * <p>Four independent restrictions, because any one of them alone would be a single point of
 * failure:
 * <ul>
 *   <li><b>Permissions.</b> {@code JexlPermissions.RESTRICTED} blocks the JDK packages that reach
 *       the filesystem, the network, reflection and the process -- so even a reachable object
 *       cannot be used to escape.</li>
 *   <li><b>Language features.</b> Object construction, loops, lambdas, annotations, pragmas and
 *       global side effects are all switched off, so there is no construct with which to build an
 *       escape.</li>
 *   <li><b>Context.</b> The expression sees the run's own data and nothing else -- no system
 *       properties, no beans, no engine handle.</li>
 *   <li><b>Time.</b> Evaluation runs on a separate thread with a hard timeout, so a pathological
 *       expression cannot occupy a worker. The engine is cancellable, so the timeout's interrupt
 *       stops evaluation between steps rather than leaving the thread running, and the threads are
 *       capped, so a flood of formulas is refused instead of starving the server.</li>
 * </ul>
 *
 * <p>Formulas are also capped in length, and plain assignment ({@code x = 1}) is switched off with
 * the other side effects: a formula reads data and gives a value.
 */
@Component
public class JexlSandbox {

  /** Wall-clock ceiling for one expression. */
  private static final long TIMEOUT_MILLIS = 2_000;

  /** The longest formula accepted, in characters. */
  public static final int MAX_LENGTH = 10_000;

  /** Formulas that may run at once; more are refused rather than queued behind a stuck one. */
  private static final int MAX_THREADS = 32;

  private final JexlEngine engine;
  private final ExecutorService executor = new ThreadPoolExecutor(0, MAX_THREADS, 60, TimeUnit.SECONDS,
      new SynchronousQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "jizza-transform");
        thread.setDaemon(true);
        return thread;
      });

  public JexlSandbox() {
    JexlFeatures features = new JexlFeatures()
        .loops(false)                 // no unbounded iteration
        .newInstance(false)           // no `new`
        .methodCall(true)             // simple accessors on the data are the point of the node
        .sideEffect(false)            // no assignment: a formula gives a value, nothing else
        .sideEffectGlobal(false)      // cannot write back into the context
        .lambda(false)
        .annotation(false)
        .pragma(false)
        .namespacePragma(false)
        .importPragma(false)
        .register(false);

    this.engine = new JexlBuilder()
        .features(features)
        // The fmt: functions are the one class added to what an expression may call.
        .permissions(JexlPermissions.RESTRICTED.compose("com.jizza.engine.workflow.sandbox.*"))
        .namespaces(java.util.Map.of("fmt", Formatters.class))
        .strict(true)
        .silent(false)
        .safe(false)
        .cancellable(true)            // the timeout's interrupt stops evaluation
        .create();
  }

  /**
   * Evaluates {@code expression} against {@code variables} and returns the result as JSON.
   *
   * @throws SandboxViolationException on a syntax error, a blocked construct, or a timeout
   */
  public JsonNode evaluate(String expression, Map<String, Object> variables) {
    if (expression == null || expression.isBlank()) {
      throw new SandboxViolationException("The transform has no expression");
    }
    if (expression.length() > MAX_LENGTH) {
      throw new SandboxViolationException("The formula is longer than " + MAX_LENGTH + " characters");
    }
    JexlExpression compiled;
    try {
      compiled = engine.createExpression(FormulaRewriter.toJexl(expression));
    } catch (JexlException e) {
      throw new SandboxViolationException("The transform expression is not valid: "
          + rootMessage(e));
    }

    MapContext context = new MapContext();
    if (variables != null) variables.forEach(context::set);

    Future<Object> future;
    try {
      future = executor.submit(() -> compiled.evaluate(context));
    } catch (RejectedExecutionException e) {
      throw new SandboxViolationException("Too many formulas are running at once; try again shortly");
    }
    try {
      Object result = future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      return Json.toNode(result);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new SandboxViolationException(
          "The transform took longer than " + TIMEOUT_MILLIS + "ms and was stopped");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxViolationException("The transform was interrupted");
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof JexlException.Cancel) {
        throw new SandboxViolationException(
            "The transform took longer than " + TIMEOUT_MILLIS + "ms and was stopped");
      }
      if (cause instanceof JexlException jexl) {
        throw new SandboxViolationException("The transform could not run: " + rootMessage(jexl));
      }
      throw new SandboxViolationException("The transform failed: "
          + (cause == null ? e.getMessage() : cause.getMessage()));
    }
  }

  private String rootMessage(JexlException e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    String message = cause.getMessage();
    return message == null ? e.toString() : message;
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
