package com.jizza.platform.api.rest;

import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.api.workspace.AuthorizationSnapshot;
import com.jizza.engine.api.workspace.WorkspaceSummary;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.engine.security.session.SessionCookies;
import com.jizza.engine.security.session.SessionService;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.usecase.workspace.WorkspaceUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creating an organization account, and leaving the one the caller is acting in. */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/workspaces")
public class WorkspaceController {

  private final WorkspaceUseCase workspaces;
  private final PermissionEvaluator permissions;
  private final SessionService sessions;
  private final SessionCookies cookies;

  public WorkspaceController(WorkspaceUseCase workspaces, PermissionEvaluator permissions,
                             SessionService sessions, SessionCookies cookies) {
    this.workspaces = workspaces;
    this.permissions = permissions;
    this.sessions = sessions;
    this.cookies = cookies;
  }

  /** The caller becomes its owner. The session is not switched; the client offers that. */
  @PostMapping
  @StepUpRequired
  public WorkspaceSummary create(@Valid @RequestBody Requests.CreateOrganization request) {
    return workspaces.createOrganization(CurrentUser.userId(), request.name());
  }

  /** Creates a Family (spec 4.4), at most one per owner. The session is not switched. */
  @PostMapping("/families")
  @StepUpRequired
  public WorkspaceSummary createFamily(@Valid @RequestBody Requests.CreateOrganization request) {
    return workspaces.createFamily(CurrentUser.userId(), request.name());
  }

  /** Leaves the active organization and moves this session back to the Individual account. */
  @DeleteMapping("/current/membership")
  public ResponseEntity<AuthorizationSnapshot> leave() {
    var principal = CurrentUser.require();
    var personal = workspaces.leave(principal.userId(), CurrentWorkspace.id());
    var issued = sessions.switchWorkspace(principal.sessionId(), personal);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(issued.token()).toString())
        .body(permissions.snapshot(principal.userId(), personal));
  }
}
