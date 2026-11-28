package com.jizza.platform.usecase.vita.medicine;

import com.jizza.engine.api.vita.VitaShares;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.sharing.VitaSection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Who hears of a person's medicines (spec 5.2, "Who receives it"), here and only here. The slot
 * reminder and the daily check go to the person themself when a Family member is linked to the
 * record with {@code as_self} and Edit on medicines, otherwise to the manager. The one grouped
 * missed alert goes to the manager and to helpers with Edit on medicines. Refill and other
 * reminders go to the manager.
 */
@Service
public class VitaReminderRecipients {

  /** One person to tell, and whether they are the person the record is about ("Your ..."). */
  public record Recipient(UUID userId, boolean thePerson) {}

  private final VitaShares shares;

  public VitaReminderRecipients(VitaShares shares) {
    this.shares = shares;
  }

  /** Who gets the reminder for a time slot and the daily check. */
  public List<Recipient> forSlot(PersonScope p) {
    for (VitaShares.Share s : medicineShares(p)) {
      if (s.asSelf() && s.edit()) return List.of(new Recipient(s.granteeUserId(), true));
    }
    return List.of(manager(p));
  }

  /** Who gets the one grouped missed alert. */
  public List<Recipient> forMissed(PersonScope p) {
    List<Recipient> out = new ArrayList<>();
    out.add(manager(p));
    for (VitaShares.Share s : medicineShares(p)) {
      if (!s.asSelf() && s.edit()) out.add(new Recipient(s.granteeUserId(), false));
    }
    return out;
  }

  /** Who gets refill and other reminders. */
  public List<Recipient> forOther(PersonScope p) {
    return List.of(manager(p));
  }

  private static Recipient manager(PersonScope p) {
    return new Recipient(p.owner(), "self".equals(p.person().kind()));
  }

  private List<VitaShares.Share> medicineShares(PersonScope p) {
    return shares.activeOf(p.owner(), p.personId()).stream()
        .filter(s -> VitaSection.MEDICINES.key().equals(s.section())).toList();
  }
}
