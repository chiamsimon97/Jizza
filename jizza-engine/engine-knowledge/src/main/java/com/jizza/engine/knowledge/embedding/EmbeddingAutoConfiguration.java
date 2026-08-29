package com.jizza.engine.knowledge.embedding;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the local embedding provider unless something else already provides one.
 *
 * <p>An autoconfiguration rather than a conditional {@code @Component}: conditions on scanned
 * components are evaluated as they are found, so whether the condition sees a competing bean
 * depends on scan order. Autoconfiguration is always evaluated last, which makes "a custom
 * provider wins" a guarantee instead of a coincidence.
 */
@AutoConfiguration
public class EmbeddingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(EmbeddingProvider.class)
  public EmbeddingProvider localHashingEmbeddingProvider() {
    return new LocalHashingEmbeddingProvider();
  }
}
