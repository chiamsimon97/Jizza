package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemorySettings;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.memory.MemoryUseCase;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's memory: facts Jizza keeps about them, the people, accounts and plans those facts are
 * grouped around, consent settings, and a preview of what an agent would be given for a request.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/memory")
public class MemoryController {

  private final MemoryUseCase memory;

  public MemoryController(MemoryUseCase memory) {
    this.memory = memory;
  }

  @GetMapping
  public List<Memory> list(@RequestParam(required = false) MemoryCategory category,
                           @RequestParam(required = false) MemoryStatus status,
                           @RequestParam(required = false) UUID subjectId,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) Integer limit) {
    return memory.list(CurrentUser.userId(), category, status, subjectId, q, limit);
  }

  @PostMapping
  public MemoryWrite create(@Valid @RequestBody Requests.MemoryInput input) {
    return memory.create(CurrentUser.userId(), input.toDraft());
  }

  @GetMapping("/{id}")
  public MemoryUseCase.MemoryDetail detail(@PathVariable UUID id) {
    return memory.detail(CurrentUser.userId(), id);
  }

  /** The user correcting a fact: kept in its history, and confirmed from then on. */
  @PatchMapping("/{id}")
  public Memory correct(@PathVariable UUID id, @Valid @RequestBody Requests.MemoryInput input) {
    return memory.correct(CurrentUser.userId(), id, input.toDraft());
  }

  @PostMapping("/{id}/confirm")
  public Memory confirm(@PathVariable UUID id) {
    return memory.confirm(CurrentUser.userId(), id);
  }

  @PostMapping("/{id}/retire")
  public Memory retire(@PathVariable UUID id) {
    return memory.retire(CurrentUser.userId(), id);
  }

  @PostMapping("/{id}/restore")
  public Memory restore(@PathVariable UUID id) {
    return memory.restore(CurrentUser.userId(), id);
  }

  /** Permanent, so it asks for a fresh step-up like the other irreversible actions. */
  @com.jizza.engine.api.util.StepUpRequired
  @DeleteMapping("/{id}")
  public Responses.Message forget(@PathVariable UUID id) {
    memory.forget(CurrentUser.userId(), id);
    return new Responses.Message("Forgotten.");
  }

  @GetMapping("/subjects")
  public List<MemorySubject> subjects() {
    return memory.subjects(CurrentUser.userId());
  }

  @PostMapping("/subjects")
  public MemorySubject createSubject(@Valid @RequestBody Requests.SubjectInput input) {
    return memory.createSubject(CurrentUser.userId(), input.toDraft());
  }

  @PatchMapping("/subjects/{id}")
  public MemorySubject updateSubject(@PathVariable UUID id, @Valid @RequestBody Requests.SubjectInput input) {
    return memory.updateSubject(CurrentUser.userId(), id, input.toDraft());
  }

  /** The subject goes; facts about it stay, unlinked from it. */
  @DeleteMapping("/subjects/{id}")
  public Responses.Message deleteSubject(@PathVariable UUID id) {
    memory.deleteSubject(CurrentUser.userId(), id);
    return new Responses.Message("Removed.");
  }

  @PostMapping("/subjects/{id}/relations")
  public MemorySubject.Relation relate(@PathVariable UUID id,
                                       @Valid @RequestBody Requests.SubjectRelation input) {
    return memory.relate(CurrentUser.userId(), id, input.toSubjectId(), input.relation());
  }

  @DeleteMapping("/relations/{relationId}")
  public Responses.Message unrelate(@PathVariable UUID relationId) {
    memory.unrelate(CurrentUser.userId(), relationId);
    return new Responses.Message("Unlinked.");
  }

  @GetMapping("/settings")
  public MemorySettings settings() {
    return memory.settings(CurrentUser.userId());
  }

  @PutMapping("/settings")
  public MemorySettings updateSettings(@Valid @RequestBody Requests.MemorySettings input) {
    return memory.updateSettings(CurrentUser.userId(),
        new MemorySettings(input.autoLearn(), input.retentionDays()));
  }

  @PostMapping("/preview")
  public MemoryUseCase.ContextPreview preview(@Valid @RequestBody Requests.MemoryPreview input) {
    return memory.preview(CurrentUser.userId(), input.agentKey(), input.request());
  }
}
