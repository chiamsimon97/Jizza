package com.jizza.platform.usecase.finance.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A person's Portfolio profile: created with their "Me" on the first visit, plus the few settings
 * that belong to the whole Portfolio (Advanced, Hide amounts).
 */
@Service
public class FinProfileUseCase {

  /** The name "Me" is stored under until the person renames it. */
  public static final String SELF_NAME = "Me";

  private final FinanceStore store;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinProfileUseCase(FinanceStore store, FinScopeResolver scopes, AuditLog audit) {
    this.store = store;
    this.scopes = scopes;
    this.audit = audit;
  }

  /**
   * {@code mutedAlerts}: the kinds of Kai alert the person turned off. {@code setupMarks}: setup
   * guide steps the person marked {@code done} or {@code skipped} by hand (steps Kai can see in
   * the data are worked out on the screen). {@code setupGuideHidden}: the guide card is put away.
   */
  public record Settings(boolean advanced, boolean hideAmounts, List<String> mutedAlerts,
                         java.util.Map<String, String> setupMarks, boolean setupGuideHidden) {
    static Settings of(JsonNode json) {
      List<String> muted = new java.util.ArrayList<>();
      json.path("mutedAlerts").forEach(n -> muted.add(n.asText()));
      var marks = new java.util.TreeMap<String, String>();
      json.path("setupMarks").fields().forEachRemaining(e -> marks.put(e.getKey(), e.getValue().asText()));
      return new Settings(json.path("advanced").asBoolean(false),
          json.path("hideAmounts").asBoolean(false), List.copyOf(muted),
          java.util.Collections.unmodifiableMap(marks), json.path("setupGuideHidden").asBoolean(false));
    }
  }

  /** The setup guide's steps, as the screen names them. */
  public static final java.util.Set<String> SETUP_STEPS = java.util.Set.of("quick-start", "people",
      "goals", "plan-builder", "bank-files", "investment-files", "holdings", "asset-tree",
      "protection", "tax", "settings");

  /** How a step can be marked by hand. */
  public static final java.util.Set<String> SETUP_MARKS = java.util.Set.of("done", "skipped");

  /** The alert kinds that can be muted (5.11). */
  public static final java.util.Set<String> ALERT_KINDS = java.util.Set.of("policy_renewal",
      "loan_maturity", "gold_ltv", "child_majority", "emergency_low", "needs_verification");

  public record ProfileSummary(UUID id, String baseCurrency, long version) {}

  /** How far set-up has got: Quick start not_started, in_progress or done. */
  public record SetupProgress(String quickStart, int peopleManaged) {}

  public record ProfileView(ProfileSummary profile, List<FinanceStore.PersonRow> people,
                            Settings settings, SetupProgress setupProgress) {}

  public ProfileView get(UUID callerUserId) {
    var scope = scopes.own(callerUserId);
    boolean first = store.profile(scope.ownerUserId()).isEmpty();
    var profile = store.ensureProfile(scope.ownerUserId(), SELF_NAME);
    if (first) {
      audit.record(scope.ownerUserId(), ActorType.USER, callerUserId, "finance.profile.created",
          profile.id().toString(), null);
    }
    return view(profile, store.people(scope.ownerUserId()));
  }

  /** Changes only the settings named; anything else in the body is ignored. */
  public Settings updateSettings(UUID callerUserId, Boolean advanced, Boolean hideAmounts) {
    return updateSettings(callerUserId, advanced, hideAmounts, null);
  }

  /** As above, plus the alert kinds to mute (the whole list; null leaves it as it is). */
  public Settings updateSettings(UUID callerUserId, Boolean advanced, Boolean hideAmounts,
                                 List<String> mutedAlerts) {
    return updateSettings(callerUserId, advanced, hideAmounts, mutedAlerts, null, null);
  }

  /**
   * As above, plus setup guide marks: each named step is set to {@code done} or {@code skipped},
   * or cleared with an empty value; steps not named keep their mark. Null leaves them all.
   */
  public Settings updateSettings(UUID callerUserId, Boolean advanced, Boolean hideAmounts,
                                 List<String> mutedAlerts, java.util.Map<String, String> setupMarks,
                                 Boolean setupGuideHidden) {
    if (setupMarks != null) {
      for (var e : setupMarks.entrySet()) {
        if (!SETUP_STEPS.contains(e.getKey())) {
          throw new com.jizza.engine.api.error.ValidationException("Unknown setup step " + e.getKey() + ".");
        }
        String v = e.getValue();
        if (v != null && !v.isEmpty() && !SETUP_MARKS.contains(v)) {
          throw new com.jizza.engine.api.error.ValidationException("A setup step is done, skipped or cleared.");
        }
      }
    }
    if (mutedAlerts != null) {
      for (String k : mutedAlerts) {
        if (!ALERT_KINDS.contains(k)) {
          throw new com.jizza.engine.api.error.ValidationException("Unknown alert kind " + k + ".");
        }
      }
    }
    var scope = scopes.own(callerUserId);
    var profile = store.ensureProfile(scope.ownerUserId(), SELF_NAME);
    ObjectNode settings = profile.settings() instanceof ObjectNode o ? o.deepCopy() : Json.obj();
    if (advanced != null) settings.put("advanced", advanced);
    if (hideAmounts != null) settings.put("hideAmounts", hideAmounts);
    if (mutedAlerts != null) {
      var arr = settings.putArray("mutedAlerts");
      new java.util.TreeSet<>(mutedAlerts).forEach(arr::add);
    }
    if (setupMarks != null) {
      ObjectNode marks = settings.path("setupMarks") instanceof ObjectNode m ? m : settings.putObject("setupMarks");
      setupMarks.forEach((k, v) -> {
        if (v == null || v.isEmpty()) marks.remove(k);
        else marks.put(k, v);
      });
    }
    if (setupGuideHidden != null) settings.put("setupGuideHidden", setupGuideHidden);
    var saved = store.saveSettings(scope.ownerUserId(), settings);
    return Settings.of(saved.settings());
  }

  private static ProfileView view(FinanceStore.ProfileRow profile,
                                  List<FinanceStore.PersonRow> people) {
    int managed = (int) people.stream()
        .filter(p -> !"self".equals(p.kind()) && "active".equals(p.status())).count();
    return new ProfileView(new ProfileSummary(profile.id(), profile.baseCurrency(), profile.version()),
        people, Settings.of(profile.settings()), new SetupProgress(
        com.jizza.platform.usecase.finance.quickstart.QuickStartUseCase.status(profile.settings()), managed));
  }
}
