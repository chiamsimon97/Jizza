package com.jizza.engine.security.alerts;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.mail.MailSender;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.UserDeviceEntity;
import com.jizza.engine.persistence.repository.UserDeviceRepository;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells the owner when something security-relevant happens to their account: a sign-in from a
 * device not seen before, two-step sign-in being switched off, a recovery code being spent, the
 * password changing. The alert goes to the in-app notifications and, when mail is configured, to
 * their inbox, so a takeover is visible even to someone who is not looking at Jizza.
 *
 * <p>Alerts are sent after the surrounding transaction commits, in their own, so a mail or
 * notification hiccup can never undo or block the sign-in it describes.
 */
@Component
public class SecurityAlerts {

  private static final Logger log = LoggerFactory.getLogger(SecurityAlerts.class);

  private final UserDeviceRepository devices;
  private final AuditLog audit;
  private final ObjectProvider<NotificationBus> notifications;
  private final ObjectProvider<MailSender> mail;
  private final TransactionTemplate separate;

  public SecurityAlerts(UserDeviceRepository devices, AuditLog audit,
                        ObjectProvider<NotificationBus> notifications, ObjectProvider<MailSender> mail,
                        PlatformTransactionManager txManager) {
    this.devices = devices;
    this.audit = audit;
    this.notifications = notifications;
    this.mail = mail;
    this.separate = new TransactionTemplate(txManager);
    this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Records the device a session was opened from and alerts on the first sign-in from it. The very
   * first device an account ever uses is not news, so it is recorded silently.
   */
  public void signedIn(UUID userId, String email, String device, InetAddress ip) {
    String fingerprint = Hashing.sha256Hex(device == null ? "" : device);
    var known = devices.findByUserIdAndFingerprint(userId, fingerprint);
    if (known.isPresent()) {
      UserDeviceEntity entity = known.get();
      entity.setLastSeenAt(Instant.now());
      entity.setLastIp(ip);
      devices.save(entity);
      return;
    }

    boolean firstEver = !devices.existsByUserId(userId);
    UserDeviceEntity entity = new UserDeviceEntity();
    entity.setUserId(userId);
    entity.setFingerprint(fingerprint);
    entity.setLabel(describe(device));
    entity.setLastIp(ip);
    devices.save(entity);
    if (firstEver) return;

    String where = ip == null ? "" : " (" + ip.getHostAddress() + ")";
    audit.record(userId, ActorType.SYSTEM, null, "auth.login.new_device", email,
        Json.obj().put("device", entity.getLabel()).put("ip", ip == null ? null : ip.getHostAddress()));
    alert(userId, email, "New sign-in to Jizza",
        "Someone signed in to your Jizza account from a new device: " + entity.getLabel() + where
            + ". If this was not you, sign out everywhere and change your password now.");
  }

  /** In-app and email alert, sent once the current transaction commits. */
  public void alert(UUID userId, String email, String subject, String body) {
    Runnable send = () -> {
      try {
        separate.executeWithoutResult(status -> notifications.ifAvailable(bus ->
            bus.raise(NotificationKind.INFO, null, NotificationContext.info(userId, body))));
      } catch (RuntimeException e) {
        log.warn("Could not raise the security notification '{}'", subject, e);
      }
      MailSender sender = mail.getIfAvailable();
      if (sender != null && email != null) {
        try {
          sender.send(email, subject, body);
        } catch (RuntimeException e) {
          log.warn("Could not email the security alert '{}'", subject, e);
        }
      }
    };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { send.run(); }
      });
    } else {
      send.run();
    }
  }

  /** A short human label for a user agent: browser and platform, not the whole string. */
  static String describe(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) return "an unknown device";
    String browser = userAgent.contains("Edg/") ? "Edge"
        : userAgent.contains("OPR/") ? "Opera"
        : userAgent.contains("Firefox/") ? "Firefox"
        : userAgent.contains("Chrome/") ? "Chrome"
        : userAgent.contains("Safari/") ? "Safari"
        : null;
    String platform = userAgent.contains("iPhone") || userAgent.contains("iPad") ? "iOS"
        : userAgent.contains("Android") ? "Android"
        : userAgent.contains("Mac OS X") || userAgent.contains("Macintosh") ? "macOS"
        : userAgent.contains("Windows") ? "Windows"
        : userAgent.contains("Linux") ? "Linux"
        : null;
    if (browser == null && platform == null) {
      return userAgent.length() <= 60 ? userAgent : userAgent.substring(0, 60) + "...";
    }
    if (browser == null) return platform;
    return platform == null ? browser : browser + " on " + platform;
  }
}
