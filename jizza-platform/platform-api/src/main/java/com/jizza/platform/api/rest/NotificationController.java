package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.notification.NotificationUseCase;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Updates, approvals, procedure reviews and interventions, all relayed through Jizza (R37/R38). */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationUseCase notifications;

  public NotificationController(NotificationUseCase notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public List<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly) {
    return notifications.list(CurrentUser.userId(), unreadOnly);
  }

  @GetMapping("/summary")
  public NotificationUseCase.Summary summary() {
    return notifications.summary(CurrentUser.userId());
  }

  @GetMapping("/{id}")
  public NotificationDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PostMapping("/{id}/read")
  public Responses.Message markRead(@PathVariable UUID id) {
    notifications.markRead(CurrentUser.userId(), id);
    return new Responses.Message("Marked as read.");
  }

  @PostMapping("/{id}/unread")
  public Responses.Message markUnread(@PathVariable UUID id) {
    notifications.markUnread(CurrentUser.userId(), id);
    return new Responses.Message("Marked as unread.");
  }

  @PostMapping("/read-all")
  public Responses.Message markAllRead() {
    int changed = notifications.markAllRead(CurrentUser.userId());
    return new Responses.Message(changed == 1 ? "1 item marked as read."
        : changed + " items marked as read.");
  }

  @PostMapping("/{id}/archive")
  public NotificationDto archive(@PathVariable UUID id) {
    return notifications.archive(CurrentUser.userId(), id, true);
  }

  @PostMapping("/{id}/unarchive")
  public NotificationDto unarchive(@PathVariable UUID id) {
    return notifications.archive(CurrentUser.userId(), id, false);
  }

  /** Hides an item until the given time; an empty {@code until} brings it back now. */
  @PostMapping("/{id}/snooze")
  public NotificationDto snooze(@PathVariable UUID id,
                                @RequestBody Requests.SnoozeNotification request) {
    return notifications.snooze(CurrentUser.userId(), id, request.until());
  }

  /**
   * Approving a workflow review binds a procedure and approving a permission releases a paused
   * run, so this route requires a fresh re-authentication.
   */
  @PostMapping("/{id}/answer")
  @com.jizza.engine.api.util.StepUpRequired
  public NotificationDto answer(@PathVariable UUID id,
                                @Valid @RequestBody Requests.AnswerNotification request) {
    return notifications.answer(CurrentUser.userId(), id,
        new Answer(request.kind(), request.instruction(), request.feedback(),
            boundedInputs(request.inputs())));
  }

  /** An approval card's values: one flat object of at most 60 keys. */
  private static com.fasterxml.jackson.databind.JsonNode boundedInputs(
      com.fasterxml.jackson.databind.JsonNode inputs) {
    if (inputs == null || inputs.isNull()) return null;
    if (!inputs.isObject() || inputs.size() > 60 || inputs.toString().length() > 200_000) {
      throw new com.jizza.engine.api.error.ValidationException(
          "The approval's values are not in the right shape");
    }
    return inputs;
  }

  private NotificationDto owned(UUID notificationId) {
    return notifications.get(CurrentUser.userId(), notificationId);
  }
}
