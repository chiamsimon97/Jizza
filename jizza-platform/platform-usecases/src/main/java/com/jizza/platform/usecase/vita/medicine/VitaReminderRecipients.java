package com.jizza.platform.usecase.vita.medicine;

import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Who hears of a person's medicines (spec 5.2, "Who receives it"). Until sharing arrives in Phase
 * 2a there are no shares, so every slot reminder, refill reminder and missed alert goes to the
 * manager. Phase 2a adds the person themself (linked with {@code as_self} and Edit on medicines)
 * for slot reminders, and helpers with Edit on medicines for the missed alert, here and only here.
 */
@Service
public class VitaReminderRecipients {

  /** Who gets the reminder for a time slot and the daily check. */
  public List<UUID> forSlot(PersonScope p) {
    return List.of(p.owner());
  }

  /** Who gets the one grouped missed alert. */
  public List<UUID> forMissed(PersonScope p) {
    return List.of(p.owner());
  }

  /** Who gets refill and other reminders. */
  public List<UUID> forOther(PersonScope p) {
    return List.of(p.owner());
  }
}
