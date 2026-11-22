package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.PublicEndpoint;
import com.jizza.engine.api.webhook.WebhookRegistry;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.webhook.WebhookUseCase;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound webhooks (R44). {@code POST /api/webhooks/{token}} is the one route reachable without a
 * session, so it is authenticated by the unguessable token plus the connector's signature check,
 * and it reads the raw body -- a signature is over bytes, not over a re-serialized object.
 */
@RestController
public class WebhookController {

  private final WebhookRegistry webhooks;
  private final com.jizza.engine.api.service.ServiceRunner services;

  public WebhookController(WebhookRegistry webhooks,
                           com.jizza.engine.api.service.ServiceRunner services) {
    this.webhooks = webhooks;
    this.services = services;
  }

  /**
   * An instant trigger of a service (K6): the service calls this address when something happens.
   * The token picks the trigger; the trigger's own signing secret, when set, checks the body.
   */
  @PublicEndpoint
  @PostMapping("/api/webhooks/services/{token}")
  public ResponseEntity<Responses.Message> receiveForService(@PathVariable String token,
      @RequestBody(required = false) String rawBody, HttpServletRequest request) {
    var delivery = services.receiveWebhook(token, rawBody, headers(request));
    return ResponseEntity.status(delivery.accepted() ? HttpStatus.ACCEPTED : HttpStatus.FORBIDDEN)
        .body(new Responses.Message(delivery.reason()));
  }

  private static Map<String, String> headers(HttpServletRequest request) {
    Map<String, String> headers = new HashMap<>();
    var names = request.getHeaderNames();
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
    }
    return headers;
  }

  @PublicEndpoint
  @PostMapping("/api/webhooks/{token}")
  public ResponseEntity<Responses.Message> receive(@PathVariable String token,
                                                   @RequestBody(required = false) String rawBody,
                                                   HttpServletRequest request) {
    var delivery = webhooks.receive(token, rawBody, headers(request));
    return ResponseEntity.status(delivery.accepted() ? HttpStatus.ACCEPTED : HttpStatus.FORBIDDEN)
        .body(new Responses.Message(delivery.reason()));
  }

  @RestController
  @AuthenticatedOnly
  @RequestMapping("/api/webhook-endpoints")
  public static class Management {

    private final WebhookUseCase webhooks;
    private final com.jizza.engine.connectors.config.ConnectorProperties connectorProps;

    public Management(WebhookUseCase webhooks,
                      com.jizza.engine.connectors.config.ConnectorProperties connectorProps) {
      this.webhooks = webhooks;
      this.connectorProps = connectorProps;
    }

    @GetMapping
    public List<Responses.WebhookEndpoint> list() {
      return webhooks.list(CurrentUser.userId()).stream()
          .map(subscription -> new Responses.WebhookEndpoint(subscription.id(),
              url(subscription.endpointToken()), subscription.workflowId(),
              subscription.enabled()))
          .toList();
    }

    @PostMapping
    @com.jizza.engine.api.util.StepUpRequired
    public Responses.WebhookEndpoint subscribe(@Valid @RequestBody Requests.WebhookSubscribe request) {
      var subscription = webhooks.subscribe(CurrentUser.userId(), request.connectionId(),
          request.workflowId(), request.verifyConfig());
      return new Responses.WebhookEndpoint(subscription.id(),
          url(subscription.endpointToken()), subscription.workflowId(), true);
    }

    @DeleteMapping("/{id}")
    @com.jizza.engine.api.util.StepUpRequired
    public Responses.Message unsubscribe(@PathVariable UUID id) {
      webhooks.unsubscribe(CurrentUser.userId(), id);
      return new Responses.Message("Endpoint disabled.");
    }

    private String url(String token) {
      return connectorProps.getCallbackBaseUrl() + "/api/webhooks/" + token;
    }
  }
}
