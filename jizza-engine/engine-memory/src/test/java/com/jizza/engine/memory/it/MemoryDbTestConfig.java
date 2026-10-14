package com.jizza.engine.memory.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.knowledge.embedding.EmbeddingProvider;
import com.jizza.engine.knowledge.embedding.LocalHashingEmbeddingProvider;
import com.jizza.engine.memory.config.MemoryConfig;
import com.jizza.engine.memory.config.MemoryScopes;
import com.jizza.engine.memory.retrieval.HybridMemoryContextProvider;
import com.jizza.engine.memory.retrieval.HybridSearch;
import com.jizza.engine.memory.maintenance.MemoryMaintenance;
import com.jizza.engine.memory.store.DefaultMemoryStore;
import com.jizza.engine.memory.tools.MemoryToolProvider;
import com.jizza.engine.persistence.config.PersistenceConfig;
import com.jizza.engine.persistence.support.MemoryQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** The memory module on a real PostgreSQL with pgvector, and nothing else of the engine. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({PersistenceConfig.class, MemoryQueries.class, MemoryConfig.class, MemoryScopes.class,
    HybridSearch.class, DefaultMemoryStore.class, HybridMemoryContextProvider.class,
    MemoryToolProvider.class, MemoryMaintenance.class})
public class MemoryDbTestConfig {

  @Bean
  EmbeddingProvider embeddingProvider() {
    return new LocalHashingEmbeddingProvider();
  }

  @Bean
  RecordingAudit auditLog() {
    return new RecordingAudit();
  }

  /** Keeps what was audited so tests can check that content never reaches the log. */
  public static class RecordingAudit implements AuditLog {
    public final List<String> details = new ArrayList<>();
    public final List<String> actions = new ArrayList<>();

    @Override
    public void record(UUID userId, ActorType actorType, UUID actorId, String action, String target,
                       JsonNode detail) {
      actions.add(action);
      details.add(action + " " + target + " " + detail);
    }

    @Override public Optional<UUID> verifyChain(UUID userId) { return Optional.empty(); }
    @Override public List<AuditEntryDto> recent(UUID userId, int limit, String action) { return List.of(); }
    @Override public long countForUser(UUID userId) { return actions.size(); }
  }
}
