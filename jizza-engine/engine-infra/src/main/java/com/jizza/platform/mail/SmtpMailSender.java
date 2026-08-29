package com.jizza.platform.mail;

import com.jizza.common.mail.MailSender;
import com.jizza.platform.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP escalation, the Charter's v1 out-of-app channel (R39). Locally this points at the mail
 * catcher in {@code docker-compose.yml}, so nudges are visible without sending real email.
 */
@Component
public class SmtpMailSender implements MailSender {

  private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

  private final org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSender;
  private final PlatformProperties props;

  public SmtpMailSender(org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSender,
                        PlatformProperties props) {
    this.mailSender = mailSender;
    this.props = props;
  }

  @Override
  public void send(String to, String subject, String body) {
    if (!props.getMail().isEnabled()) {
      log.info("Mail is disabled; would have sent '{}' to {}", subject, to);
      return;
    }
    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null) {
      log.warn("No mail sender is configured; '{}' to {} was not sent", subject, to);
      return;
    }
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(props.getMail().getFrom());
    message.setTo(to);
    message.setSubject(subject);
    message.setText(body);
    sender.send(message);
  }
}
