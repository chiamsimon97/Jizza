package com.jizza.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The real SDK against a real server process over stdio. */
class SdkMcpSessionsTest {

  @Test
  void listsAndCallsTheToolsOfAServerRunningAsItsOwnProcess() {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    McpProperties.Server server = new McpProperties.Server();
    server.setCommand(java);
    server.setArgs(List.of("-cp", System.getProperty("java.class.path"),
        StdioTestServer.class.getName()));
    server.setRequestTimeout(Duration.ofSeconds(30));

    try (McpSession session = new SdkMcpSessions().open("notes", server)) {
      assertThat(session.listTools()).extracting(McpSchema.Tool::name)
          .containsExactlyInAnyOrder("search_notes", "delete_note");

      McpSchema.CallToolResult result = session.callTool("search_notes", Map.of("query", "tax"));

      assertThat(McpToolProvider.toResult(result).output().asText())
          .isEqualTo("found 2 notes about tax");
    }
  }
}
