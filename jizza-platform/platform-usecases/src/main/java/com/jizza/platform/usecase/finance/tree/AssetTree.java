package com.jizza.platform.usecase.finance.tree;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A goal's asset tree and its drift (spec 5.1, screens 7.4 and 7.7). Pure.
 *
 * <p>Each node has a share of its parent; its share of the goal is the product down the path.
 * Siblings that carry a share add up to 100 (invariant 3). An instrument node without a share is a
 * holding the user chose to keep there; it follows its parent.
 *
 * <p>Drift is checked at every node, parents included. The default band is the 5/25 rule: a node
 * whose target is 20% of the goal or more is inside its band within 5 points either way; a smaller
 * one within 25% of its target, relative. A node's own rebalance-band rule replaces that.
 */
public final class AssetTree {

  private AssetTree() {}

  static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** One node. {@code targetPct} is of its parent; null for an instrument kept without a target. */
  public record Node(String id, String parentId, String kind, String key, String name, BigDecimal targetPct,
                     String instrumentId) {}

  /** A band rule: five_twenty_five (with its numbers), absolute points, or relative percent. */
  public record Band(String mode, BigDecimal absolutePts, BigDecimal relativePct, BigDecimal largeFromPct) {
    public static Band fiveTwentyFive() {
      return new Band("five_twenty_five", BigDecimal.valueOf(5), BigDecimal.valueOf(25), BigDecimal.valueOf(20));
    }
  }

  /**
   * A node's drift. Percentages are of the goal's whole value; {@code status} is inside, above,
   * below, or no_target (an instrument kept without one); {@code driftPts} is actual − target.
   */
  public record Drift(String id, String parentId, String kind, String key, String name, int depth,
                      BigDecimal targetPct, BigDecimal actualPct, BigDecimal bandLow, BigDecimal bandHigh,
                      String bandMode, String status, BigDecimal driftPts, BigDecimal value) {}

  /** Why the targets are not whole, or an empty list. */
  public static List<String> problems(List<Node> nodes) {
    List<String> out = new ArrayList<>();
    Map<String, Node> byId = new HashMap<>();
    nodes.forEach(n -> byId.put(n.id(), n));
    Map<String, List<Node>> kids = children(nodes);
    for (var e : kids.entrySet()) {
      List<Node> withTarget = e.getValue().stream().filter(n -> n.targetPct() != null).toList();
      if (withTarget.isEmpty()) continue;
      BigDecimal sum = withTarget.stream().map(Node::targetPct).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (sum.compareTo(HUNDRED) != 0) {
        Node parent = byId.get(e.getKey());
        out.add((parent == null ? "The asset classes" : "The parts of " + parent.name()) + " add up to "
            + plain(sum) + "%, not 100%.");
      }
    }
    for (Node n : nodes) {
      if (n.targetPct() == null && !"instrument".equals(n.kind())) out.add(n.name() + " needs a target.");
      if (n.targetPct() != null && (n.targetPct().signum() < 0 || n.targetPct().compareTo(HUNDRED) > 0)) {
        out.add(n.name() + " is outside 0–100%.");
      }
      if (n.parentId() != null && !byId.containsKey(n.parentId())) out.add(n.name() + " has no parent in this goal.");
    }
    return out;
  }

