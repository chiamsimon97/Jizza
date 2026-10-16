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

  @GetMapping("/{id}")
  public NotificationDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PostMapping("/{id}/read")
  public Responses.Message markRead(@PathVariable UUID id) {
    notifications.markRead(CurrentUser.userId(), id);
    return new Responses.Message("Marked as read.");
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
        new Answer(request.kind(), request.instruction(), request.feedback()));
  }

  private NotificationDto owned(UUID notificationId) {
    return notifications.get(CurrentUser.userId(), notificationId);
  }
}
