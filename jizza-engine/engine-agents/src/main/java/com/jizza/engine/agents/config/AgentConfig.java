package com.jizza.engine.agents.config;

import com.jizza.engine.api.chat.ChatFiles;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.agents.llm.AnthropicLlmClient;
import com.jizza.engine.agents.llm.ModelProviders;
import com.jizza.engine.agents.llm.RoutingLlmClient;
import com.jizza.engine.agents.llm.UnconfiguredLlmClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

  /**
   * The real client when a key is configured, a loud stub otherwise. Booting without a key is
   * deliberately supported so the scheduler, connectors and workflow engine stay testable.
   */
  @Bean
  public LlmClient llmClient(AgentProperties props, ObjectProvider<ChatFiles> chatFiles,
                             ObjectProvider<ModelProviders> providers) {
    String apiKey = props.getAnthropic().getApiKey();
    LlmClient platform = apiKey == null || apiKey.isBlank()
        ? new UnconfiguredLlmClient()
        : new AnthropicLlmClient(props, chatFiles::getIfAvailable);
    // Models on the person's own key (F1) go to their connection; everything else to the platform.
    return new RoutingLlmClient(platform, providers::getIfAvailable);
  }
}
