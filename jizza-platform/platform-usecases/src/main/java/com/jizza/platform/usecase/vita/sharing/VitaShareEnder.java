package com.jizza.platform.usecase.vita.sharing;

import com.jizza.engine.api.event.MembershipChanged;
import com.jizza.engine.api.vita.VitaShares;
import com.jizza.platform.usecase.finance.profile.PersonStatusListener;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Ends shares when the reason for them ends (spec 5.10, Fixture J). A member who leaves the Family
 * or is removed from it loses every share they were given there and every share they gave there.
 * A record that is archived or marked deceased stops being shared with anyone. The rows stay, with
 * when and why, for the access list. Deleting an account removes its rows with it (foreign keys).
 */
@Component
public class VitaShareEnder implements PersonStatusListener {

  private final VitaShares shares;

  public VitaShareEnder(VitaShares shares) {
    this.shares = shares;
  }

  @EventListener
  public void onMembershipChanged(MembershipChanged event) {
    String reason = switch (event.kind()) {
      case LEFT -> "left_family";
      case REMOVED -> "removed";
      case ROLE_CHANGED -> null;
    };
    if (reason != null) shares.endInWorkspace(event.workspaceId(), event.userId(), reason);
  }

  @Override
  public void statusChanged(UUID owner, UUID personId, String status) {
    if (!"active".equals(status)) shares.endForPerson(owner, personId, "record_closed");
  }
}
