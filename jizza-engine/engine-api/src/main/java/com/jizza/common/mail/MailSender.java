package com.jizza.common.mail;

/** Out-of-app nudge channel for unseen blocking items (R39). SMTP in v1. */
public interface MailSender {
  void send(String to, String subject, String body);
}
