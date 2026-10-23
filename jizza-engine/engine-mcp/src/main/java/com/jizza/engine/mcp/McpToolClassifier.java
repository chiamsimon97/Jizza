package com.jizza.engine.mcp;

import com.jizza.engine.api.enums.ActionType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Suggests what an MCP tool does from its name, so the guardrails of the agent it is given to apply
 * from the start: a {@code get_holdings} is a read and runs under the agent's rule for reads, a
 * {@code place_order} moves money. It is only a suggestion, shown as one and changed by the user;
 * a tool it cannot place gets none and is judged the most consequential kind.
 */
public final class McpToolClassifier {

  private static final Set<String> READ = Set.of("get", "list", "search", "fetch", "find", "read",
      "show", "view", "lookup", "query", "describe", "check", "status", "browse", "login",
      "logout", "whoami", "profile", "count", "preview", "download", "export");
  private static final Set<String> MONEY = Set.of("order", "orders", "trade", "trades", "buy", "sell",
      "pay", "payment", "payout", "transfer", "withdraw", "deposit", "fund", "funds", "invest",
      "gtt", "sip", "mf", "charge", "refund", "invoice", "purchase", "checkout", "subscribe");
  private static final Set<String> DELETE = Set.of("delete", "remove", "cancel", "revoke", "drop",
      "destroy", "archive", "purge", "clear", "unsubscribe");
  private static final Set<String> SEND = Set.of("send", "email", "mail", "message", "post",
      "notify", "reply", "publish", "tweet", "sms", "invite", "share");
  private static final Set<String> CREATE = Set.of("create", "add", "new", "insert", "upload",
      "make", "generate", "place", "book", "schedule", "start", "open");
  private static final Set<String> UPDATE = Set.of("update", "modify", "edit", "set", "rename",
      "move", "change", "patch", "replace", "assign", "close", "enable", "disable", "save");

  private McpToolClassifier() { }

  /**
   * @param hint what the server says about it ({@code read-only}, {@code destructive}); it can
   *             only make a suggestion riskier or fill a gap, never turn a write into a read
   */
  public static Optional<ActionType> suggest(String name, String hint) {
    List<String> words = words(name);
    if (words.isEmpty()) return Optional.empty();
    String verb = words.getFirst();
    boolean destructive = "destructive".equals(hint);
    ActionType type;
    if (READ.contains(verb)) {
      type = ActionType.READ;
    } else if (words.stream().anyMatch(MONEY::contains)) {
      type = ActionType.MONEY;
    } else if (DELETE.contains(verb)) {
      type = ActionType.DELETE;
    } else if (SEND.contains(verb)) {
      type = ActionType.SEND;
    } else if (CREATE.contains(verb)) {
      type = ActionType.CREATE;
    } else if (UPDATE.contains(verb)) {
      type = ActionType.UPDATE;
    } else if ("read-only".equals(hint)) {
      type = ActionType.READ;
    } else {
      return destructive ? Optional.of(ActionType.DELETE) : Optional.empty();
    }
    return Optional.of(destructive ? ActionType.riskier(type, ActionType.DELETE) : type);
  }

  /** {@code getHoldings}, {@code get-holdings} and {@code get_holdings} all read as get, holdings. */
  static List<String> words(String name) {
    if (name == null) return List.of();
    String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    return Arrays.stream(spaced.split("[^a-z0-9]+")).filter(w -> !w.isEmpty()).toList();
  }
}
