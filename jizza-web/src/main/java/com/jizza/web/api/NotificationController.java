package com.jizza.web.api;

import com.jizza.common.dto.NotificationDto;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.notification.Answer;
import com.jizza.common.notification.NotificationBus;
import com.jizza.security.session.CurrentUser;
import com.jizza.web.api.dto.Requests;
import com.jizza.web.api.dto.Responses;
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
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationBus bus;

  public NotificationController(NotificationBus bus) {
    this.bus = bus;
  }

  @GetMapping
  public List<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly) {
    return bus.list(CurrentUser.userId(), unreadOnly);
  }

  @GetMapping("/{id}")
  public NotificationDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PostMapping("/{id}/read")
  public Responses.Message markRead(@PathVariable UUID id) {
    owned(id);
    bus.markRead(id);
    return new Responses.Message("Marked as read.");
  }

  /**
   * Approving a workflow review binds a procedure and approving a permission releases a paused
   * run, so this route requires a fresh re-authentication.
   */
  @PostMapping("/{id}/answer")
  @com.jizza.common.util.StepUpRequired
  public NotificationDto answer(@PathVariable UUID id,
                                @Valid @RequestBody Requests.AnswerNotification request) {
    owned(id);
    return bus.answer(id, new Answer(request.kind(), request.instruction(), request.feedback()));
  }

  private NotificationDto owned(UUID notificationId) {
    NotificationDto notification = bus.get(notificationId);
    if (!notification.userId().equals(CurrentUser.userId())) {
      throw new NotFoundException("Notification", notificationId);
    }
    return notification;
  }
}
