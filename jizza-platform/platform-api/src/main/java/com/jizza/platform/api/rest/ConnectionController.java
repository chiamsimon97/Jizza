package com.jizza.platform.api.rest;

import com.jizza.engine.api.connector.OAuthService;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.enums.DocFormat;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.connectors.service.ConnectionService;
import com.jizza.engine.connectors.tools.generate.ApiDocIngestService;
import com.jizza.engine.infra.config.PlatformProperties;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Connections, the OAuth handshake, and API-doc ingestion (R23-R26).
 *
 * <p>Connecting a service hands an agent live access to it, so every mutating route here is
 * step-up guarded. The callback itself is not: the provider redirects the browser to it and cannot
 * carry a step-up, which is exactly why the state is single-use, expiring and session-bound.
 */
@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

  private final ConnectionService connections;
  private final OAuthService oauth;
  private final ApiDocIngestService apiDocs;
  private final PlatformProperties platform;

  public ConnectionController(ConnectionService connections, OAuthService oauth,
                              ApiDocIngestService apiDocs, PlatformProperties platform) {
    this.connections = connections;
    this.oauth = oauth;
    this.apiDocs = apiDocs;
    this.platform = platform;
  }

  @GetMapping
  public List<ConnectionDto> list() {
    return connections.list(CurrentUser.userId());
  }

  @GetMapping("/{id}")
  public ConnectionDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PostMapping
  @com.jizza.engine.api.util.StepUpRequired
  public ConnectionDto create(@Valid @RequestBody Requests.Connection request) {
    return connections.create(CurrentUser.userId(), new ConnectionService.ConnectionRequest(
        request.serviceName(), request.authScheme(), request.clientId(), request.clientSecret(),
        request.authorizationUrl(), request.accessTokenUrl(), request.refreshTokenUrl(),
        request.scopes(), request.scopeSeparator(), request.pkce(), request.unifiedCallback(),
        request.tokenPlacement(), request.grantType(), request.baseUrl(), request.autoRefresh(),
        request.testApi(), request.revokeApi(), request.profileApi(), request.webhookVerifyApi(),
        request.extraFields()));
  }

  @PatchMapping("/{id}")
  @com.jizza.engine.api.util.StepUpRequired
  public ConnectionDto update(@PathVariable UUID id,
                              @Valid @RequestBody Requests.ConnectionPatch request) {
    owned(id);
    return connections.update(id, new ConnectionService.ConnectionRequest(request.serviceName(),
        request.authScheme(), request.clientId(), request.clientSecret(), request.authorizationUrl(),
        request.accessTokenUrl(), request.refreshTokenUrl(), request.scopes(),
        request.scopeSeparator(), request.pkce(), request.unifiedCallback(),
        request.tokenPlacement(), request.grantType(), request.baseUrl(), request.autoRefresh(),
        request.testApi(), request.revokeApi(), request.profileApi(), request.webhookVerifyApi(),
        request.extraFields()));
  }

  @DeleteMapping("/{id}")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message delete(@PathVariable UUID id) {
    owned(id);
    connections.delete(id);
    return new Responses.Message("Connection removed.");
  }

  /** Starts the handshake; the returned URL is where the browser must be sent. */
  @PostMapping("/{id}/authorize")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.AuthorizeUrl authorize(@PathVariable UUID id) {
    owned(id);
    return new Responses.AuthorizeUrl(
        oauth.startAuth(id, CurrentUser.sessionId()).toString());
  }

  @PostMapping("/{id}/test")
  public Responses.TestConnection test(@PathVariable UUID id) {
    owned(id);
    var result = oauth.test(id);
    return new Responses.TestConnection(result.ok(), result.httpStatus(), result.message());
  }

  @PostMapping("/{id}/revoke")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message revoke(@PathVariable UUID id) {
    owned(id);
    oauth.revoke(id);
    return new Responses.Message("Disconnected and tokens cleared.");
  }

  @GetMapping("/{id}/tools")
  public List<GeneratedToolDto> tools(@PathVariable UUID id) {
    owned(id);
    return apiDocs.list(id);
  }

  /**
   * Attaching an API document generates one tool per endpoint. The document is untrusted content,
   * so it is size- and type-checked here before anything parses it (R47).
   */
  @PostMapping(value = "/{id}/apidoc", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @com.jizza.engine.api.util.StepUpRequired
  public List<GeneratedToolDto> attachApiDoc(@PathVariable UUID id,
                                             @RequestParam("file") MultipartFile file,
                                             @RequestParam(required = false) String format)
      throws java.io.IOException {
    owned(id);
    if (file.isEmpty()) throw new ValidationException("No document was uploaded");
    if (file.getSize() > platform.getFiles().getMaxBytes()) {
      throw new ValidationException("API documents are limited to "
          + (platform.getFiles().getMaxBytes() / 1024 / 1024) + " MB");
    }
    return apiDocs.fromApiDoc(id, file.getBytes(), resolveFormat(format, file));
  }

  @PostMapping("/tools/{toolId}/enabled")
  @com.jizza.engine.api.util.StepUpRequired
  public GeneratedToolDto setToolEnabled(@PathVariable UUID toolId,
                                         @RequestParam boolean enabled) {
    return apiDocs.setEnabled(toolId, enabled);
  }

  private DocFormat resolveFormat(String requested, MultipartFile file) {
    if (requested != null && !requested.isBlank()) {
      try {
        return DocFormat.valueOf(requested.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new ValidationException("Unknown document format '" + requested + "'");
      }
    }
    String name = file.getOriginalFilename() == null
        ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
    if (name.endsWith(".json")) return DocFormat.OPENAPI_JSON;
    if (name.endsWith(".yaml") || name.endsWith(".yml")) return DocFormat.OPENAPI_YAML;
    return DocFormat.FREEFORM;
  }

  private ConnectionDto owned(UUID connectionId) {
    ConnectionDto connection = connections.get(connectionId);
    if (!connection.userId().equals(CurrentUser.userId())) {
      throw new NotFoundException("Connection", connectionId);
    }
    return connection;
  }
}
