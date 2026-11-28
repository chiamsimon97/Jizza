package com.jizza.platform.usecase.vita.medicine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.scheduler.RecurrenceDays;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.engine.api.vita.VitaDoseStore.DoseRow;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What Vita's two jobs do (spec 5.2, 5.12, 5.13), each safe to run twice and with every instant
 * passed in so tests can run a day in a second.
 *
 * <p>Every minute: make the next rows if the nightly run did not, send one reminder per person per
 * time slot ({@code each_slot}), mark slots missed {@code reminder.missed_after_minutes} after they
 * were due and send one grouped missed alert, send the daily check ({@code daily_check}) and the
 * other reminders that are due. A person with reminders {@code off} gets nothing and nothing is
 * marked missed. In {@code daily_check} mode slots are still marked missed, so adherence counts
 * them, but the daily check replaces the per-slot alerts. A closed record is skipped. Nightly:
 * stop courses past their end date, make 48 hours of rows, check refills and set visit reminders.
 *
 * <p>The log names only user ids and counts.
 */
@Service
public class VitaReminderService {

  private static final Logger log = LoggerFactory.getLogger(VitaReminderService.class);
  /** How far back a slot reminder or a missed slot is still looked for. */
  private static final Duration LOOK_BACK = Duration.ofDays(2);

  private final VitaDoseStore doses;
  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaMedicineUseCase medicines;
  private final VitaReminderNotifier notifier;
  private final RecurrenceDays days;
  private final com.jizza.engine.api.finance.FinanceStore people;
  private final com.jizza.platform.usecase.vita.insurance.VitaInsuranceUseCase insurance;

  public VitaReminderService(VitaDoseStore doses, VitaRecords records, VitaScopeResolver scopes,
                             VitaMedicineUseCase medicines, VitaReminderNotifier notifier, RecurrenceDays days,
                             com.jizza.engine.api.finance.FinanceStore people,
                             com.jizza.platform.usecase.vita.insurance.VitaInsuranceUseCase insurance) {
    this.people = people;
    this.insurance = insurance;
    this.doses = doses;
    this.records = records;
    this.scopes = scopes;
    this.medicines = medicines;
    this.notifier = notifier;
    this.days = days;
  }

  public record MinuteRun(int expanded, int slotReminders, int missedDoses, int missedAlerts, int dailyChecks,
                          int otherReminders) {}

  public record NightRun(int people, int stopped, int expanded, int refills, int visitReminders) {}

  /** A person as the jobs see them; empty when gone or the record is closed. */
  private final class People {
    private final Set<UUID> only;

    People(Set<UUID> only) {
      this.only = only;
    }

    boolean includes(UUID owner) {
      return only == null || only.contains(owner);
    }

    List<UUID> owners(List<UUID> all) {
      return all.stream().filter(this::includes).toList();
    }

    private final Map<String, Optional<PersonScope>> scopes = new HashMap<>();
    private final Map<String, VitaMedicineUseCase.PersonSettings> settings = new HashMap<>();
    private final Map<UUID, ReminderSettings> owners = new HashMap<>();

    Optional<PersonScope> get(UUID owner, UUID personId) {
      return scopes.computeIfAbsent(owner + "/" + personId, k -> {
        try {
          PersonScope p = VitaReminderService.this.scopes.person(owner, personId);
          return p.open() ? Optional.of(p) : Optional.empty();
        } catch (RuntimeException e) {
          return Optional.empty();
        }
      });
    }

    VitaMedicineUseCase.PersonSettings settings(PersonScope p) {
      return settings.computeIfAbsent(p.owner() + "/" + p.personId(), k -> medicines.settings(p));
    }

    ReminderSettings owner(UUID owner) {
      return owners.computeIfAbsent(owner, medicines::reminderSettings);
    }
  }

  private record Slot(UUID owner, UUID personId, Instant dueAt) {}

  // ── Every minute ───────────────────────────────────────────────────────────

  public MinuteRun minute(Instant now) {
    return minute(now, null);
  }

