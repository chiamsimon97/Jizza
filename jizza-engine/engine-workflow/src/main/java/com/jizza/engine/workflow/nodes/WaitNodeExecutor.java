package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Holds its path until a time: {@code duration} after the step is reached (ISO-8601, such as
 * {@code PT2H} or {@code P1D}), or {@code until} a moment, given directly or computed by
 * {@code untilExpr}. A time without an offset is read in {@code timezone}, India by default like
 * scheduled tasks.
 *
 * <p>The wake time is worked out once and kept in the step's state, so a restart neither resets
 * the wait nor stretches it. The run sleeps in the database while it waits; the engine's recovery
 * sweep wakes it, so a wait resolves to within one sweep interval.
 */
@Component
public class WaitNodeExecutor implements NodeExecutor {

  /** Longer than this is a scheduled task's job, not a workflow step's. */
  static final Duration MAX_WAIT = Duration.ofDays(366);

  private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

  private final JexlSandbox sandbox;
  private final Clock clock;

  @Autowired
  public WaitNodeExecutor(JexlSandbox sandbox) {
    this(sandbox, Clock.systemUTC());
  }

  public WaitNodeExecutor(JexlSandbox sandbox, Clock clock) {
    this.sandbox = sandbox;
    this.clock = clock;
  }

  @Override
  public NodeType type() { return NodeType.WAIT; }

  @Override
  public Outcome execute(Execution execution) {
    String nodeId = execution.node().id();
    ObjectNode state = execution.context().state(nodeId);
    Instant now = clock.instant();
    Instant wakeAt;
    if (state.path("wakeAt").isTextual()) {
      wakeAt = Instant.parse(state.get("wakeAt").asText());
    } else {
      try {
        wakeAt = wakeTime(execution, now);
      } catch (IllegalArgumentException | DateTimeException | SandboxViolationException e) {
        return Outcome.permanentFailure("The wait step could not work out when to continue: "
            + e.getMessage());
      }
      if (wakeAt.isAfter(now.plus(MAX_WAIT))) {
        return Outcome.permanentFailure("The wait step would wait more than " + MAX_WAIT.toDays()
            + " days; use a scheduled task for that");
      }
      state.put("wakeAt", wakeAt.toString());
    }
    if (!now.isBefore(wakeAt)) {
      return Outcome.completed(Json.obj().put("waitedUntil", wakeAt.toString()));
    }
    return Outcome.waitUntil(wakeAt);
  }

  private Instant wakeTime(Execution execution, Instant now) {
    JsonNode config = execution.node().config();
    if (config == null) throw new IllegalArgumentException("it has no settings");
    if (config.hasNonNull("duration")) {
      Duration duration = Duration.parse(config.get("duration").asText());
      if (duration.isNegative()) throw new IllegalArgumentException("the duration is negative");
      return now.plus(duration);
    }
    String until = config.hasNonNull("until") ? config.get("until").asText()
        : config.hasNonNull("untilExpr")
            ? sandbox.evaluate(config.get("untilExpr").asText(), execution.context().variables())
                .asText()
            : null;
    if (until == null || until.isBlank()) {
      throw new IllegalArgumentException("set a duration, an until time or an untilExpr");
    }
    return parseMoment(until, config.path("timezone").asText(null));
  }

  static Instant parseMoment(String text, String zone) {
    String value = text.strip();
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeException notOffset) {
      try {
        return Instant.parse(value);
      } catch (DateTimeException notInstant) {
        ZoneId zoneId = zone == null || zone.isBlank() ? DEFAULT_ZONE : ZoneId.of(zone);
        return LocalDateTime.parse(value).atZone(zoneId).toInstant();
      }
    }
  }
}
