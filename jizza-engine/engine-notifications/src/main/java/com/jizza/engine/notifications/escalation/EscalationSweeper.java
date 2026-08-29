package com.jizza.engine.notifications.escalation;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.mail.MailSender;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
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
import org.springframework.transaction.annotation.Transactional;

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
    if (item.getReminderCount() >= props.getMaxReminders()) return;
    if (item.getLastRemindedAt() != null
        && item.getLastRemindedAt().isAfter(now.minus(props.getReminderCooldown()))) {
      return;
    }

    item.setReminderCount(item.getReminderCount() + 1);
    item.setLastRemindedAt(now);
    item.setEscalationState(EscalationState.REMINDED);

    if (props.isEmailEscalation()) {
      MailSender sender = mail.getIfAvailable();
      UserEntity user = users.findById(item.getUserId()).orElse(null);
      if (sender != null && user != null) {
        String agentName = item.getSourceAgentId() == null
            ? "Jizza" : agents.nameOf(item.getSourceAgentId());
        try {
          // The nudge deliberately carries no detail: it says something is waiting and where to
          // look, because health and financial context does not belong in an email (R13).
          sender.send(user.getEmail(),
              "Jizza is waiting on you",
              """
              %s raised something that is waiting for your answer and it will expire at %s.

              Open Jizza to read it and respond. This reminder deliberately leaves out the detail.
              """.formatted(agentName, item.getExpiresAt()));
          item.setEscalationState(EscalationState.ESCALATED);
        } catch (RuntimeException e) {
          log.warn("Could not send the escalation email for notification {}", item.getId(), e);
        }
      }
    }

    notifications.save(item);
    audit.record(item.getUserId(), ActorType.SYSTEM, item.getSourceAgentId(),
        "notification.reminded", item.getId().toString(),
        Json.obj().put("reminderCount", item.getReminderCount())
            .put("escalation", item.getEscalationState().name().toLowerCase(java.util.Locale.ROOT)));
    realtime.ifAvailable(gateway -> gateway.publish(item.getUserId(), "notifications",
        Json.obj().put("id", item.getId().toString()).put("reminder", item.getReminderCount())));
  }
}
