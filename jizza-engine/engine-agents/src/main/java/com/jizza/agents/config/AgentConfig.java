package com.jizza.agents.config;

import com.jizza.common.llm.LlmClient;
import com.jizza.agents.llm.AnthropicLlmClient;
import com.jizza.agents.llm.UnconfiguredLlmClient;
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
  public LlmClient llmClient(AgentProperties props) {
    String apiKey = props.getAnthropic().getApiKey();
    return apiKey == null || apiKey.isBlank()
        ? new UnconfiguredLlmClient()
        : new AnthropicLlmClient(props);
  }
}