  /** Each node's target as a share of the whole goal. */
  public static Map<String, BigDecimal> goalTargets(List<Node> nodes) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    Map<String, List<Node>> kids = children(nodes);
    walk(kids, null, HUNDRED, out);
    return out;
  }

  private static void walk(Map<String, List<Node>> kids, String parent, BigDecimal parentPct, Map<String, BigDecimal> out) {
    for (Node n : kids.getOrDefault(parent == null ? ROOT : parent, List.of())) {
      BigDecimal pct = n.targetPct() == null ? null
          : parentPct == null ? null : parentPct.multiply(n.targetPct()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
      out.put(n.id(), pct);
      walk(kids, n.id(), pct, out);
    }
  }

  /**
   * The drift of every node. {@code directValue} is the value held directly at a node (an
   * instrument's holding, or a holding placed at its sub-category); {@code goalTotal} is the
   * goal's whole value, off-tree holdings included. {@code bands} gives a node's own band rule;
   * nodes without one use the 5/25 rule.
   */
  public static List<Drift> drift(List<Node> nodes, Map<String, BigDecimal> directValue, BigDecimal goalTotal,
                                  Map<String, Band> bands) {
    Map<String, BigDecimal> targets = goalTargets(nodes);
    Map<String, List<Node>> kids = children(nodes);
    Map<String, BigDecimal> subtree = new HashMap<>();
    for (Node n : nodes) value(n.id(), kids, directValue, subtree);
    List<Drift> out = new ArrayList<>();
    emit(kids, ROOT, 0, targets, subtree, goalTotal, bands, out);
    return out;
  }

  private static void emit(Map<String, List<Node>> kids, String parent, int depth, Map<String, BigDecimal> targets,
                           Map<String, BigDecimal> subtree, BigDecimal total, Map<String, Band> bands, List<Drift> out) {
    for (Node n : kids.getOrDefault(parent, List.of())) {
      BigDecimal value = subtree.getOrDefault(n.id(), BigDecimal.ZERO);
      BigDecimal actual = total == null || total.signum() == 0 ? BigDecimal.ZERO
          : value.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
      BigDecimal target = targets.get(n.id());
      if (target == null) {
        out.add(new Drift(n.id(), n.parentId(), n.kind(), n.key(), n.name(), depth, null, actual, null, null,
            null, "no_target", null, value));
      } else {
        Band band = bands == null ? null : bands.get(n.id());
        if (band == null) band = Band.fiveTwentyFive();
        BigDecimal[] lohi = band(target, band);
        String status = actual.compareTo(lohi[1]) > 0 ? "above" : actual.compareTo(lohi[0]) < 0 ? "below" : "inside";
        out.add(new Drift(n.id(), n.parentId(), n.kind(), n.key(), n.name(), depth, scale(target), actual,
            scale(lohi[0]), scale(lohi[1]), band.mode(), status, actual.subtract(scale(target)), value));
      }
      emit(kids, n.id(), depth + 1, targets, subtree, total, bands, out);
    }
  }

  /** The band around a target (both ends kept within 0–100). */
  public static BigDecimal[] band(BigDecimal target, Band b) {
    BigDecimal width = switch (b.mode()) {
      case "absolute" -> b.absolutePts();
      case "relative" -> target.multiply(b.relativePct()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
      default -> target.compareTo(b.largeFromPct()) >= 0 ? b.absolutePts()
          : target.multiply(b.relativePct()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
    };
    BigDecimal lo = target.subtract(width).max(BigDecimal.ZERO);
    BigDecimal hi = target.add(width).min(HUNDRED);
    return new BigDecimal[] {lo, hi};
  }

  private static BigDecimal value(String id, Map<String, List<Node>> kids, Map<String, BigDecimal> direct,
                                  Map<String, BigDecimal> memo) {
    BigDecimal have = memo.get(id);
    if (have != null) return have;
    BigDecimal v = direct.getOrDefault(id, BigDecimal.ZERO);
    for (Node c : kids.getOrDefault(id, List.of())) v = v.add(value(c.id(), kids, direct, memo));
    memo.put(id, v);
    return v;
  }

  static final String ROOT = "\u0000root";

  static Map<String, List<Node>> children(List<Node> nodes) {
    Map<String, List<Node>> out = new LinkedHashMap<>();
    for (Node n : nodes) out.computeIfAbsent(n.parentId() == null ? ROOT : n.parentId(), k -> new ArrayList<>()).add(n);
    return out;
  }

  private static BigDecimal scale(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
  }

  static String plain(BigDecimal v) {
    BigDecimal s = Objects.requireNonNull(v).stripTrailingZeros();
    return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
  }
}
