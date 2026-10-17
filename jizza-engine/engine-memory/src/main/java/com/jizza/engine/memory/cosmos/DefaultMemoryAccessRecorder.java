package com.jizza.engine.memory.cosmos;

import com.jizza.engine.api.cosmos.AccessContext;
import com.jizza.engine.api.cosmos.MemoryAccessRecorder;
import com.jizza.engine.memory.config.MemoryProperties;
import com.jizza.engine.persistence.support.MemoryAccessWriter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stamps each memory item an agent is handed, in the run it was handed in (the
 * {@link AccessContext} on the calling thread). Off the request thread by default: a stamp that
 * fails is logged and dropped, and never fails or slows the read it records.
 */
@Service
public class DefaultMemoryAccessRecorder implements MemoryAccessRecorder {

  private static final Logger log = LoggerFactory.getLogger(DefaultMemoryAccessRecorder.class);

  private final MemoryAccessWriter writer;
  private final MemoryProperties props;
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-memory-access-", 0).factory());

  public DefaultMemoryAccessRecorder(MemoryAccessWriter writer, MemoryProperties props) {
    this.writer = writer;
    this.props = props;
  }

  @Override
  public void facts(UUID userId, UUID agentId, Collection<UUID> memoryIds) {
    submit(userId, agentId, memoryIds, true);
  }

  @Override
  public void notes(UUID userId, UUID agentId, Collection<UUID> entryIds) {
    submit(userId, agentId, entryIds, false);
  }

  private void submit(UUID userId, UUID agentId, Collection<UUID> ids, boolean facts) {
    if (userId == null || ids == null || ids.isEmpty()) return;
    // Captured here: the context lives on the agent's thread, not the writer's.
    AccessContext ctx = AccessContext.current()
        .orElseGet(() -> AccessContext.fromTrace(null));
    List<UUID> copy = List.copyOf(ids);
    Runnable write = () -> {
      try {
        if (facts) writer.stampFacts(userId, agentId, copy, ctx.kind(), ctx.ref());
        else writer.stampNotes(userId, agentId, copy, ctx.kind(), ctx.ref());
      } catch (RuntimeException e) {
        log.debug("Could not record memory access", e);
      }
    };
    if (props.getAccess().isAsync()) executor.execute(write); else write.run();
  }
}
