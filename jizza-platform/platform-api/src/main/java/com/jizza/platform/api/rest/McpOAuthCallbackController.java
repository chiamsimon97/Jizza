package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.PublicEndpoint;
import com.jizza.platform.usecase.mcp.McpServerUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where an MCP server's authorization server sends the browser after sign-in. Public for the same
 * reason the connector callback is: the single-use, expiring state is what authenticates it.
 */
@RestController
public class McpOAuthCallbackController {

  private final McpServerUseCase servers;

  public McpOAuthCallbackController(McpServerUseCase servers) {
    this.servers = servers;
  }

  @PublicEndpoint
  @GetMapping("/api/mcp/oauth/callback")
  public ResponseEntity<String> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false)
                                         String errorDescription) {
    if (error != null) {
      return page(HttpStatus.BAD_REQUEST, "Sign-in not completed", "The server reported: "
          + escape(error) + (errorDescription == null ? "" : " (" + escape(errorDescription) + ")"));
    }
    try {
      var server = servers.completeAuth(state, code);
      int tools = server.tools().size();
      return page(HttpStatus.OK, "Connected", escape(server.name()) + " is connected"
          + (tools > 0 ? " with " + tools + " tool" + (tools == 1 ? "" : "s") : "")
          + ". You can close this tab and go back to Jizza.");
    } catch (RuntimeException e) {
      return page(HttpStatus.BAD_REQUEST, "Sign-in not completed", escape(e.getMessage()));
    }
  }

  private ResponseEntity<String> page(HttpStatus status, String title, String body) {
    return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body("""
        <!doctype html><html lang="en"><head><meta charset="utf-8">
        <title>%s</title></head>
        <body style="font-family:system-ui;margin:4rem auto;max-width:32rem">
        <h1>%s</h1><p>%s</p></body></html>
        """.formatted(title, title, body));
  }

  /** The server controls these strings, so nothing goes into the page unescaped. */
  private static String escape(String value) {
    if (value == null) return "";
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }
}
