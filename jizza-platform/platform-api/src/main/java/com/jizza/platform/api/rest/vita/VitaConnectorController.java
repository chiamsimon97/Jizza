package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.devices.VitaConnectorUseCase;
import com.jizza.platform.usecase.vita.devices.VitaConnectorUseCase.Phone;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device grants and ingest on the web (spec 5.11, 5.12, Phase 3), behind the health switch. The
 * phones use the signed twins under {@code /agent/v1/me/vita}; a grant made here belongs to no
 * phone, and only such a grant takes a batch here.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaConnectorController {

  private final VitaConnectorUseCase connectors;

  public VitaConnectorController(VitaConnectorUseCase connectors) {
    this.connectors = connectors;
  }

  /** Every connection the caller can see, or one person's. */
  @GetMapping("/connectors")
  public List<ObjectNode> list(@RequestParam(value = "personId", required = false) UUID personId) {
    return connectors.list(CurrentUser.userId(), personId, Phone.WEB);
  }

  /** {@code {"personId","source","dataTypes":[…]}}: adds a connection or changes its data types. */
  @PostMapping("/connectors")
  public ObjectNode grant(@RequestBody JsonNode body) {
    return connectors.grant(CurrentUser.userId(), body, Phone.WEB);
  }

  /** Stops a connection, or some of its data types; {@code deleteData} removes what it wrote. */
  @DeleteMapping("/connectors/{id}")
  public ObjectNode revoke(@PathVariable UUID id, @RequestParam("personId") UUID personId,
                           @RequestParam(value = "types", required = false) List<String> types,
                           @RequestParam(value = "deleteData", defaultValue = "false") boolean deleteData) {
    return connectors.revoke(CurrentUser.userId(), personId, id, types, deleteData, Phone.WEB);
  }

  /** {@code {"personId","grantId","records":[…]}}; see docs/agent-protocol-v1.md. */
  @PostMapping("/ingest")
  public ObjectNode ingest(@RequestBody JsonNode body) {
    return connectors.ingest(CurrentUser.userId(), body, Phone.WEB);
  }
}