  /** One run for the given owners only ({@code null}: everyone), so a test sees only its own. */
  public MinuteRun minute(Instant now, Set<UUID> only) {
    People people = new People(only);
    int expanded = 0;
    for (UUID owner : people.owners(doses.ownersWithSchedules())) {
      try {
        Optional<Instant> latest = doses.latestDue(owner);
        if (latest.isEmpty() || latest.get().isBefore(now.plus(Duration.ofHours(24)))) expanded += expandOwner(owner, now);
      } catch (RuntimeException e) {
        log.warn("Vita dose rows could not be made for user {}: {}", owner, e.getClass().getSimpleName());
      }
    }
    int reminders = slotReminders(people, now);
    int[] missed = missed(people, now);
    int checks = dailyChecks(people, now);
    int other = otherReminders(people, now);
    return new MinuteRun(expanded, reminders, missed[0], missed[1], checks, other);
  }

  private static Map<Slot, List<DoseRow>> bySlot(List<DoseRow> rows) {
    Map<Slot, List<DoseRow>> out = new LinkedHashMap<>();
    rows.forEach(r -> out.computeIfAbsent(new Slot(r.userId(), r.personId(), r.dueAt()), k -> new ArrayList<>()).add(r));
    return out;
  }

  int slotReminders(People people, Instant now) {
    int sent = 0;
    for (var e : bySlot(doses.toRemind(now, now.minus(LOOK_BACK)).stream().filter(r -> people.includes(r.userId())).toList()).entrySet()) {
      Slot s = e.getKey();
      try {
        Optional<PersonScope> p = people.get(s.owner(), s.personId());
        if (p.isEmpty()) continue;
        var ps = people.settings(p.get());
        ReminderSettings rs = people.owner(s.owner());
        List<UUID> ids = e.getValue().stream().map(DoseRow::id).toList();
        boolean late = !s.dueAt().plus(rs.missedAfter()).isAfter(now);
        if (!"each_slot".equals(ps.mode()) || late) {
          doses.markReminded(ids, now);
          continue;
        }
        if (doses.markReminded(ids, now).isEmpty()) continue;
        List<String> names = rs.showMedicineNames() ? names(s.owner(), e.getValue()) : List.of();
        notifier.slot(p.get(), s.dueAt(), ps.zone(), names);
        sent++;
      } catch (RuntimeException ex) {
        log.warn("Vita slot reminder failed for user {}: {}", s.owner(), ex.getClass().getSimpleName());
      }
    }
    return sent;
  }

  private List<String> names(UUID owner, List<DoseRow> rows) {
    Set<String> out = new LinkedHashSet<>();
    for (DoseRow r : rows) records.get("vita_medicine", owner, r.medicineId()).ifPresent(m -> out.add(m.path("name").asText()));
    return List.copyOf(out);
  }

  /** Returns {doses marked missed, alerts sent}. */
  int[] missed(People people, Instant now) {
    int marked = 0;
    int alerts = 0;
    for (var e : bySlot(doses.overdue(now, now.minus(LOOK_BACK)).stream().filter(r -> people.includes(r.userId())).toList()).entrySet()) {
      Slot s = e.getKey();
      try {
        Optional<PersonScope> p = people.get(s.owner(), s.personId());
        if (p.isEmpty()) continue;
        var ps = people.settings(p.get());
        if ("off".equals(ps.mode())) continue;
        if (s.dueAt().plus(people.owner(s.owner()).missedAfter()).isAfter(now)) continue;
        List<UUID> ids = doses.markMissed(e.getValue().stream().map(DoseRow::id).toList());
        marked += ids.size();
        if (ids.isEmpty() || !"each_slot".equals(ps.mode())) continue;
        if (doses.markMissedAlerted(ids, now).isEmpty()) continue;
        notifier.missed(p.get(), s.dueAt(), ps.zone());
        alerts++;
      } catch (RuntimeException ex) {
        log.warn("Vita missed check failed for user {}: {}", s.owner(), ex.getClass().getSimpleName());
      }
    }
    return new int[] {marked, alerts};
  }

