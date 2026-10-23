package com.jizza.engine.notifications.escalation;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.mail.MailSender;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.notifications.bus.DefaultNotificationBus;
import com.jizza.engine.notifications.config.NotificationProperties;
import com.jizza.engine.persistence.entity.NotificationEntity;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.repository.NotificationRepository;
import com.jizza.engine.persistence.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Makes sure a blocking item is never simply forgotten (R39).
 *
 * <p>Three stages, all driven from persisted timestamps so they survive a restart: remind while the
 * item is still answerable, escalate out of the app by email once it is close to expiring, and
 * expire it when the window closes so a paused run reports a definite outcome rather than hanging
 * indefinitely.
 */
@Component
public class EscalationSweeper {

  private static final Logger log = LoggerFactory.getLogger(EscalationSweeper.class);

  private final NotificationRepository notifications;
  private final UserRepository users;
  private final AgentDirectory agents;
  private final ObjectProvider<MailSender> mail;
  private final ObjectProvider<RealtimeGateway> realtime;
  private final AuditLog audit;
  private final NotificationProperties props;

  public EscalationSweeper(NotificationRepository notifications, UserRepository users,
                           AgentDirectory agents, ObjectProvider<MailSender> mail,
                           ObjectProvider<RealtimeGateway> realtime, AuditLog audit,
                           NotificationProperties props) {
    this.notifications = notifications;
    this.users = users;
    this.agents = agents;
    this.mail = mail;
    this.realtime = realtime;
    this.audit = audit;
    this.props = props;
  }

  private QuietHours quietHours;

  /** Optional so tests can build the sweeper without user settings. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setQuietHours(QuietHours quietHours) {
    this.quietHours = quietHours;
  }

  @Scheduled(fixedDelayString = "${jizza.notifications.sweep-interval:PT5M}")
  @Transactional
  public void sweep() {
    Instant now = Instant.now();
    Instant horizon = now.plus(props.getRemindWhenWithin());
    List<NotificationEntity> open = notifications.findOpenBlockingDueBy(horizon);

    for (NotificationEntity item : open) {
      if (item.getExpiresAt() == null) continue;
      if (!item.getExpiresAt().isAfter(now)) {
        expire(item);
      } else {
        remind(item, now);
      }
    }
  }

  private void expire(NotificationEntity item) {
    item.setStatus(NotificationStatus.EXPIRED);
    item.setEscalationState(EscalationState.AUTO_CANCELLED);
    notifications.save(item);

    audit.record(item.getUserId(), ActorType.SYSTEM, item.getSourceAgentId(),
        "notification.expired", item.getId().toString(),
        Json.obj().put("kind", item.getKind().name().toLowerCase(java.util.Locale.ROOT)));
    realtime.ifAvailable(gateway -> gateway.publish(item.getUserId(), "notifications",
        Json.obj().put("id", item.getId().toString()).put("status", "expired")));
    log.info("Notification {} expired without an answer", item.getId());
  }

  private void remind(NotificationEntity item, Instant now) {
    // A low-priority item waits for the user's next visit; it is not chased.
    if (item.getPriority() == NotificationPriority.LOW) return;
    if (item.getReminderCount() >= props.getMaxReminders()) return;
    // The user put it off on purpose; only something urgent is chased through a snooze.
    if (item.getPriority() != NotificationPriority.URGENT && item.getSnoozedUntil() != null
        && item.getSnoozedUntil().isAfter(now)) {
      return;
    }
    if (item.getLastRemindedAt() != null
        && item.getLastRemindedAt().isAfter(now.minus(props.getReminderCooldown()))) {
      return;
    }
    // Quiet hours hold back everything but what is urgent; the item is chased once they end.
    if (item.getPriority() != NotificationPriority.URGENT && quietHours != null
        && quietHours.quietNow(item.getUserId())) {
      return;
    }
    item.setReminderCount(item.getReminderCount() + 1);
    item.setLastRemindedAt(now);
    item.setEscalationState(EscalationState.REMINDED);

    if (props.isEmailEscalation() && nudge(item)) {
      item.setEscalationState(EscalationState.ESCALATED);
    }
    notifications.save(item);
    audit.record(item.getUserId(), ActorType.SYSTEM, item.getSourceAgentId(),
        "notification.reminded", item.getId().toString(),
        Json.obj().put("reminderCount", item.getReminderCount())
            .put("escalation", item.getEscalationState().name().toLowerCase(java.util.Locale.ROOT)));
    realtime.ifAvailable(gateway -> gateway.publish(item.getUserId(), "notifications",
        Json.obj().put("id", item.getId().toString()).put("reminder", item.getReminderCount())));
  }

  /**
   * An urgent item is nudged out of the app as soon as the work that raised it commits, rather
   * than waiting for it to near its expiry.
   */
  @TransactionalEventListener(fallbackExecution = true)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onUrgent(DefaultNotificationBus.UrgentNotificationRaised event) {
    NotificationEntity item = notifications.findById(event.notificationId()).orElse(null);
    if (item == null || item.getStatus() == NotificationStatus.APPROVED
        || item.getStatus() == NotificationStatus.DENIED
        || item.getStatus() == NotificationStatus.EXPIRED) {
      return;
    }
    if (!props.isEmailEscalation() || !nudge(item)) return;
    item.setEscalationState(EscalationState.ESCALATED);
    item.setLastRemindedAt(Instant.now());
    notifications.save(item);
    audit.record(item.getUserId(), ActorType.SYSTEM, item.getSourceAgentId(),
        "notification.urgent_nudged", item.getId().toString(), null);
  }

  /** Emails the user that something is waiting. Returns whether the email went out. */
  private boolean nudge(NotificationEntity item) {
    MailSender sender = mail.getIfAvailable();
    UserEntity user = users.findById(item.getUserId()).orElse(null);
    if (sender == null || user == null) return false;
    String agentName = item.getSourceAgentId() == null
        ? "Jizza" : agents.nameOf(item.getSourceAgentId());
    String when = item.getExpiresAt() == null ? ""
        : " It will expire at " + item.getExpiresAt() + ".";
    try {
      // The nudge deliberately carries no detail: it says something is waiting and where to
      // look, because health and financial context does not belong in an email (R13).
      sender.send(user.getEmail(),
          item.getPriority() == NotificationPriority.URGENT
              ? "Jizza needs you now" : "Jizza is waiting on you",
          """
          %s raised something that is waiting for your answer.%s
          Open Jizza to read it and respond. This message deliberately leaves out the detail.
          """.formatted(agentName, when));
      return true;
    } catch (RuntimeException e) {
      log.warn("Could not send the escalation email for notification {}", item.getId(), e);
      return false;
    }
  }
}
