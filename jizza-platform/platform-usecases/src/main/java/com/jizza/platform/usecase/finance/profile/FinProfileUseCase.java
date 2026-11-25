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

  /** {@code mutedAlerts}: the kinds of Kai alert the person turned off. */
  public record Settings(boolean advanced, boolean hideAmounts, List<String> mutedAlerts) {
    static Settings of(JsonNode json) {
      List<String> muted = new java.util.ArrayList<>();
      json.path("mutedAlerts").forEach(n -> muted.add(n.asText()));
      return new Settings(json.path("advanced").asBoolean(false),
          json.path("hideAmounts").asBoolean(false), List.copyOf(muted));
    }
  }

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