  int dailyChecks(People people, Instant now) {
    int sent = 0;
    for (UUID owner : people.owners(doses.ownersWithSchedules())) {
      try {
        ReminderSettings rs = people.owner(owner);
        for (ObjectNode profile : records.list("vita_profile", owner, Map.of("reminderMode", "daily_check"))) {
          UUID personId = UUID.fromString(profile.path("personId").asText());
          Optional<PersonScope> p = people.get(owner, personId);
          if (p.isEmpty()) continue;
          ZoneId zone = people.settings(p.get()).zone();
          LocalDate today = now.atZone(zone).toLocalDate();
          Instant due = today.atTime(rs.dailyCheckTime()).atZone(zone).toInstant();
          Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
          if (now.isBefore(due) || !now.isBefore(dayEnd)) continue;
          Map<String, Object> where = new LinkedHashMap<>();
          where.put("personId", personId);
          where.put("kind", "daily_check");
          boolean done = records.list("vita_reminder", owner, where).stream()
              .anyMatch(r -> due.equals(VitaMedicineUseCase.instant(r.path("dueAt").asText())));
          if (done) continue;
          if (doses.forPerson(owner, personId, today.atStartOfDay(zone).toInstant(), dayEnd).isEmpty()) continue;
          records.insert("vita_reminder", owner, Json.obj().put("personId", personId.toString()).put("kind", "daily_check")
              .put("dueAt", due.toString()).put("status", "sent").put("sentAt", now.toString()).put("source", "manual"));
          notifier.dailyCheck(p.get());
          sent++;
        }
      } catch (RuntimeException ex) {
        log.warn("Vita daily check failed for user {}: {}", owner, ex.getClass().getSimpleName());
      }
    }
    return sent;
  }

  int otherReminders(People people, Instant now) {
    int sent = 0;
    for (VitaDoseStore.DueReminder d : doses.remindersDue(now)) {
      if (!people.includes(d.userId())) continue;
      try {
        ObjectNode r = records.get("vita_reminder", d.userId(), d.id()).orElse(null);
        if (r == null || !"scheduled".equals(r.path("status").asText())) continue;
        Optional<PersonScope> p = people.get(d.userId(), d.personId());
        if (p.isEmpty()) {
          records.update("vita_reminder", d.userId(), d.id(), Json.obj().put("status", "cancelled"));
          continue;
        }
        ZoneId zone = people.settings(p.get()).zone();
        Instant dueAt = VitaMedicineUseCase.instant(r.path("dueAt").asText());
        String kind = r.path("kind").asText();
        Instant about = dueAt;
        if ("visit".equals(kind) && r.hasNonNull("refId")) {
          about = records.get("vita_visit", d.userId(), UUID.fromString(r.path("refId").asText()))
              .map(v -> VitaMedicineUseCase.instant(v.path("scheduledAt").asText())).orElse(dueAt);
        } else if ("policy_renewal".equals(kind) && r.hasNonNull("refId")) {
          about = insurance.renewsOn(d.userId(), UUID.fromString(r.path("refId").asText()))
              .map(day -> day.atTime(12, 0).atZone(zone).toInstant()).orElse(dueAt);
        }
        records.update("vita_reminder", d.userId(), d.id(), Json.obj().put("status", "sent").put("sentAt", now.toString()));
        notifier.other(p.get(), d.id(), kind, r.path("title").asText(null), about, zone);
        sent++;
        String rule = r.path("recurrence").asText(null);
        if (rule != null && !rule.isBlank()) next(r, rule, dueAt, zone).ifPresent(nextAt -> {
          ObjectNode copy = Json.obj().put("personId", d.personId().toString()).put("kind", kind)
              .put("dueAt", nextAt.toString()).put("recurrence", rule).put("status", "scheduled").put("source", "manual");
          if (r.hasNonNull("title")) copy.put("title", r.path("title").asText());
          if (r.hasNonNull("refId")) copy.put("refId", r.path("refId").asText());
          records.insert("vita_reminder", d.userId(), copy);
        });
      } catch (RuntimeException ex) {
        log.warn("Vita reminder failed for user {}: {}", d.userId(), ex.getClass().getSimpleName());
      }
    }
    return sent;
  }

