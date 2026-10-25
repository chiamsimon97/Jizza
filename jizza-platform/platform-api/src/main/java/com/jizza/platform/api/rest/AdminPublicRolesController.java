package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.workspace.Membership;
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
 * The Super Admin's Public roles page: who is an Admin, Developer or User in the platform's Public
 * organization. Platform-wide, so the check is the caller's platform role, made in the use case.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/admin/public-roles")
public class AdminPublicRolesController {

  private final PublicRolesUseCase roles;

  public AdminPublicRolesController(PublicRolesUseCase roles) {
    this.roles = roles;
  }

  @GetMapping
  public Responses.PublicRoles list(@RequestParam(defaultValue = "") String q) {
    UUID me = CurrentUser.userId();
    var r = roles.list(me, q);
    return new Responses.PublicRoles(r.workspaceId(), r.workspaceName(),
        r.members().stream().map(m -> member(m, me)).toList());
  }

  @PutMapping("/{memberId}")
  public Responses.Member setRole(@PathVariable UUID memberId,
                                  @Valid @RequestBody Requests.ChangeRole body) {
    UUID me = CurrentUser.userId();
    return member(roles.setRole(me, memberId, body.role()), me);
  }

  private static Responses.Member member(Membership m, UUID me) {
    return new Responses.Member(m.id(), m.userId(), m.email(), m.role(), m.status(), m.joinedAt(),
        m.userId().equals(me));
  }
}
