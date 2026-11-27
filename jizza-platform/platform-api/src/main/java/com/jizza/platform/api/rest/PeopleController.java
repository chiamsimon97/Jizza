package com.jizza.platform.api.rest;

import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.finance.profile.FinPersonUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household people list Kai and Vita share (Vita spec 4.1): the user ("Me") and the people
 * they manage. Not behind any feature switch, so Vita works with finance off; it never creates a
 * finance profile. {@code /api/finance/people} keeps working for Kai over the same rows.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/people")
public class PeopleController {

  private final FinPersonUseCase people;

  public PeopleController(FinPersonUseCase people) {
    this.people = people;
  }

  public record PersonBody(@Size(max = 80) String displayName, @Size(max = 10) String kind,
                           @Size(max = 10) String type, LocalDate dob, Boolean isEarning,
                           Boolean isDependent) {
    FinPersonUseCase.PersonInput input() {
      return new FinPersonUseCase.PersonInput(displayName, kind, type, dob, isEarning, isDependent);
    }
  }

  /** Everyone, "Me" first. {@code forHealth=true} leaves out an HUF, which has no health record. */
  @GetMapping
  public List<PersonRow> list(@RequestParam(defaultValue = "false") boolean forHealth) {
    List<PersonRow> all = people.list(CurrentUser.userId());
    return forHealth ? all.stream().filter(p -> !"huf".equals(p.kind())).toList() : all;
  }

  @PostMapping
  public PersonRow add(@Valid @RequestBody PersonBody body) {
    return people.create(CurrentUser.userId(), body.input());
  }

  @GetMapping("/{id}")
  public PersonRow get(@PathVariable UUID id) {
    return people.get(CurrentUser.userId(), id);
  }

  @PatchMapping("/{id}")
  public PersonRow update(@PathVariable UUID id, @Valid @RequestBody PersonBody body) {
    return people.update(CurrentUser.userId(), id, body.input());
  }

  public record StatusBody(@Size(max = 12) String status, LocalDate on) {}

  /** Archived or deceased closes the person's records (with the date); active reopens them. */
  @PatchMapping("/{id}/status")
  public PersonRow status(@PathVariable UUID id, @Valid @RequestBody StatusBody body) {
    return people.setStatus(CurrentUser.userId(), id, body.status(), body.on());
  }
}