  /** The next day the rule falls on after this one, at the same local time. */
  Optional<Instant> next(ObjectNode r, String rule, Instant dueAt, ZoneId zone) {
    LocalDateTime local = LocalDateTime.ofInstant(dueAt, zone);
    LocalDate first = local.toLocalDate();
    for (int i = 1; i <= 400; i++) {
      LocalDate d = first.plusDays(i);
      try {
        if (days.occursOn(rule, first, d)) return Optional.of(DoseExpander.at(d, local.toLocalTime(), zone));
      } catch (RuntimeException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  // ── Nightly ────────────────────────────────────────────────────────────────

  public NightRun nightly(Instant now) {
    return nightly(now, null);
  }

  public NightRun nightly(Instant now, Set<UUID> only) {
    int people = 0;
    int stopped = 0;
    int expanded = 0;
    int refills = 0;
    int visits = 0;
    int renewals = 0;
    Set<UUID> owners = new LinkedHashSet<>(records.ownersWithRecords());
    owners.addAll(doses.ownersWithSchedules());
    if (only != null) owners.retainAll(only);
    for (UUID owner : owners) {
      try {
        ReminderSettings rs = medicines.reminderSettings(owner);
        for (PersonScope p : openPeople(owner)) {
          people++;
          stopped += medicines.stopEnded(p, now);
          expanded += medicines.expand(p, now);
          for (ObjectNode med : medicines.medicines(p)) {
            if (medicines.refillCheck(p, med, rs, now)) refills++;
          }
          visits += visitReminders(p, rs, now);
        }
        renewals += insurance.renewals(owner, rs, now);
      } catch (RuntimeException e) {
        log.warn("Vita nightly run failed for user {}: {}", owner, e.getClass().getSimpleName());
      }
    }
    log.info("Vita nightly: {} people, {} stopped, {} dose rows, {} refills, {} visit reminders, {} renewal reminders",
        people, stopped, expanded, refills, visits, renewals);
    return new NightRun(people, stopped, expanded, refills, visits);
  }

  int expandOwner(UUID owner, Instant now) {
    int n = 0;
    for (PersonScope p : openPeople(owner)) n += medicines.expand(p, now);
    return n;
  }

  private List<PersonScope> openPeople(UUID owner) {
    List<PersonScope> out = new ArrayList<>();
    for (var person : people.people(owner)) {
      if ("huf".equals(person.kind()) || !"active".equals(person.status())) continue;
      try {
        PersonScope p = scopes.person(owner, person.id());
        if (p.open()) out.add(p);
      } catch (RuntimeException ignored) {
        // gone: nothing to do
      }
    }
    return out;
  }

  /**
   * One reminder per planned visit, {@code reminder.lead_days.visit} days before it; a visit
   * that moved moves its reminder, one cancelled or done cancels it. Returns how many were added.
   */
  int visitReminders(PersonScope p, ReminderSettings rs, Instant now) {
    int added = 0;
    int lead = rs.leadDays().getOrDefault("visit", 1);
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("personId", p.personId());
    where.put("kind", "visit");
    where.put("deletedAt", null);
    Map<String, ObjectNode> byVisit = new HashMap<>();
    records.list("vita_reminder", p.owner(), where).forEach(r -> byVisit.put(r.path("refId").asText(), r));
    for (ObjectNode v : records.list("vita_visit", p.owner(), Map.of("personId", p.personId()))) {
      ObjectNode existing = byVisit.get(v.path("id").asText());
      Instant at = VitaMedicineUseCase.instant(v.path("scheduledAt").asText(null));
      boolean live = v.path("deletedAt").isNull() && "planned".equals(v.path("status").asText()) && at != null && at.isAfter(now);
      if (!live) {
        if (existing != null && "scheduled".equals(existing.path("status").asText())) {
          records.update("vita_reminder", p.owner(), UUID.fromString(existing.path("id").asText()), Json.obj().put("status", "cancelled"));
        }
        continue;
      }
      Instant due = at.minus(Duration.ofDays(lead));
      if (due.isBefore(now)) due = now;
      if (existing == null) {
        records.insert("vita_reminder", p.owner(), Json.obj().put("personId", p.personId().toString()).put("kind", "visit")
            .put("refId", v.path("id").asText()).put("dueAt", due.toString()).put("status", "scheduled").put("source", "manual"));
        added++;
      } else if ("scheduled".equals(existing.path("status").asText())
          && !due.equals(VitaMedicineUseCase.instant(existing.path("dueAt").asText()))) {
        records.update("vita_reminder", p.owner(), UUID.fromString(existing.path("id").asText()), Json.obj().put("dueAt", due.toString()));
      }
    }
    return added;
  }
}
