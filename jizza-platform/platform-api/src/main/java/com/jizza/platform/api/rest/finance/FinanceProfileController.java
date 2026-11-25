package com.jizza.platform.api.rest.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.finance.FinanceStore.ConsentRow;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.finance.config.FinConfigService;
import com.jizza.platform.usecase.finance.profile.ConsentService;
import com.jizza.platform.usecase.finance.profile.FinPersonUseCase;
import com.jizza.platform.usecase.finance.profile.FinProfileUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kai Portfolio, Phase 0: the profile, the people a person manages, consent notes and Settings.
 * Every route is the caller's own data only; another account's ids read as not found. Like
 * {@code FinanceController}, it exists only while {@code jizza.finance.enabled} is on.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/finance")
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinanceProfileController {

  private final FinProfileUseCase profiles;
  private final FinPersonUseCase people;
  private final ConsentService consents;
  private final FinConfigService config;

  public FinanceProfileController(FinProfileUseCase profiles, FinPersonUseCase people,
                                  ConsentService consents, FinConfigService config) {
    this.profiles = profiles;
    this.people = people;
    this.consents = consents;
    this.config = config;
  }

  /** The profile, created with "Me" on the first call. */
  @GetMapping("/profile")
  public FinProfileUseCase.ProfileView profile() {
    return profiles.get(CurrentUser.userId());
  }

  public record SettingsBody(Boolean advanced, Boolean hideAmounts) {}

  @PatchMapping("/profile/settings")
  public FinProfileUseCase.Settings settings(@RequestBody SettingsBody body) {
    return profiles.updateSettings(CurrentUser.userId(), body.advanced(), body.hideAmounts());
  }

  public record PersonBody(@Size(max = 80) String displayName, @Size(max = 10) String kind,
                           @Size(max = 10) String type, LocalDate dob, Boolean isEarning,
                           Boolean isDependent) {
    FinPersonUseCase.PersonInput input() {
      return new FinPersonUseCase.PersonInput(displayName, kind, type, dob, isEarning, isDependent);
    }
  }

  @GetMapping("/people")
  public List<PersonRow> people() {
    return people.list(CurrentUser.userId());
  }

  @PostMapping("/people")
  public PersonRow addPerson(@Valid @RequestBody PersonBody body) {
    return people.create(CurrentUser.userId(), body.input());
  }

  @GetMapping("/people/{id}")
  public PersonRow person(@PathVariable UUID id) {
    return people.get(CurrentUser.userId(), id);
  }

  @PatchMapping("/people/{id}")
  public PersonRow updatePerson(@PathVariable UUID id, @Valid @RequestBody PersonBody body) {
    return people.update(CurrentUser.userId(), id, body.input());
  }

  /** Archives: the person's records stay, and they leave the switchers. */
  @DeleteMapping("/people/{id}")
  public PersonRow archivePerson(@PathVariable UUID id) {
    return people.archive(CurrentUser.userId(), id);
  }

  public record ConsentBody(@NotNull @Size(max = 10) String scope, @Size(max = 40) String channel,
                            UUID evidenceFileId) {}

  @GetMapping("/people/{id}/consents")
  public List<ConsentRow> consents(@PathVariable UUID id) {
    return consents.list(CurrentUser.userId(), id);
  }

  @PostMapping("/people/{id}/consents")
  public ConsentRow recordConsent(@PathVariable UUID id, @Valid @RequestBody ConsentBody body) {
    return consents.record(CurrentUser.userId(), id, body.scope(), body.channel(),
        body.evidenceFileId());
  }

  /** Withdrawing consent starts the 30-day purge, so it needs a fresh re-authentication. */
  @PostMapping("/consents/{id}/revoke")
  @StepUpRequired
  public ConsentRow revokeConsent(@PathVariable UUID id) {
    return consents.revoke(CurrentUser.userId(), id);
  }

  @GetMapping("/config")
  public List<FinConfigService.ConfigItem> config() {
    return config.list(CurrentUser.userId());
  }

  public record ConfigBody(@NotNull JsonNode value) {}

  @PutMapping("/config/{key}")
  public FinConfigService.ConfigItem putConfig(@PathVariable String key,
                                               @Valid @RequestBody ConfigBody body) {
    return config.put(CurrentUser.userId(), key, body.value());
  }

  /** "Reset to Kai default". */
  @DeleteMapping("/config/{key}")
  public FinConfigService.ConfigItem resetConfig(@PathVariable String key) {
    return config.reset(CurrentUser.userId(), key);
  }
}
