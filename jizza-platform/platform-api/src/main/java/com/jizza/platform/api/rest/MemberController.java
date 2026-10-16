package com.jizza.platform.api.rest;

import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.workspace.MembershipUseCase;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Members tab of an organization account: who belongs, the requests sent, and inviting,
 * re-roling and removing people. Always about the account the caller is acting in.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/workspaces/current")
public class MemberController {

  private final MembershipUseCase memberships;

  public MemberController(MembershipUseCase memberships) {
    this.memberships = memberships;
  }

  @GetMapping("/members")
  @RequiresPermission(Permissions.MEMBERS_VIEW)
  public List<Responses.Member> members() {
    var access = CurrentWorkspace.access();
    return memberships.members(access).stream().map(m -> member(m, access.userId())).toList();
  }

  @GetMapping("/invitations")
  @RequiresPermission(Permissions.MEMBERS_VIEW)
  public List<Responses.Invitation> invitations() {
    return memberships.invitations(CurrentWorkspace.access()).stream()
        .map(i -> new Responses.Invitation(i.id(), i.email(), i.role(), i.status(),
            i.invitedByEmail(), i.createdAt(), i.expiresAt(), i.respondedAt()))
        .toList();
  }

  /**
   * Answers the same way whether or not the address has an account, so this cannot be used to
   * find out who is registered.
   */
  @PostMapping("/invitations")
  @RequiresPermission(Permissions.MEMBERS_INVITE)
  @StepUpRequired
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Responses.Message invite(@Valid @RequestBody Requests.InviteMember request) {
    memberships.invite(CurrentWorkspace.access(), request.email(), request.role());
    return new Responses.Message("Request sent.");
  }

  @PostMapping("/invitations/{id}/revoke")
  @RequiresPermission(Permissions.MEMBERS_INVITE)
  public Responses.Message revoke(@PathVariable UUID id) {
    memberships.revoke(CurrentWorkspace.access(), id);
    return new Responses.Message("Request withdrawn.");
  }

  @PatchMapping("/members/{memberId}")
  @RequiresPermission(Permissions.MEMBERS_MODIFY)
  @StepUpRequired
  public Responses.Member changeRole(@PathVariable UUID memberId,
                                     @Valid @RequestBody Requests.ChangeRole request) {
    var access = CurrentWorkspace.access();
    return member(memberships.changeRole(access, memberId, request.role()), access.userId());
  }

  @DeleteMapping("/members/{memberId}")
  @RequiresPermission(Permissions.MEMBERS_REMOVE)
  @StepUpRequired
  public Responses.Message remove(@PathVariable UUID memberId) {
    memberships.remove(CurrentWorkspace.access(), memberId);
    return new Responses.Message("Member removed.");
  }

  private static Responses.Member member(Membership m, UUID caller) {
    return new Responses.Member(m.id(), m.userId(), m.email(), m.role(), m.status(), m.joinedAt(),
        m.userId().equals(caller));
  }
}
