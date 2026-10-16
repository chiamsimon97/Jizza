package com.jizza.platform.api.rest;

import com.jizza.engine.api.authz.PermissionCatalog;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.platform.api.rest.dto.Responses;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The permission definitions from permissions.json, for labels and help text in the client. */
@RestController
@AuthenticatedOnly
public class PermissionsController {

  private final PermissionCatalog catalog;

  public PermissionsController(PermissionCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/api/permissions")
  public List<Responses.Permission> list() {
    return catalog.all().stream()
        .map(p -> new Responses.Permission(p.name(), p.label(), p.description(), p.category(),
            p.module(), p.dependencies()))
        .toList();
  }
}
