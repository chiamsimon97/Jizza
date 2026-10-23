package com.jizza.engine.mcp;

import com.jizza.engine.api.enums.ActionType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Suggests what an MCP tool does from its name and description, so the guardrails of the agent it
 * is given to apply from the start: a {@code get_holdings} is a read and runs under the agent's
 * rule for reads, a {@code place_order} moves money. It is only a suggestion, shown as one and
 * changed by the user; a tool it cannot place gets none and is judged the most consequential kind.
 *
 * <p>The server's own hints are not used. MCP defaults a tool with no annotations to
 * "destructive", and libraries such as mcp-go (behind Zerodha Kite) send that default for every
 * tool, so it says nothing about a {@code get_holdings}.
 */
public final class McpToolClassifier {

  private static final Set<String> READ = Set.of("get", "gets", "list", "lists", "search",
      "searches", "fetch", "fetches", "find", "finds", "read", "reads", "show", "shows", "view",
      "lookup", "query", "describe", "describes", "check", "checks", "status", "browse", "login",
      "logout", "whoami", "profile", "count", "preview", "download", "export", "retrieve",
      "retrieves", "return", "returns", "look", "looks", "see", "inspect", "analyze", "analyse",
      "calculate", "calculates", "compute", "estimate", "summarize", "summarise", "validate");
  private static final Set<String> MONEY = Set.of("order", "orders", "trade", "trades", "buy",
      "sell", "pay", "payment", "payout", "transfer", "withdraw", "deposit", "fund", "funds",
      "invest", "gtt", "sip", "mf", "charge", "refund", "invoice", "purchase", "checkout",
      "subscribe");
  private static final Set<String> DELETE = Set.of("delete", "deletes", "remove", "removes",
      "cancel", "cancels", "revoke", "drop", "destroy", "archive", "purge", "clear", "unsubscribe",
      "erase", "wipe", "exit");
  private static final Set<String> SEND = Set.of("send", "sends", "email", "mail", "message",
      "post", "posts", "notify", "reply", "publish", "tweet", "sms", "invite", "share");
  private static final Set<String> CREATE = Set.of("create", "creates", "add", "adds", "new",
      "insert", "upload", "make", "generate", "place", "places", "book", "schedule", "start",
      "open");
  private static final Set<String> UPDATE = Set.of("update", "updates", "modify", "modifies",
      "edit", "set", "sets", "rename", "move", "change", "changes", "patch", "replace", "assign",
      "close", "enable", "disable", "save", "convert");

  private static final Set<String> WIPES = Set.of("delete", "remove", "erase", "wipe", "purge",
      "destroy");

  private McpToolClassifier() { }

  /** From the name; the description decides only when the name says nothing. */
  public static Optional<ActionType> suggest(String name, String description) {
    Optional<ActionType> fromName = fromWords(words(name));
    if (fromName.isPresent()) return fromName;
    // "Retrieve the user's profile…": the description's opening verb says what it does.
    List<String> opening = words(description).stream().limit(1).toList();
    if (opening.isEmpty()) return Optional.empty();
    return fromWords(List.of(opening.getFirst())).filter(t -> t == ActionType.READ)
        .or(() -> fromWords(words(description).stream().limit(4).toList()));
  }

  private static Optional<ActionType> fromWords(List<String> words) {
    if (words.isEmpty()) return Optional.empty();
    String verb = words.getFirst();
    // A read that also names a wipe (get_and_delete) is judged by the wipe.
    ActionType also = words.stream().skip(1).anyMatch(WIPES::contains) ? ActionType.DELETE : null;
    if (READ.contains(verb)) {
      return Optional.of(also == null ? ActionType.READ : also);
    }
    if (words.stream().anyMatch(MONEY::contains)) return Optional.of(ActionType.MONEY);
    ActionType own = writeOf(verb);
    if (own != null) return Optional.of(ActionType.riskier(own, also));
    return Optional.empty();
  }

  /** The write a word names, or null; only verbs count, so "orders" in get_orders does not. */
  private static ActionType writeOf(String word) {
    if (DELETE.contains(word)) return ActionType.DELETE;
    if (SEND.contains(word)) return ActionType.SEND;
    if (CREATE.contains(word) && !"new".equals(word) && !"open".equals(word)) {
      return ActionType.CREATE;
    }
    if (UPDATE.contains(word) && !"set".equals(word) && !"close".equals(word)) {
      return ActionType.UPDATE;
    }
    return null;
  }

  /** {@code getHoldings}, {@code get-holdings} and {@code get_holdings} all read as get, holdings. */
  static List<String> words(String name) {
    if (name == null) return List.of();
    String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    return Arrays.stream(spaced.split("[^a-z0-9]+")).filter(w -> !w.isEmpty()).toList();
  }
}
