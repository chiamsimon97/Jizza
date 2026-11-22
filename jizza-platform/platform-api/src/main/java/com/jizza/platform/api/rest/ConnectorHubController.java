package com.jizza.platform.api.rest;

import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.usecase.connector.ConnectorHubUseCase;
import com.jizza.platform.usecase.connector.ConnectorHubUseCase.Card;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Connectors hub: services, MCP servers and devices as one list of cards, and one way to say
 * which agents may use each. Testing and signing in stay on each kind's own routes.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/connector-hub")
public class ConnectorHubController {

  /** Agent keys; {@code "*"} gives it to every agent. */
  public record AgentsInput(List<String> agentKeys) {}

  private final ConnectorHubUseCase hub;

  public ConnectorHubController(ConnectorHubUseCase hub) {
    this.hub = hub;
  }

  @RequiresPermission(Permissions.CONNECTOR_VIEW)
  @GetMapping
  public List<Card> cards() {
    return hub.cards(CurrentWorkspace.access());
  }

  /** Handing an agent a connector widens what it can do, so it needs a fresh re-authentication. */
  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PutMapping("/{kind}/{id}/agents")
  @StepUpRequired
  public Card setAgents(@PathVariable String kind, @PathVariable UUID id,
                        @RequestBody AgentsInput input) {
    return hub.setAgents(CurrentWorkspace.access(), kind, id, input.agentKeys());
  }
}
