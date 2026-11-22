package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.mcp.McpAuthType;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServerSettings;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.mcp.McpCatalog;
import com.jizza.platform.usecase.mcp.McpServerUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP servers as connectors: add a remote server by URL, sign in to it, choose its tools and the
 * agents that may use them.
 *
 * <p>Adding or re-pointing a server hands agents live access to it, so those routes are step-up
 * guarded like a connection's. The OAuth redirect lands on {@link McpOAuthCallbackController}.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/mcp-servers")
public class McpServerController {

  /** Null fields are left as they are; a blank secret clears it. */
  public record ServerInput(String name, String url, McpAuthType authType, String headerName,
                            String secret, Boolean enabled, List<String> agentKeys,
                            Boolean redactArguments, String oauthClientId,
                            String oauthClientSecret, String oauthScopes) {
    McpServerSettings settings() {
      return new McpServerSettings(name, url, authType, headerName, secret, enabled, agentKeys,
          redactArguments, oauthClientId, oauthClientSecret, oauthScopes);
    }
  }

  public record ToolInput(Boolean enabled, ActionType actionType) {}

  /** Tool name to the type the person settled on. */
  public record ToolTypesInput(java.util.Map<String, ActionType> types) {}

  private final McpServerUseCase servers;
  private final McpCatalog catalog;

  public McpServerController(McpServerUseCase servers, McpCatalog catalog) {
    this.servers = servers;
    this.catalog = catalog;
  }

  @RequiresPermission(Permissions.CONNECTOR_VIEW)
  @GetMapping
  public List<McpServerDto> list() {
    return servers.list(CurrentWorkspace.access());
  }

  /** Ready-made servers to install with one click; installing one is an ordinary create. */
  @RequiresPermission(Permissions.CONNECTOR_VIEW)
  @GetMapping("/catalog")
  public McpCatalog.Catalog catalog() {
    return catalog.catalog();
  }

  @RequiresPermission(Permissions.CONNECTOR_VIEW)
  @GetMapping("/{id}")
  public McpServerDto get(@PathVariable UUID id) {
    return servers.get(CurrentWorkspace.access(), id);
  }

  @RequiresPermission(Permissions.CONNECTOR_CREATE)
  @PostMapping
  @StepUpRequired
  public McpServerDto create(@RequestBody ServerInput input) {
    return servers.create(CurrentWorkspace.access(), input.settings());
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PatchMapping("/{id}")
  @StepUpRequired
  public McpServerDto update(@PathVariable UUID id, @RequestBody ServerInput input) {
    return servers.update(CurrentWorkspace.access(), id, input.settings());
  }

  @RequiresPermission(Permissions.CONNECTOR_DELETE)
  @DeleteMapping("/{id}")
  @StepUpRequired
  public Responses.Message delete(@PathVariable UUID id) {
    servers.delete(CurrentWorkspace.access(), id);
    return new Responses.Message("MCP server removed.");
  }

  /** Reaches the server and reads its tools again. */
  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PostMapping("/{id}/refresh")
  public McpServerDto refresh(@PathVariable UUID id) {
    return servers.refreshTools(CurrentWorkspace.access(), id);
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PatchMapping("/{id}/tools/{tool}")
  @StepUpRequired
  public McpServerDto updateTool(@PathVariable UUID id, @PathVariable String tool,
                                 @RequestBody ToolInput input) {
    return servers.updateTool(CurrentWorkspace.access(), id, tool, input.enabled(),
        input.actionType());
  }

  /** The review after adding a server: the types of several tools at once. */
  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @org.springframework.web.bind.annotation.PutMapping("/{id}/tool-types")
  @StepUpRequired
  public McpServerDto updateToolTypes(@PathVariable UUID id, @RequestBody ToolTypesInput input) {
    return servers.updateToolTypes(CurrentWorkspace.access(), id, input.types());
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PostMapping("/{id}/tools/{tool}/test")
  public ToolResult testTool(@PathVariable UUID id, @PathVariable String tool,
                             @RequestBody(required = false) JsonNode arguments) {
    return servers.test(CurrentWorkspace.access(), id, tool, arguments);
  }

  /** OAuth servers: where to send the browser to sign in. */
  @RequiresPermission(Permissions.CONNECTOR_CREATE)
  @PostMapping("/{id}/authorize")
  @StepUpRequired
  public Responses.AuthorizeUrl authorize(@PathVariable UUID id) {
    return new Responses.AuthorizeUrl(servers.startAuth(CurrentWorkspace.access(), id,
        CurrentUser.sessionId()).toString());
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PostMapping("/{id}/sign-out")
  @StepUpRequired
  public McpServerDto signOut(@PathVariable UUID id) {
    return servers.signOut(CurrentWorkspace.access(), id);
  }
}
