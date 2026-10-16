package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.cosmos.CosmosSnapshot;
import com.jizza.engine.api.cosmos.ItemPage;
import com.jizza.engine.api.cosmos.MemorySearchHit;
import com.jizza.engine.api.cosmos.MemorySearchQuery;
import com.jizza.engine.api.cosmos.Passport;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.usecase.memory.CosmosUseCase;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Memory Cosmos: every body and its counts, the items orbiting a body, search across both
 * memory stores, each item's passport, and the actions that change who can use a memory.
 *
 * <p>User-only. Nothing here is registered as an agent tool. Moving, un-restricting, revealing a
 * restricted item and forgetting all ask for a fresh step-up.
 */
@RestController
@AuthenticatedOnly
public class CosmosController {

  private final CosmosUseCase cosmos;

  public CosmosController(CosmosUseCase cosmos) {
    this.cosmos = cosmos;
  }

  // ── reading ─────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/api/memory/cosmos")
  public CosmosSnapshot snapshot() {
    return cosmos.snapshot(CurrentUser.userId());
  }

  @GetMapping("/api/memory/cosmos/bodies/{bodyId}/items")
  public ItemPage items(@PathVariable String bodyId,
                        @RequestParam(required = false) String cursor,
                        @RequestParam(required = false) Integer limit,
                        @RequestParam(defaultValue = "false") boolean includeInactive) {
    return cosmos.items(CurrentUser.userId(), bodyId, cursor, limit, includeInactive);
  }

  @GetMapping("/api/memory/cosmos/search")
  public List<MemorySearchHit> search(@RequestParam String q,
                                      @RequestParam(required = false) List<UUID> agentIds,
                                      @RequestParam(required = false) List<String> categories,
                                      @RequestParam(required = false) String classification,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to) {
    return cosmos.search(CurrentUser.userId(),
        new MemorySearchQuery(q, agentIds, categories, classification, status, from, to));
  }

  @GetMapping("/api/memory/cosmos/items/{kind}/{id}/passport")
  public Passport passport(@PathVariable String kind, @PathVariable UUID id,
                           @RequestParam(required = false) String cursor) {
    return cosmos.passport(CurrentUser.userId(), kind, id, cursor);
  }

  /** A restricted item's words. */
  @StepUpRequired
  @GetMapping("/api/memory/cosmos/items/{kind}/{id}/reveal")
  public Passport reveal(@PathVariable String kind, @PathVariable UUID id) {
    return cosmos.reveal(CurrentUser.userId(), kind, id);
  }

  /** Forgets every fact, note and subject in one body's part of the sky. Permanent. */
  @StepUpRequired
  @PostMapping("/api/memory/cosmos/reset")
  public CosmosUseCase.ResetResult reset(@Valid @RequestBody Requests.ResetMemory input) {
    return cosmos.reset(CurrentUser.userId(), input.bodyId());
  }

  // ── facts ───────────────────────────────────────────────────────────────────────────────────

  @PostMapping("/api/memory/{id}/share")
  public Memory share(@PathVariable UUID id, @Valid @RequestBody Requests.ShareMemory input) {
    return cosmos.share(CurrentUser.userId(), id, input.agentId());
  }

  @DeleteMapping("/api/memory/{id}/share/{agentId}")
  public Memory unshare(@PathVariable UUID id, @PathVariable UUID agentId) {
    return cosmos.unshare(CurrentUser.userId(), id, agentId);
  }

  @StepUpRequired
  @PostMapping("/api/memory/{id}/move")
  public Memory move(@PathVariable UUID id, @Valid @RequestBody Requests.MoveMemory input) {
    return cosmos.move(CurrentUser.userId(), id, input.domains());
  }

  /** Narrowing who can use a fact needs nothing more than being signed in. */
  @PostMapping("/api/memory/{id}/restrict")
  public Memory restrict(@PathVariable UUID id) {
    return cosmos.restrictFact(CurrentUser.userId(), id, true);
  }

  /** Widening it again does. */
  @StepUpRequired
  @PostMapping("/api/memory/{id}/unrestrict")
  public Memory unrestrict(@PathVariable UUID id) {
    return cosmos.restrictFact(CurrentUser.userId(), id, false);
  }

  // ── an agent's notes ────────────────────────────────────────────────────────────────────────

  @PatchMapping("/api/agents/{agentId}/memory/notes/{noteId}")
  public KnowledgeEntry editNote(@PathVariable UUID agentId, @PathVariable UUID noteId,
                                 @Valid @RequestBody Requests.EditNote input) {
    return cosmos.editNote(CurrentUser.userId(), agentId, noteId, input.title(), input.content());
  }

  @StepUpRequired
  @PostMapping("/api/agents/{agentId}/memory/notes/{noteId}/move")
  public KnowledgeEntry moveNote(@PathVariable UUID agentId, @PathVariable UUID noteId,
                                 @Valid @RequestBody Requests.MoveNote input) {
    return cosmos.moveNote(CurrentUser.userId(), agentId, noteId, input.toAgentId());
  }

  @PostMapping("/api/agents/{agentId}/memory/notes/{noteId}/restrict")
  public KnowledgeEntry restrictNote(@PathVariable UUID agentId, @PathVariable UUID noteId) {
    return cosmos.restrictNote(CurrentUser.userId(), agentId, noteId, true);
  }

  @StepUpRequired
  @PostMapping("/api/agents/{agentId}/memory/notes/{noteId}/unrestrict")
  public KnowledgeEntry unrestrictNote(@PathVariable UUID agentId, @PathVariable UUID noteId) {
    return cosmos.restrictNote(CurrentUser.userId(), agentId, noteId, false);
  }

  /** Makes a note part of the user's shared memory, as a fact they stand behind. */
  @PostMapping("/api/agents/{agentId}/memory/notes/{noteId}/promote")
  public MemoryWrite promote(@PathVariable UUID agentId, @PathVariable UUID noteId,
                             @RequestBody Requests.PromoteNote input) {
    return cosmos.promoteNote(CurrentUser.userId(), agentId, noteId, input.category(),
        input.removeNote());
  }
}
