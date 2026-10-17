package com.jizza.engine.knowledge.embedding;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the configured embedding provider unless something else already provides one.
 *
 * <p>An autoconfiguration rather than a conditional {@code @Component}: conditions on scanned
 * components are evaluated as they are found, so whether the condition sees a competing bean
 * depends on scan order. Autoconfiguration is always evaluated last, which makes "a custom
 * provider wins" a guarantee instead of a coincidence.
 */
@AutoConfiguration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(EmbeddingProvider.class)
  public EmbeddingProvider embeddingProvider(EmbeddingProperties props) {
    return switch (props.getProvider() == null ? "local" : props.getProvider().trim()) {
      case "openai-compatible" -> new OpenAiCompatibleEmbeddingProvider(props.getOpenaiCompatible());
      case "local", "" -> new LocalHashingEmbeddingProvider();
      default -> throw new IllegalStateException("Unknown jizza.embedding.provider '"
          + props.getProvider() + "'; use 'local' or 'openai-compatible'");
    };
  }
}
