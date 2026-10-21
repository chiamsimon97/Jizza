package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.mail.MailSender;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Tells the user something (Zapier's "Email by Zapier" to yourself, Zoho Flow's notifications):
 * {@code title} and {@code message} are templates, and {@code channel} is {@code app} (the
 * notification inbox), {@code email} (to the account's own address) or {@code both}. It only ever
 * reaches the workflow's owner; mail to anyone else goes through a mail connector, where the
 * user's sending rules apply.
 */
@Component
public class NotifyNodeExecutor implements NodeExecutor {

  static final int MAX_MESSAGE_CHARS = 10_000;

  private final JexlSandbox sandbox;
  private final ObjectProvider<NotificationBus> notifications;
  private final ObjectProvider<MailSender> mail;
  private final UserRepository users;

  public NotifyNodeExecutor(JexlSandbox sandbox, ObjectProvider<NotificationBus> notifications,
                            ObjectProvider<MailSender> mail, UserRepository users) {
    this.sandbox = sandbox;
    this.notifications = notifications;
    this.mail = mail;
    this.users = users;
  }

  @Override
  public NodeType type() { return NodeType.NOTIFY; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode config = execution.node().config();
    if (config == null) return Outcome.permanentFailure("The notify step has no settings");
    Map<String, Object> variables = execution.context().variables();
    String title;
    String message;
    try {
      title = Templates.render(config.path("title").asText(""), sandbox, variables).strip();
      message = Templates.render(config.path("message").asText(""), sandbox, variables).strip();
    } catch (SandboxViolationException e) {
      return Outcome.permanentFailure("The notification could not be written: " + e.getMessage());
    }
    if (message.isEmpty()) return Outcome.permanentFailure("The notification has no message");
    if (message.length() > MAX_MESSAGE_CHARS) message = message.substring(0, MAX_MESSAGE_CHARS);
    String channel = config.path("channel").asText("app");
    boolean app = !channel.equals("email");
    boolean email = !channel.equals("app");

    var sent = Json.mapper().createArrayNode();
    if (app) {
      NotificationBus bus = notifications.getIfAvailable();
      if (bus == null) return Outcome.retryable("Notifications are not available right now");
      String body = title.isEmpty() ? message : title + "\n\n" + message;
      bus.raise(NotificationKind.INFO, execution.owningAgentId(), NotificationContext.forRun(
          execution.userId(), body, Json.obj().put("node", execution.node().id())
              .put("workflowId", String.valueOf(execution.workflowId())), execution.runId()));
      sent.add("app");
    }
    if (email) {
      MailSender sender = mail.getIfAvailable();
      String to = users.findById(execution.userId()).map(u -> u.getEmail()).orElse(null);
      if (sender == null || to == null || to.isBlank()) {
        return Outcome.permanentFailure("Email is not set up, so the notification could not be "
            + "emailed");
      }
      sender.send(to, title.isEmpty() ? "A message from your Jizza workflow" : title, message);
      sent.add("email");
    }
    var output = Json.obj().put("title", title).put("message", message);
    output.set("sent", sent);
    return Outcome.completed(output);
  }
}
