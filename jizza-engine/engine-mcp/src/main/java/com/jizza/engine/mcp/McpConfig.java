package com.jizza.engine.mcp;

import com.jizza.engine.api.connector.EgressGuard;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.connectors.config.ConnectorProperties;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.McpServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
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

  private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

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
                                           EncryptionService encryption, McpOAuth oauth,
                                           org.springframework.beans.factory.ObjectProvider<
                                               com.jizza.engine.persistence.repository.AuditLogRepository> audit) {
    McpServerService service = new McpServerService(servers, provider, encryption, oauth);
    service.setAuditLog(audit.getIfAvailable());
    return service;
  }

  /** Corrects what an older classifier suggested a tool does, once the app is up. */
  @Bean
  public ApplicationRunner mcpSuggestionRefresh(McpServerService service) {
    return args -> {
      try {
        int changed = service.resuggestAll();
        if (changed > 0) log.info("Updated the suggested tool types of {} MCP servers", changed);
      } catch (RuntimeException e) {
        log.warn("Could not update the suggested MCP tool types", e);
      }
    };
  }
}
