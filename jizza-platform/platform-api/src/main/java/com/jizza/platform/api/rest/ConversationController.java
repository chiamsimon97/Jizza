package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.chat.JobDto;
import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.dto.ConversationSummaryDto;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.usecase.chat.ConversationUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The user's conversations and the jobs each one ran. */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/conversations")
public class ConversationController {

  private final ConversationUseCase conversations;

  public ConversationController(ConversationUseCase conversations) {
    this.conversations = conversations;
  }

  /** Newest activity first, pinned on top; {@code q} searches titles and messages. */
  @GetMapping
  public List<ConversationSummaryDto> list(@RequestParam(defaultValue = "50") int limit,
                                           @RequestParam(defaultValue = "false") boolean archived,
                                           @RequestParam(required = false) String q) {
    return conversations.summaries(CurrentUser.userId(), limit, archived, q);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ConversationSummaryDto create(@jakarta.validation.Valid @RequestBody(required = false) Requests.ConversationCreate body) {
    return conversations.create(CurrentUser.userId(), body == null ? null : body.title(),
        body != null && Boolean.TRUE.equals(body.threads()));
  }

  @GetMapping("/{id}")
  public ConversationDto get(@PathVariable UUID id) {
    return conversations.get(CurrentUser.userId(), id);
  }

  @PatchMapping("/{id}")
  public ConversationSummaryDto update(@PathVariable UUID id, @jakarta.validation.Valid @RequestBody Requests.ConversationPatch body) {
    return conversations.update(CurrentUser.userId(), id, body.title(), body.pinned(), body.archived(),
        body.threads());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    conversations.delete(CurrentUser.userId(), id);
  }

  @GetMapping("/{id}/messages")
  public List<ConversationDto.MessageDto> messages(@PathVariable UUID id) {
    return conversations.messages(CurrentUser.userId(), id);
  }

  /** The conversation as a file: {@code md} (default) or {@code json}. */
  @GetMapping("/{id}/export")
  public org.springframework.http.ResponseEntity<String> export(@PathVariable UUID id,
      @RequestParam(defaultValue = "md") String format) {
    ConversationUseCase.Export file = conversations.export(CurrentUser.userId(), id, format);
    return org.springframework.http.ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
            org.springframework.http.ContentDisposition.attachment().filename(file.fileName()).build().toString())
        .contentType(org.springframework.http.MediaType.parseMediaType(file.contentType()))
        .body(file.body());
  }

  /** One conversation's list row: for a thread, its lead, topic and main chat. */
  @GetMapping("/{id}/summary")
  public ConversationSummaryDto summary(@PathVariable UUID id) {
    return conversations.summary(CurrentUser.userId(), id);
  }

  /** A main chat's threads, most recently active first. A thread is reached from its own chat only. */
  @GetMapping("/{id}/threads")
  public List<ConversationSummaryDto> threads(@PathVariable UUID id) {
    return conversations.threads(CurrentUser.userId(), id);
  }

  /**
   * What Jizza remembers of the older messages once a long conversation was folded into a summary
   * to keep each turn small; 204 while every message is still sent as written.
   */
  @GetMapping("/{id}/earlier")
  public org.springframework.http.ResponseEntity<com.jizza.engine.api.dto.EarlierSummaryDto> earlier(@PathVariable UUID id) {
    com.jizza.engine.api.dto.EarlierSummaryDto earlier = conversations.earlier(CurrentUser.userId(), id);
    return earlier == null ? org.springframework.http.ResponseEntity.noContent().build()
        : org.springframework.http.ResponseEntity.ok(earlier);
  }

  /** The long-term memories learned from what was said here: a thread's own memory. */
  @GetMapping("/{id}/learned")
  public List<com.jizza.engine.api.dto.LearnedFactDto> learned(@PathVariable UUID id) {
    return conversations.learned(CurrentUser.userId(), id);
  }

  /**
   * Edits the user's message {@code messageId}: removes it and everything after it, so the client
   * sends the edited text in its place.
   */
  @org.springframework.web.bind.annotation.PostMapping("/{id}/messages/{messageId}/rewind")
  public org.springframework.http.ResponseEntity<Void> rewind(@PathVariable UUID id,
                                                              @PathVariable UUID messageId) {
    conversations.rewind(CurrentUser.userId(), id, messageId);
    return org.springframework.http.ResponseEntity.noContent().build();
  }

  /** A thread's learning file. */
  @GetMapping("/{id}/guide")
  public com.jizza.engine.api.dto.ThreadGuideDto guide(@PathVariable UUID id) {
    return conversations.guide(CurrentUser.userId(), id);
  }

  @org.springframework.web.bind.annotation.PutMapping("/{id}/guide")
  public com.jizza.engine.api.dto.ThreadGuideDto updateGuide(@PathVariable UUID id,
      @jakarta.validation.Valid @RequestBody Requests.ThreadGuidePut body) {
    return conversations.updateGuide(CurrentUser.userId(), id, body.markdown());
  }

  /** A thread's learning file as a .md file. */
  @GetMapping("/{id}/guide/download")
  public org.springframework.http.ResponseEntity<String> downloadGuide(@PathVariable UUID id) {
    ConversationUseCase.Export file = conversations.guideFile(CurrentUser.userId(), id);
    return org.springframework.http.ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
            org.springframework.http.ContentDisposition.attachment().filename(file.fileName()).build().toString())
        .contentType(org.springframework.http.MediaType.parseMediaType(file.contentType()))
        .body(file.body());
  }

  /** The conversation's jobs, oldest first, without their events. */
  @GetMapping("/{id}/jobs")
  public List<JobDto> jobs(@PathVariable UUID id) {
    return conversations.jobs(CurrentUser.userId(), id);
  }
}
