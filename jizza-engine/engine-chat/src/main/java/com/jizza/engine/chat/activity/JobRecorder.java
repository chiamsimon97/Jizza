package com.jizza.engine.chat.activity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.activity.ActivityEvent;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.chat.JobEventDto;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.JobEventEntity;
import com.jizza.engine.persistence.repository.JobEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One job's sink: numbers each event, pushes it to the user's socket, and keeps it.
 *
 * <p>Streamed text ({@code delta}) is pushed but not stored: the finished reply is stored on the
 * message instead, and a client that reconnects mid-turn gets the text so far from
 * {@link #partialText()}. An incognito job stores nothing; its events live in memory only, long
 * enough for the client to catch up.
 *
 * <p>Events can arrive from several threads at once (specialists fanned out in parallel), so the
 * sequence is atomic and the in-memory list is guarded.
 */
public class JobRecorder implements ActivitySink {

  private static final Logger log = LoggerFactory.getLogger(JobRecorder.class);

  public static final String TOPIC = "chat";

  private final UUID jobId;
  private final UUID userId;
  private final UUID conversationId;
  private final boolean persist;
  private final JobEventRepository events;
  private final RealtimeGateway realtime;
  private final AtomicInteger seq = new AtomicInteger();
  private final List<JobEventDto> kept = new ArrayList<>();
  private final StringBuilder partial = new StringBuilder();
  private volatile boolean cancelled;

  public JobRecorder(UUID jobId, UUID userId, UUID conversationId, boolean persist,
                     JobEventRepository events, RealtimeGateway realtime) {
    this.jobId = jobId;
    this.userId = userId;
    this.conversationId = conversationId;
    this.persist = persist;
    this.events = events;
    this.realtime = realtime;
  }

  public UUID jobId() { return jobId; }

  @Override
  public void emit(ActivityEvent event) {
    int n = seq.incrementAndGet();
    Instant at = Instant.now();
    ObjectNode payload = event.payload() == null ? Json.obj() : event.payload();

    if (ActivityTypes.DELTA.equals(event.type())) {
      synchronized (partial) { partial.append(payload.path("text").asText()); }
    } else if (ActivityTypes.SEGMENT_PREAMBLE.equals(event.type())) {
      // That text was narration before tool calls; the answer starts afresh in the next segment.
      synchronized (partial) { partial.setLength(0); }
    }

    JobEventDto dto = new JobEventDto(n, event.type(), event.agentId(), event.agentName(), payload, at);
    if (!ActivityTypes.DELTA.equals(event.type())) keep(dto);
    push(dto);
  }

  private void keep(JobEventDto dto) {
    if (!persist) {
      synchronized (kept) { kept.add(dto); }
      return;
    }
    try {
      JobEventEntity entity = new JobEventEntity();
      entity.setJobId(jobId);
      entity.setSeq(dto.seq());
      entity.setType(dto.type());
      entity.setAgentId(dto.agentId());
      entity.setAgentName(dto.agentName());
      entity.setPayload(dto.payload());
      entity.setAt(dto.at());
      events.save(entity);
    } catch (RuntimeException e) {
      log.warn("Could not keep event {} of job {}", dto.type(), jobId, e);
    }
  }

  private void push(JobEventDto dto) {
    if (realtime == null) return;
    ObjectNode message = Json.obj();
    message.put("type", dto.type());
    message.put("jobId", jobId.toString());
    if (conversationId != null) message.put("conversationId", conversationId.toString());
    message.put("seq", dto.seq());
    if (dto.agentId() != null) message.put("agentId", dto.agentId().toString());
    if (dto.agentName() != null) message.put("agentName", dto.agentName());
    message.put("at", dto.at().toString());
    message.set("payload", dto.payload());
    realtime.publish(userId, TOPIC, message);
  }

  /** Events not stored in the database (incognito), after {@code afterSeq}. */
  public List<JobEventDto> keptAfter(int afterSeq) {
    synchronized (kept) {
      return kept.stream().filter(e -> e.seq() > afterSeq).toList();
    }
  }

  /** Every event this job reported, for summarising it. */
  public List<JobEventDto> all() {
    if (!persist) return keptAfter(0);
    return events.findByJobIdOrderBySeqAsc(jobId).stream()
        .map(e -> new JobEventDto(e.getSeq(), e.getType(), e.getAgentId(), e.getAgentName(),
            e.getPayload(), e.getAt()))
        .toList();
  }

  public String partialText() {
    synchronized (partial) { return partial.toString(); }
  }

  public void cancel() { cancelled = true; }

  @Override
  public boolean cancelled() { return cancelled; }
}
