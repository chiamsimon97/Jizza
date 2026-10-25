package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.listing.PublicRolesUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Super Admin's Registered accounts page: every account registered in the platform's Public
 * organization, and each person's role there. Platform-wide, so the check is the caller's platform
 * role, made in the use case.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/admin/accounts")
public class AdminPublicRolesController {

  private final PublicRolesUseCase roles;

  public AdminPublicRolesController(PublicRolesUseCase roles) {
    this.roles = roles;
  }

  @GetMapping
  public Responses.AccountRegistry list(@RequestParam(defaultValue = "") String q) {
    var r = roles.list(CurrentUser.userId(), q);
    return new Responses.AccountRegistry(r.workspaceId(), r.workspaceName(),
        r.accounts().stream().map(a -> new Responses.RegisteredAccount(a.workspaceId(), a.name(),
            a.type(), a.ownerUserId(), a.ownerEmail(), a.source(), a.registeredAt(),
            a.publicRole() == null ? com.jizza.engine.api.enums.WorkspaceRole.USER : a.publicRole()))
            .toList());
  }

  @PutMapping("/people/{userId}/public-role")
  public Responses.PublicRole setRole(@PathVariable UUID userId,
                                      @Valid @RequestBody Requests.ChangeRole body) {
    return new Responses.PublicRole(userId, roles.setRole(CurrentUser.userId(), userId, body.role()));
  }
}
