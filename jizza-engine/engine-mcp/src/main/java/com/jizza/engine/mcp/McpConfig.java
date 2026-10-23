package com.jizza.engine.mcp;

import com.jizza.engine.api.connector.EgressGuard;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.connectors.config.ConnectorProperties;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.McpServerRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * With no servers configured and none added in the app the provider offers nothing and opens
 * nothing. Servers added in the app go through the egress guard before they are reached.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

  @Bean
  public McpToolProvider mcpToolProvider(McpProperties props, AgentRepository agents,
                                         ObjectProvider<EgressGuard> egress) {
    return new McpToolProvider(props, new GuardedMcpSessions(new SdkMcpSessions(), egress), agents);
  }

  @Bean
  public McpOAuth mcpOAuth(GuardedHttpClient http, EncryptionService encryption,
                           ConnectorProperties connectorProperties) {
    return new McpOAuth(http, encryption, connectorProperties);
  }

  @Bean
  public McpServerService mcpServerService(McpServerRepository servers, McpToolProvider provider,
                                           EncryptionService encryption, McpOAuth oauth) {
    return new McpServerService(servers, provider, encryption, oauth);
  }
}
