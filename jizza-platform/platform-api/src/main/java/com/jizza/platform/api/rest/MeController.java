package com.jizza.platform.api.rest;

import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.workspace.AuthorizationSnapshot;
import com.jizza.engine.api.workspace.WorkspaceSummary;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.SessionCookies;
import com.jizza.engine.security.session.SessionService;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.workspace.MembershipUseCase;
import com.jizza.platform.usecase.workspace.WorkspaceUseCase;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person: which account they are acting in, what they may do there, switching
 * accounts, and the membership requests waiting for them.
 *
 * <p>Switching replaces the session (a new cookie, the old session revoked), so a token captured in
 * one account can never be carried into another.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/me")
public class MeController {

  private final PermissionEvaluator permissions;
  private final WorkspaceUseCase workspaces;
  private final MembershipUseCase memberships;
  private final SessionService sessions;
  private final SessionCookies cookies;

  public MeController(PermissionEvaluator permissions, WorkspaceUseCase workspaces,
                      MembershipUseCase memberships, SessionService sessions,
                      SessionCookies cookies) {
    this.permissions = permissions;
    this.workspaces = workspaces;
    this.memberships = memberships;
    this.sessions = sessions;
    this.cookies = cookies;
  }

  @GetMapping("/context")
  public AuthorizationSnapshot context() {
    var principal = CurrentUser.require();
    return permissions.snapshot(principal.userId(), principal.activeWorkspaceId());
  }

  @PostMapping("/active-workspace")
  public ResponseEntity<AuthorizationSnapshot> switchWorkspace(
      @Valid @RequestBody Requests.SwitchWorkspace request) {
    var principal = CurrentUser.require();
    workspaces.switchTo(principal.userId(), request.workspaceId());
    var issued = sessions.switchWorkspace(principal.sessionId(), request.workspaceId());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(issued.token()).toString())
        .body(permissions.snapshot(principal.userId(), request.workspaceId()));
  }

  @GetMapping("/invitations")
  public List<Responses.ReceivedInvitation> invitations() {
    return memberships.received(CurrentUser.userId()).stream()
        .map(i -> new Responses.ReceivedInvitation(i.id(), i.workspaceId(), i.workspaceName(),
            i.role(), i.invitedByEmail(), i.createdAt(), i.expiresAt()))
        .toList();
  }

  @PostMapping("/invitations/{id}/accept")
  public WorkspaceSummary accept(@PathVariable UUID id) {
    return memberships.accept(CurrentUser.userId(), id);
  }

  @PostMapping("/invitations/{id}/decline")
  public Responses.Message decline(@PathVariable UUID id) {
    memberships.decline(CurrentUser.userId(), id);
    return new Responses.Message("Request declined.");
  }
}
