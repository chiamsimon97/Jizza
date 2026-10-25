package com.jizza.platform.usecase.activity;

import static com.jizza.engine.api.audit.catalog.ActivityLogType.Severity.CRITICAL;
import static com.jizza.engine.api.audit.catalog.ActivityLogType.Severity.NOTICE;
import static com.jizza.engine.api.audit.catalog.ActivityLogType.Severity.WARNING;

import com.jizza.engine.api.audit.catalog.ActivityCategory;
import com.jizza.engine.api.audit.catalog.ActivityCategory.ActivityGroup;
import com.jizza.engine.api.audit.catalog.ActivityLogCatalog;
import com.jizza.engine.api.audit.catalog.ActivityLogType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The activity view's map of every audit action Jizza records today: the sections, their
 * sub-groups, and what each action is called. A new log type usually needs one line in
 * {@link #types()}; a new area needs a category here, or its own {@link ActivityLogCatalog} bean.
 */
@Component
public class CoreActivityCatalog implements ActivityLogCatalog {

  @Override
  public List<ActivityCategory> categories() {
    return List.of(
        category("security", "Sign-in & security", "ShieldCheck", 10, List.of("auth.", "authz."),
            group("sign_in", "Sign-in"),
            group("mfa", "Two-step verification"),
            group("password", "Password & recovery"),
            group("sessions", "Sessions & devices"),
            group("access", "Access control")),
        category("agents", "Agents", "Bot", 20, List.of("agent.", "agent_template."),
            group("lifecycle", "Created & changed"),
            group("runs", "Agent runs"),
            group("templates", "Agent templates")),
        category("workflows", "Workflows", "Workflow", 30,
            List.of("run.", "workflow.", "webhook."),
            group("runs", "Runs"),
            group("attention", "Failures & stops"),
            group("human", "Approvals & instructions"),
            group("webhooks", "Webhooks"),
            group("skills", "Saved as skills")),
        category("tasks", "Tasks & procedures", "ListTodo", 40, List.of("task.", "procedure."),
            group("tasks", "Tasks"),
            group("procedures", "Procedures")),
        category("tools", "Tools & connectors", "Plug", 50,
            List.of("tool.", "connection.", "service."),
            group("calls", "Tool calls"),
            group("blocked", "Blocked & held"),
            group("connections", "Connections"),
            group("services", "Custom services")),
        category("guardrails", "Guardrails & autonomy", "Shield", 60,
            List.of("guardrail.", "autonomy."),
            group("decisions", "Decisions"),
            group("policies", "Policies"),
            group("kill_switch", "Pause all")),
        category("memory", "Memory & knowledge", "Brain", 70, List.of("memory.", "knowledge."),
            group("learned", "Learned"),
            group("subjects", "Subjects & files"),
            group("knowledge", "Knowledge"),
            group("isolation", "Isolation")),
        category("chat", "Conversations", "MessageSquare", 80, List.of("chat."),
            group("messages", "Messages"),
            group("threads", "Threads")),
        category("notifications", "Notifications", "Bell", 90, List.of("notification."),
            group("raised", "Raised"),
            group("answers", "Answers"),
            group("escalation", "Reminders & expiry")),
        category("workspace", "Workspace & members", "Users", 100,
            List.of("workspace.", "member."),
            group("members", "Members"),
            group("workspace", "Workspace")),
        category("data", "Data & privacy", "Database", 110, List.of("data."),
            group("requests", "Export & deletion")));
  }

  @Override
  public List<ActivityLogType> types() {
    return List.of(
        // ── security
        ActivityLogType.of("auth.login.success", "security", "sign_in", "Signed in"),
        ActivityLogType.of("auth.login.password_ok", "security", "sign_in", "Password accepted"),
        ActivityLogType.of("auth.login.failure", "security", "sign_in", "Sign-in failed", WARNING),
        ActivityLogType.of("auth.login.new_device", "security", "sessions",
            "Signed in from a new device", NOTICE),
        ActivityLogType.of("auth.lockout", "security", "sign_in", "Account locked", CRITICAL),
        ActivityLogType.of("auth.register", "security", "sign_in", "Account created"),
        ActivityLogType.of("auth.logout", "security", "sessions", "Signed out"),
        ActivityLogType.of("auth.logout_everywhere", "security", "sessions",
            "Signed out everywhere", NOTICE),
        ActivityLogType.of("auth.session.*", "security", "sessions", null),
        ActivityLogType.of("auth.mfa.*", "security", "mfa", null),
        ActivityLogType.of("auth.mfa.disabled", "security", "mfa",
            "Two-step verification turned off", WARNING),
        ActivityLogType.of("auth.mfa.recovery_code_used", "security", "mfa",
            "Recovery code used", NOTICE),
        ActivityLogType.of("auth.password.*", "security", "password", null),
        ActivityLogType.of("auth.password.changed", "security", "password", "Password changed",
            NOTICE),
        ActivityLogType.of("auth.password_reset.*", "security", "password", null),
        ActivityLogType.of("auth.step_up.*", "security", "access", "Identity confirmed"),
        ActivityLogType.of("authz.*", "security", "access", null),
        ActivityLogType.of("authz.denied", "security", "access", "Access denied", WARNING),
        ActivityLogType.of("authz.super_admin_override", "security", "access",
            "Super admin override", CRITICAL),

        // ── agents
        ActivityLogType.of("agent.*", "agents", "lifecycle", null),
        ActivityLogType.of("agent.run.*", "agents", "runs", null),
        ActivityLogType.of("agent.run.start", "agents", "runs", "Agent started"),
        ActivityLogType.of("agent.run.done", "agents", "runs", "Agent finished"),
        ActivityLogType.of("agent.run.budget_exhausted", "agents", "runs", "Agent ran out of budget",
            WARNING),
        ActivityLogType.of("agent.tried", "agents", "runs", "Agent tried out"),
        ActivityLogType.of("agent.deleted", "agents", "lifecycle", "Agent deleted", NOTICE),
        ActivityLogType.of("agent_template.*", "agents", "templates", null),

        // ── workflows
        ActivityLogType.of("run.*", "workflows", "runs", null),
        ActivityLogType.of("run.started", "workflows", "runs", "Run started"),
        ActivityLogType.of("run.completed", "workflows", "runs", "Run completed"),
        ActivityLogType.of("run.resumed_after_restart", "workflows", "runs",
            "Run resumed after restart", NOTICE),
        ActivityLogType.of("run.failed", "workflows", "attention", "Run failed", WARNING),
        ActivityLogType.of("run.dead_lettered", "workflows", "attention", "Run dead-lettered",
            CRITICAL),
        ActivityLogType.of("run.cancelled", "workflows", "attention", "Run cancelled", NOTICE),
        ActivityLogType.of("run.halted_by_kill_switch", "workflows", "attention",
            "Run halted by pause-all", WARNING),
        ActivityLogType.of("run.paused", "workflows", "human", "Run waiting for you", NOTICE),
        ActivityLogType.of("run.waiting", "workflows", "runs", "Run waiting"),
        ActivityLogType.of("run.permission_granted", "workflows", "human", "Step approved"),
        ActivityLogType.of("run.instruction_submitted", "workflows", "human",
            "Instruction given"),
        ActivityLogType.of("webhook.*", "workflows", "webhooks", null),
        ActivityLogType.of("webhook.rejected", "workflows", "webhooks", "Webhook rejected", WARNING),
        ActivityLogType.of("workflow.*", "workflows", "skills", null),

        // ── tasks & procedures
        ActivityLogType.of("task.*", "tasks", "tasks", null),
        ActivityLogType.of("task.failed", "tasks", "tasks", "Task failed", WARNING),
        ActivityLogType.of("procedure.*", "tasks", "procedures", null),

        // ── tools & connectors
        ActivityLogType.of("tool.*", "tools", "calls", null),
        ActivityLogType.of("tool.call", "tools", "calls", "Tool called"),
        ActivityLogType.of("tool.result.ok", "tools", "calls", "Tool succeeded"),
        ActivityLogType.of("tool.result.error", "tools", "calls", "Tool failed", WARNING),
        ActivityLogType.of("tool.dry_run", "tools", "calls", "Tool dry run"),
        ActivityLogType.of("tool.blocked", "tools", "blocked", "Tool call blocked", WARNING),
        ActivityLogType.of("tool.egress_blocked", "tools", "blocked", "Network call blocked",
            WARNING),
        ActivityLogType.of("tool.permission_requested", "tools", "blocked",
            "Approval requested", NOTICE),
        ActivityLogType.of("connection.*", "tools", "connections", null),
        ActivityLogType.of("connection.refresh_failed", "tools", "connections",
            "Connection refresh failed", WARNING),
        ActivityLogType.of("connection.revoked", "tools", "connections", "Connection revoked",
            NOTICE),
        ActivityLogType.of("service.*", "tools", "services", null),

        // ── guardrails
        ActivityLogType.of("guardrail.*", "guardrails", "policies", null),
        ActivityLogType.of("guardrail.jizza_approved", "guardrails", "decisions",
            "Jizza approved a call"),
        ActivityLogType.of("guardrail.jizza_asked", "guardrails", "decisions",
            "Jizza asked you", NOTICE),
        ActivityLogType.of("autonomy.*", "guardrails", "policies", null),
        ActivityLogType.of("autonomy.kill_switch_engaged", "guardrails", "kill_switch",
            "Everything paused", CRITICAL),
        ActivityLogType.of("autonomy.kill_switch_released", "guardrails", "kill_switch",
            "Everything resumed", NOTICE),

        // ── memory & knowledge
        ActivityLogType.of("memory.*", "memory", "learned", null),
        ActivityLogType.of("memory.subject.*", "memory", "subjects", null),
        ActivityLogType.of("memory.file_added", "memory", "subjects", "File added"),
        ActivityLogType.of("memory.file_deleted", "memory", "subjects", "File deleted"),
        ActivityLogType.of("memory.settings.updated", "memory", "subjects",
            "Memory settings changed"),
        ActivityLogType.of("memory.isolation_violation", "memory", "isolation",
            "Memory isolation blocked", CRITICAL),
        ActivityLogType.of("knowledge.*", "memory", "knowledge", null),

        // ── conversations
        ActivityLogType.of("chat.*", "chat", "messages", null),
        ActivityLogType.of("chat.message", "chat", "messages", "Message sent"),
        ActivityLogType.of("chat.turn.cancel", "chat", "messages", "Reply stopped"),
        ActivityLogType.of("chat.conversation.delete", "chat", "messages",
            "Conversation deleted", NOTICE),
        ActivityLogType.of("chat.thread.open", "chat", "threads", "Thread opened"),
        ActivityLogType.of("chat.thread.handback", "chat", "threads", "Message passed back to Jizza"),
        ActivityLogType.of("chat.thread.guide.edit", "chat", "threads", "Thread guide edited"),

        // ── notifications
        ActivityLogType.of("notification.*", "notifications", "answers", null),
        ActivityLogType.of("notification.raised", "notifications", "raised",
            "Notification raised"),
        ActivityLogType.of("notification.reminded", "notifications", "escalation", "Reminder sent"),
        ActivityLogType.of("notification.urgent_nudged", "notifications", "escalation",
            "Marked urgent", NOTICE),
        ActivityLogType.of("notification.expired", "notifications", "escalation",
            "Notification expired", WARNING),
        ActivityLogType.of("notification.permission_denied", "notifications", "answers",
            "Permission denied", NOTICE),
        ActivityLogType.of("notification.review_rejected", "notifications", "answers",
            "Review rejected", NOTICE),

        // ── workspace
        ActivityLogType.of("member.*", "workspace", "members", null),
        ActivityLogType.of("member.removed", "workspace", "members", "Member removed", NOTICE),
        ActivityLogType.of("member.role_changed", "workspace", "members", "Role changed", NOTICE),
        ActivityLogType.of("workspace.*", "workspace", "workspace", null),

        // ── data
        ActivityLogType.of("data.*", "data", "requests", null),
        ActivityLogType.of("data.hard_delete_requested", "data", "requests",
            "Permanent deletion requested", CRITICAL));
  }

  private static ActivityCategory category(String key, String label, String icon, int order,
                                           List<String> prefixes, ActivityGroup... groups) {
    return new ActivityCategory(key, label, icon, order, prefixes, List.of(groups));
  }

  private static ActivityGroup group(String key, String label) {
    return new ActivityGroup(key, label);
  }
}
