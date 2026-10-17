package com.jizza.engine.mcp;

import com.jizza.engine.persistence.repository.AgentRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** With no servers configured the provider offers nothing and opens nothing. */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

  @Bean
  public McpToolProvider mcpToolProvider(McpProperties props, AgentRepository agents) {
    return new McpToolProvider(props, new SdkMcpSessions(), agents);
  }
}
