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
    return conversations.create(CurrentUser.userId(), body == null ? null : body.title());
  }

  @GetMapping("/{id}")
  public ConversationDto get(@PathVariable UUID id) {
    return conversations.get(CurrentUser.userId(), id);
  }

  @PatchMapping("/{id}")
  public ConversationSummaryDto update(@PathVariable UUID id, @jakarta.validation.Valid @RequestBody Requests.ConversationPatch body) {
    return conversations.update(CurrentUser.userId(), id, body.title(), body.pinned(), body.archived());
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

  /** The conversation's jobs, oldest first, without their events. */
  @GetMapping("/{id}/jobs")
  public List<JobDto> jobs(@PathVariable UUID id) {
    return conversations.jobs(CurrentUser.userId(), id);
  }
}
