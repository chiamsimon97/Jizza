package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.home.VitaHomeUseCase;
import com.jizza.platform.usecase.vita.measure.VitaMeasurementUseCase;
import com.jizza.platform.usecase.vita.measure.VitaRedFlagService;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.profile.VitaProfileUseCase;
import com.jizza.platform.usecase.vita.quickstart.VitaQuickStartUseCase;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vita's Health tab, Phase 0 (spec 5.12): status, Settings, Quick start, and per person the
 * profile, emergency contacts, emergency card, conditions, allergies and weight. Built behind one
 * server switch, {@code jizza.health.enabled} ({@code JIZZA_HEALTH_ENABLED}), off by default:
 * while it is off this controller is not registered, every {@code /api/vita/**} path answers 404
 * and the client hides the tab. Every route reaches only the caller's own household; another
 * account's person reads as not found.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaController {

  private final VitaConfigService config;
  private final VitaQuickStartUseCase quickStart;
  private final VitaProfileUseCase profiles;
  private final VitaRecordsUseCase forms;
  private final VitaMeasurementUseCase readings;
  private final VitaRedFlagService redFlags;
  private final VitaTargetService targets;
  private final VitaHomeUseCase home;
  private final com.jizza.platform.usecase.vita.sharing.VitaSharingUseCase sharing;

  public VitaController(VitaConfigService config, VitaQuickStartUseCase quickStart,
                        VitaProfileUseCase profiles, VitaRecordsUseCase forms,
                        VitaMeasurementUseCase readings, VitaRedFlagService redFlags,
                        VitaTargetService targets, VitaHomeUseCase home,
                        com.jizza.platform.usecase.vita.sharing.VitaSharingUseCase sharing) {
    this.sharing = sharing;
    this.config = config;
    this.quickStart = quickStart;
    this.profiles = profiles;
    this.forms = forms;
    this.readings = readings;
    this.redFlags = redFlags;
    this.targets = targets;
    this.home = home;
  }

  /**
   * What the client needs to decide whether to show the Health tab. {@code openOn} is
   * "my-medicines" for a member linked to their own record with Edit on medicines who has not
   * turned that off (setting ui.open_my_medicines), otherwise null.
   */
  public record VitaStatus(boolean enabled, String openOn) {}

  @GetMapping("/status")
  public VitaStatus status() {
    UUID me = CurrentUser.userId();
    boolean open = sharing.linkedAsSelfWithMedicines(me)
        && config.value(me, "ui.open_my_medicines").map(v -> v.path("enabled").asBoolean(true)).orElse(true);
    return new VitaStatus(true, open ? "my-medicines" : null);
  }

  // ── Settings ────────────────────────────────────────────────────────────────

  @GetMapping("/config")
  public List<VitaConfigService.ConfigItem> config() {
    return config.list(CurrentUser.userId());
  }

  public record ConfigBody(@NotNull JsonNode value) {}

  @PutMapping("/config/{key}")
  public VitaConfigService.ConfigItem putConfig(@PathVariable String key, @Valid @RequestBody ConfigBody body) {
    return config.put(CurrentUser.userId(), key, body.value());
  }

  /** "Reset to Vita default". */
  @DeleteMapping("/config/{key}")
  public VitaConfigService.ConfigItem resetConfig(@PathVariable String key) {
    return config.reset(CurrentUser.userId(), key);
  }

  // ── Quick start ─────────────────────────────────────────────────────────────

  @GetMapping("/quick-start")
  public VitaQuickStartUseCase.QuickStartView quickStart(@RequestParam(required = false) UUID personId) {
    return quickStart.get(CurrentUser.userId(), personId);
  }

  public record QuickStartBody(UUID personId, @NotNull @Size(max = 20) String step, JsonNode answers,
                               Boolean skip) {}

  @PostMapping("/quick-start")
  public VitaQuickStartUseCase.QuickStartView saveQuickStart(@Valid @RequestBody QuickStartBody body) {
    return quickStart.save(CurrentUser.userId(), body.personId(), body.step(), body.answers(),
        Boolean.TRUE.equals(body.skip()));
  }

  // ── Profile and emergency card ──────────────────────────────────────────────

  @GetMapping("/people/{personId}/profile")
  public VitaProfileUseCase.ProfileView profile(@PathVariable UUID personId) {
    return profiles.get(CurrentUser.userId(), personId);
  }

  @PutMapping("/people/{personId}/profile")
  public VitaProfileUseCase.ProfileView saveProfile(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return profiles.put(CurrentUser.userId(), personId, body);
  }

  @GetMapping("/people/{personId}/emergency-card")
  public ObjectNode emergencyCard(@PathVariable UUID personId) {
    return profiles.emergencyCard(CurrentUser.userId(), personId);
  }

  // ── Emergency contacts, conditions, allergies ──────────────────────────────

  @GetMapping("/people/{personId}/emergency-contacts")
  public List<ObjectNode> contacts(@PathVariable UUID personId) {
    return forms.list(CurrentUser.userId(), personId, VitaTable.CONTACT);
  }

  @PostMapping("/people/{personId}/emergency-contacts")
  public ObjectNode addContact(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return forms.create(CurrentUser.userId(), personId, VitaTable.CONTACT, body, "manual");
  }

  @PutMapping("/people/{personId}/emergency-contacts/{id}")
  public ObjectNode updateContact(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return forms.update(CurrentUser.userId(), personId, VitaTable.CONTACT, id, body);
  }

  @DeleteMapping("/people/{personId}/emergency-contacts/{id}")
  public void removeContact(@PathVariable UUID personId, @PathVariable UUID id) {
    forms.delete(CurrentUser.userId(), personId, VitaTable.CONTACT, id);
  }

  @GetMapping("/people/{personId}/conditions")
  public List<ObjectNode> conditions(@PathVariable UUID personId) {
    return forms.list(CurrentUser.userId(), personId, VitaTable.CONDITION);
  }

  @PostMapping("/people/{personId}/conditions")
  public ObjectNode addCondition(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return forms.create(CurrentUser.userId(), personId, VitaTable.CONDITION, body, "manual");
  }

  @PutMapping("/people/{personId}/conditions/{id}")
  public ObjectNode updateCondition(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return forms.update(CurrentUser.userId(), personId, VitaTable.CONDITION, id, body);
  }

  @DeleteMapping("/people/{personId}/conditions/{id}")
  public void removeCondition(@PathVariable UUID personId, @PathVariable UUID id) {
    forms.delete(CurrentUser.userId(), personId, VitaTable.CONDITION, id);
  }

  @GetMapping("/people/{personId}/allergies")
  public List<ObjectNode> allergies(@PathVariable UUID personId) {
    return forms.list(CurrentUser.userId(), personId, VitaTable.ALLERGY);
  }

  @PostMapping("/people/{personId}/allergies")
  public ObjectNode addAllergy(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return forms.create(CurrentUser.userId(), personId, VitaTable.ALLERGY, body, "manual");
  }

  @PutMapping("/people/{personId}/allergies/{id}")
  public ObjectNode updateAllergy(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return forms.update(CurrentUser.userId(), personId, VitaTable.ALLERGY, id, body);
  }

  @DeleteMapping("/people/{personId}/allergies/{id}")
  public void removeAllergy(@PathVariable UUID personId, @PathVariable UUID id) {
    forms.delete(CurrentUser.userId(), personId, VitaTable.ALLERGY, id);
  }

  // ── Readings (weight in Phase 0) ────────────────────────────────────────────

  @GetMapping("/people/{personId}/measurements")
  public List<ObjectNode> measurements(@PathVariable UUID personId, @RequestParam(required = false) String kind) {
    return readings.list(CurrentUser.userId(), personId, kind);
  }

  /**
   * Adds a reading. {@code secondOf} joins it to a first reading taken just before; the same
   * reading saved a moment ago answers 409 "Already saved. Add anyway?" unless
   * {@code confirmDuplicate}.
   */
  @PostMapping("/people/{personId}/measurements")
  public ObjectNode addMeasurement(@PathVariable UUID personId, @RequestBody JsonNode body,
                                   @RequestParam(required = false) UUID secondOf,
                                   @RequestParam(required = false, defaultValue = "false") boolean confirmDuplicate) {
    return readings.create(CurrentUser.userId(), personId, body,
        new VitaMeasurementUseCase.Options(secondOf, confirmDuplicate));
  }

  @GetMapping("/people/{personId}/measurements/trend")
  public ObjectNode trend(@PathVariable UUID personId, @RequestParam String kind,
                          @RequestParam(required = false) Integer days,
                          @RequestParam(required = false) String context) {
    return home.trend(CurrentUser.userId(), personId, kind, days, context);
  }

  @GetMapping("/people/{personId}/measurements/{id}")
  public ObjectNode measurement(@PathVariable UUID personId, @PathVariable UUID id) {
    return readings.get(CurrentUser.userId(), personId, id);
  }

  public record RedFlagAnswer(@NotNull Boolean right, Boolean signsNow) {}

  /** The answer to "Is 182/112 right?" and to the rule's question; a yes alerts the manager. */
  @PostMapping("/people/{personId}/measurements/{id}/red-flag")
  public ObjectNode confirmRedFlag(@PathVariable UUID personId, @PathVariable UUID id,
                                   @Valid @RequestBody RedFlagAnswer body) {
    return redFlags.confirm(CurrentUser.userId(), personId, id, body.right(), body.signsNow());
  }

  @PutMapping("/people/{personId}/measurements/{id}")
  public ObjectNode updateMeasurement(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return readings.update(CurrentUser.userId(), personId, id, body);
  }

  @DeleteMapping("/people/{personId}/measurements/{id}")
  public void removeMeasurement(@PathVariable UUID personId, @PathVariable UUID id) {
    readings.delete(CurrentUser.userId(), personId, id);
  }

  public record MoveBody(@NotNull UUID toPersonId) {}

  /** A reading entered for the wrong person goes to the right one. */
  @PostMapping("/people/{personId}/measurements/{id}/move")
  public ObjectNode moveMeasurement(@PathVariable UUID personId, @PathVariable UUID id,
                                    @Valid @RequestBody MoveBody body) {
    return readings.move(CurrentUser.userId(), personId, id, body.toPersonId());
  }

  // ── Home, ranges, condition pages ───────────────────────────────────────────

  @GetMapping("/people/{personId}/home")
  public ObjectNode home(@PathVariable UUID personId) {
    return home.home(CurrentUser.userId(), personId);
  }

  @GetMapping("/people/{personId}/ranges")
  public List<VitaTargetService.RangeView> ranges(@PathVariable UUID personId) {
    return targets.ranges(CurrentUser.userId(), personId);
  }

  /** Sets the person's own range or a doctor's target for one measure. */
  @PutMapping("/people/{personId}/ranges")
  public ObjectNode putRange(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return targets.putRange(CurrentUser.userId(), personId, body);
  }

  @DeleteMapping("/people/{personId}/ranges/{id}")
  public void removeRange(@PathVariable UUID personId, @PathVariable UUID id) {
    targets.deleteRange(CurrentUser.userId(), personId, id);
  }

  @GetMapping("/people/{personId}/conditions/{id}/summary")
  public ObjectNode conditionSummary(@PathVariable UUID personId, @PathVariable UUID id) {
    return home.conditionSummary(CurrentUser.userId(), personId, id);
  }

  // ── Doctors (household-wide) and visits ─────────────────────────────────────

  @GetMapping("/doctors")
  public List<ObjectNode> doctors() {
    return home.doctors(CurrentUser.userId());
  }

  @PostMapping("/doctors")
  public ObjectNode addDoctor(@RequestBody JsonNode body) {
    return forms.createHousehold(CurrentUser.userId(), VitaTable.DOCTOR, body, "manual");
  }

  @GetMapping("/doctors/{id}")
  public ObjectNode doctor(@PathVariable UUID id) {
    return forms.getHousehold(CurrentUser.userId(), VitaTable.DOCTOR, id);
  }

  @PutMapping("/doctors/{id}")
  public ObjectNode updateDoctor(@PathVariable UUID id, @RequestBody JsonNode body) {
    return forms.updateHousehold(CurrentUser.userId(), VitaTable.DOCTOR, id, body);
  }

  @DeleteMapping("/doctors/{id}")
  public void removeDoctor(@PathVariable UUID id) {
    forms.deleteHousehold(CurrentUser.userId(), VitaTable.DOCTOR, id);
  }

  @GetMapping("/people/{personId}/visits")
  public List<ObjectNode> visits(@PathVariable UUID personId) {
    return forms.list(CurrentUser.userId(), personId, VitaTable.VISIT);
  }

  @PostMapping("/people/{personId}/visits")
  public ObjectNode addVisit(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return forms.create(CurrentUser.userId(), personId, VitaTable.VISIT, body, "manual");
  }

  @PutMapping("/people/{personId}/visits/{id}")
  public ObjectNode updateVisit(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return forms.update(CurrentUser.userId(), personId, VitaTable.VISIT, id, body);
  }

  @DeleteMapping("/people/{personId}/visits/{id}")
  public void removeVisit(@PathVariable UUID personId, @PathVariable UUID id) {
    forms.delete(CurrentUser.userId(), personId, VitaTable.VISIT, id);
  }
}
