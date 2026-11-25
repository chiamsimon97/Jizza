package com.jizza.platform.usecase.finance.builder;

import static com.jizza.platform.usecase.finance.model.Inr.HUNDRED;
import static com.jizza.platform.usecase.finance.model.Inr.nz;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import com.jizza.platform.usecase.finance.model.PlanConfig.AgeRow;
import com.jizza.platform.usecase.finance.model.PlanConfig.LongRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The plan builder's engine (spec 5.12, 2A.8): the user says how much goes in, and Kai splits it
 * down to sub-category. A pure function of its input and {@link PlanConfig}.
 *
 * <ol>
 *   <li>Survival needs come off the top in the 2A.2 order: the starter-buffer gap (up to 3 months
 *       of essential spending) takes everything until it is filled, then loans above the
 *       high-interest line, then the rest of the emergency gap with at most half of what is left.
 *   <li>What is left splits into Short, Mid and Long: each goal's required SIP in its own bucket
 *       first (Survival, then Important, then Aspirational goals, nearest date first), the rest by
 *       the age table.
 *   <li>Inside each bucket: asset classes (Short by Kai's default, Mid by risk profile, Long by
 *       age), then sub-categories (Long equity by risk profile).
 *   <li>At every level each amount is rounded half up to ₹100 and the rounding remainder goes to
 *       the largest part, so the parts always add up to their parent exactly.
 *   <li>A node the user set keeps its amount; its unset siblings share what is left in their
 *       default proportions, so the total never changes.
 *   <li>Lumpsum: Short and Mid go in today; Long debt and gold go in today; Long equity is parked
 *       in a liquid fund and moves in by STP over {@code stpMonths} months.
 * </ol>
 *
 * <p>Nothing here names a fund: the leaves are sub-categories. Nothing is bought.
 */
public final class AllocationResolver {

  private AllocationResolver() {}

  static final BigDecimal STEP = BigDecimal.valueOf(100);
  static final BigDecimal HALF = new BigDecimal("0.5");

  public static final String SHORT = "short";
  public static final String MID = "mid";
  public static final String LONG = "long";
  public static final List<String> BUCKETS = List.of(SHORT, MID, LONG);

  /** Survival needs, as totals still to fund (zero when met). */
  public record Needs(BigDecimal starterGap, BigDecimal restEmergencyGap, BigDecimal highInterestDebt,
                      BigDecimal highInterestRatePct) {
    public static Needs none() {
      return new Needs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(12));
    }
  }

  /** A goal's monthly need, placed in its bucket by target date. */
  public record GoalNeed(String goalId, String name, String bucket, String tier,
                         LocalDate targetDate, BigDecimal requiredSip) {}

  /** A node the user set: its key (as in the tree) and the monthly amount. */
  public record Edit(String key, BigDecimal monthly) {}

  public record Input(BigDecimal monthly, BigDecimal lumpsum, int age, String risk, Needs needs,
                      List<GoalNeed> goals, List<Edit> edits) {}

  public record OffTheTop(String key, String label, BigDecimal monthly, BigDecimal lumpsum,
                          String reason) {}

  /**
   * One node of the split. {@code pct} is the node's share of its parent's monthly amount.
   * {@code source} is system, age, risk, goal or user; {@code sourceLabel} is the badge text.
   */
  public record Node(String key, String label, String level, BigDecimal monthly, BigDecimal lumpsum,
                     BigDecimal pct, String source, String sourceLabel, boolean edited,
                     List<Node> children) {}

  /** A leaf: one sub-category in one bucket. {@code pct} is its share of the monthly invested. */
  public record Line(String key, String bucket, String assetClass, String subCategory, String label,
                     BigDecimal monthly, BigDecimal lumpsum, BigDecimal pct, String source,
                     String sourceLabel) {}

  /** The goal lens: what each goal gets in each bucket ({@code goalId} null for general money). */
  public record GoalShare(String goalId, String name, String bucket, BigDecimal monthly,
                          BigDecimal required) {}

  public record TodayItem(String key, String label, BigDecimal amount) {}

  public record StpRow(int month, BigDecimal amount) {}

  /** What the lumpsum does: invested today, and the Long equity share moved in by STP. */
  public record Timeline(List<TodayItem> today, BigDecimal investedToday, BigDecimal parked,
                         int stpMonths, List<StpRow> stp) {}

  public record Result(BigDecimal monthlyIn, BigDecimal lumpsumIn, List<OffTheTop> offTheTop,
                       BigDecimal monthlyInvested, BigDecimal lumpsumInvested, int age, String risk,
                       Node tree, List<Line> lines, List<GoalShare> goalLens, Timeline timeline,
                       List<String> notes) {

    public Line line(String key) {
      return lines.stream().filter(l -> l.key().equals(key)).findFirst().orElse(null);
    }

    public Node node(String key) {
      return find(tree, key);
    }

    private static Node find(Node n, String key) {
      if (n.key().equals(key)) return n;
      for (Node c : n.children()) {
        Node f = find(c, key);
        if (f != null) return f;
      }
      return null;
    }
  }

  // ── the shape of the tree ────────────────────────────────────────────────────

  /** A default child: key suffix, label, weight, and the badge it carries. */
  record Part(String key, String label, BigDecimal weight, String source, String sourceLabel) {}

  static final Map<String, String> BUCKET_LABELS =
      Map.of(SHORT, "Short (under 3 years)", MID, "Mid (3–7 years)", LONG, "Long (7 years and more)");

  static final Map<String, String> CLASS_LABELS = Map.of("cash", "Cash and liquid", "debt", "Debt",
      "equity", "Equity", "gold", "Gold");

  /** Sub-category key → label. A sub-category is never a fund's name. */
  public static final Map<String, String> SUB_LABELS = Map.ofEntries(
      Map.entry("liquid", "Liquid fund"),
      Map.entry("arbitrage_ultra_short", "Arbitrage / ultra-short"),
      Map.entry("balanced_multi_asset", "Balanced advantage / multi-asset"),
      Map.entry("short_duration", "Short duration / banking & PSU"),
      Map.entry("gold", "Gold ETF or fund"),
      Map.entry("large_cap_index", "Large cap / Nifty 50 index"),
      Map.entry("flexi_cap", "Flexi cap"),
      Map.entry("mid_cap", "Mid cap"),
      Map.entry("small_cap", "Small cap"),
      Map.entry("ppf_debt", "PPF / debt funds"));

  static final Map<String, String> EQUITY_SUB_KEYS = Map.of("largeCapOrIndex", "large_cap_index",
      "flexiCap", "flexi_cap", "midCap", "mid_cap", "smallCap", "small_cap");

  static String riskWord(String risk) {
    return risk.substring(0, 1).toUpperCase(Locale.ROOT) + risk.substring(1);
  }

  /** The Long bucket's mix for an age and risk: Conservative takes the low end of the equity range. */
  public static Map<String, BigDecimal> longMix(LongRow row, String risk) {
    BigDecimal equity = switch (risk) {
      case "conservative" -> row.equityMin();
      case "aggressive" -> row.equityMax();
      default -> row.equity();
    };
    Map<String, BigDecimal> m = new LinkedHashMap<>();
    m.put("equity", equity);
    m.put("debt", HUNDRED.subtract(equity).subtract(row.gold()));
    m.put("gold", row.gold());
    return m;
  }

  /** The Short bucket's mix: cash and liquid, and arbitrage or ultra-short debt. */
  public static Map<String, BigDecimal> shortMix(PlanConfig cfg) {
    Map<String, BigDecimal> m = new LinkedHashMap<>();
    m.put("cash", nz(cfg.shortMix().get("cashAndLiquid")));
    m.put("debt", nz(cfg.shortMix().get("arbitrageOrUltraShort")));
    return m;
  }

  static List<Part> classParts(String bucket, int age, String risk, PlanConfig cfg) {
    List<Part> out = new ArrayList<>();
    switch (bucket) {
      case SHORT -> shortMix(cfg).forEach((k, w) ->
          out.add(new Part(k, CLASS_LABELS.get(k), w, "system", "Kai default")));
      case MID -> cfg.midByRisk().get(risk).forEach((k, w) ->
          out.add(new Part(k, CLASS_LABELS.get(k), w, "risk", riskWord(risk))));
      default -> {
        LongRow row = cfg.longFor(age);
        String label = "moderate".equals(risk) ? row.label() : row.label() + " · " + riskWord(risk);
        longMix(row, risk).forEach((k, w) -> out.add(new Part(k, CLASS_LABELS.get(k), w, "age", label)));
      }
    }
    out.removeIf(p -> p.weight().signum() == 0);
    return out;
  }

  static List<Part> subParts(String bucket, String assetClass, String risk, PlanConfig cfg) {
    if (LONG.equals(bucket) && "equity".equals(assetClass)) {
      List<Part> out = new ArrayList<>();
      cfg.equitySubByRisk().get(risk).forEach((k, w) -> {
        String key = EQUITY_SUB_KEYS.get(k);
        if (key != null && w.signum() > 0) out.add(new Part(key, SUB_LABELS.get(key), w, "risk", riskWord(risk)));
      });
      return out;
    }
    String sub = switch (bucket + "." + assetClass) {
      case "short.cash" -> "liquid";
      case "short.debt" -> "arbitrage_ultra_short";
      case "mid.equity" -> "balanced_multi_asset";
      case "mid.debt" -> "short_duration";
      case "long.debt" -> "ppf_debt";
      case "mid.gold", "long.gold" -> "gold";
      default -> "liquid";
    };
    return List.of(new Part(sub, SUB_LABELS.get(sub), HUNDRED, "system", "Kai default"));
  }

  // ── the engine ───────────────────────────────────────────────────────────────

  public static Result resolve(Input in, PlanConfig cfg) {
    String risk = in.risk() == null ? "moderate" : in.risk();
    if (!PlanConfig.RISKS.contains(risk)) throw new ValidationException("riskProfile must be conservative, moderate or aggressive.");
    BigDecimal monthly = Inr.rupees(in.monthly());
    BigDecimal lumpsum = Inr.rupees(in.lumpsum());
    if (monthly.signum() < 0 || lumpsum.signum() < 0) throw new ValidationException("Amounts cannot be negative.");
    Needs needs = in.needs() == null ? Needs.none() : in.needs();
    List<String> notes = new ArrayList<>();

    // 1. Off the top: lumpsum first, then what each month still has to cover.
    List<OffTheTop> top = new ArrayList<>();
    BigDecimal starter = nz(needs.starterGap()).max(BigDecimal.ZERO);
    BigDecimal debt = nz(needs.highInterestDebt()).max(BigDecimal.ZERO);
    BigDecimal restGap = nz(needs.restEmergencyGap()).max(BigDecimal.ZERO);

    BigDecimal starterL = lumpsum.min(starter);
    BigDecimal debtL = lumpsum.subtract(starterL).min(debt);
    BigDecimal restL = Inr.rupees(lumpsum.subtract(starterL).subtract(debtL).multiply(HALF)).min(restGap);
    BigDecimal investL = lumpsum.subtract(starterL).subtract(debtL).subtract(restL);

    BigDecimal starterM = monthly.min(starter.subtract(starterL));
    BigDecimal debtM = monthly.subtract(starterM).min(debt.subtract(debtL));
    BigDecimal restM = Inr.rupees(monthly.subtract(starterM).subtract(debtM).multiply(HALF))
        .min(restGap.subtract(restL));
    BigDecimal investM = monthly.subtract(starterM).subtract(debtM).subtract(restM);

    if (starterM.signum() > 0 || starterL.signum() > 0) {
      top.add(new OffTheTop("starter_buffer", "Emergency fund to 3 months", starterM, starterL,
          "Your emergency money covers less than 3 months of essential spending; it is "
              + Inr.group(starter) + " short. Everything goes here first."));
    }
    if (debtM.signum() > 0 || debtL.signum() > 0) {
      top.add(new OffTheTop("high_interest_debt", "Repay loans above " + Inr.pctWords(needs.highInterestRatePct()),
          debtM, debtL, Inr.group(debt) + " is owed on loans costing more than "
              + Inr.pctWords(needs.highInterestRatePct()) + " a year. Repaying them earns more than investing."));
    }
    if (restM.signum() > 0 || restL.signum() > 0) {
      top.add(new OffTheTop("emergency_fund", "Emergency fund to 6 months", restM, restL,
          "The emergency fund is " + Inr.group(restGap) + " short of 6 months; half of what is left goes here."));
    }

    // 2. Horizon split: goals first, then the age table.
    AgeRow ageRow = cfg.horizonFor(in.age());
    Map<String, BigDecimal> goalPart = new LinkedHashMap<>();
    BUCKETS.forEach(b -> goalPart.put(b, BigDecimal.ZERO));
    Map<String, List<GoalNeed>> goalsIn = new HashMap<>();
    List<GoalShare> lens = new ArrayList<>();
    BigDecimal left = investM;
    List<GoalNeed> goals = new ArrayList<>(in.goals() == null ? List.of() : in.goals());
    goals.sort(Comparator.comparing((GoalNeed g) -> tierRank(g.tier()))
        .thenComparing(GoalNeed::targetDate, Comparator.nullsLast(Comparator.naturalOrder())));
    for (GoalNeed g : goals) {
      BigDecimal need = nz(g.requiredSip()).max(BigDecimal.ZERO);
      BigDecimal give = need.min(left);
      left = left.subtract(give);
      goalPart.merge(g.bucket(), give, BigDecimal::add);
      goalsIn.computeIfAbsent(g.bucket(), k -> new ArrayList<>()).add(g);
      lens.add(new GoalShare(g.goalId(), g.name(), g.bucket(), give, need));
      if (give.compareTo(need) < 0) {
        notes.add(g.name() + " needs " + Inr.group(need) + " a month; " + Inr.group(give)
            + " is left for it after the goals before it.");
      }
    }
    Map<String, BigDecimal> ageWeights = new LinkedHashMap<>();
    ageWeights.put(SHORT, ageRow.shortPct());
    ageWeights.put(MID, ageRow.midPct());
    ageWeights.put(LONG, ageRow.longPct());
    Map<String, BigDecimal> general = distribute(left, BUCKETS, ageWeights, Map.of(), "the plan");
    Map<String, BigDecimal> bucketDefault = new LinkedHashMap<>();
    for (String b : BUCKETS) {
      bucketDefault.put(b, goalPart.get(b).add(general.get(b)));
      if (general.get(b).signum() > 0) lens.add(new GoalShare(null, "General savings", b, general.get(b), null));
    }

    // 3–5. Build the tree with the user's edits.
    Map<String, BigDecimal> edits = new HashMap<>();
    for (Edit e : in.edits() == null ? List.<Edit>of() : in.edits()) {
      if (e.key() == null || e.monthly() == null) throw new ValidationException("Each change needs a node and an amount.");
      if (e.monthly().signum() < 0) throw new ValidationException("An amount cannot be negative.");
      edits.put(e.key(), Inr.rupees(e.monthly()));
    }
    Set<String> used = new HashSet<>();

    List<Part> bucketParts = new ArrayList<>();
    for (String b : BUCKETS) {
      List<GoalNeed> gs = goalsIn.getOrDefault(b, List.of());
      boolean hasGoal = goalPart.get(b).signum() > 0;
      String label = hasGoal ? goalBadge(gs) : ageRow.label();
      bucketParts.add(new Part(b, BUCKET_LABELS.get(b), bucketDefault.get(b), hasGoal ? "goal" : "age", label));
    }
    // Lumpsum buckets follow the age table: goals ask for a monthly amount, not a lumpsum.
    Map<String, BigDecimal> lumpWeights = ageWeights;

    List<Node> bucketNodes = new ArrayList<>();
    Map<String, BigDecimal> bucketMonthly = split(investM, bucketParts, edits, used, "", true);
    Map<String, BigDecimal> bucketLump = splitLump(investL, bucketParts, lumpWeights, bucketMonthly, investM, edits, "");
    for (Part bp : bucketParts) {
      String b = bp.key();
      List<Part> classes = classParts(b, in.age(), risk, cfg);
      Map<String, BigDecimal> cm = split(bucketMonthly.get(b), classes, edits, used, b + ".", false);
      Map<String, BigDecimal> cl = splitLump(bucketLump.get(b), classes, null, cm, bucketMonthly.get(b), edits, b + ".");
      List<Node> classNodes = new ArrayList<>();
      for (Part cp : classes) {
        String ck = b + "." + cp.key();
        List<Part> subs = subParts(b, cp.key(), risk, cfg);
        Map<String, BigDecimal> sm = split(cm.get(cp.key()), subs, edits, used, ck + ".", false);
        Map<String, BigDecimal> sl = splitLump(cl.get(cp.key()), subs, null, sm, cm.get(cp.key()), edits, ck + ".");
        List<Node> subNodes = new ArrayList<>();
        for (Part sp : subs) {
          String sk = ck + "." + sp.key();
          subNodes.add(node(sk, sp, "sub_category", sm.get(sp.key()), sl.get(sp.key()), cm.get(cp.key()),
              edits.containsKey(sk), List.of()));
        }
        classNodes.add(node(ck, cp, "asset_class", cm.get(cp.key()), cl.get(cp.key()), bucketMonthly.get(b),
            edits.containsKey(ck), subNodes));
      }
      bucketNodes.add(node(b, bp, "bucket", bucketMonthly.get(b), bucketLump.get(b), investM,
          edits.containsKey(b), classNodes));
    }
    for (String key : edits.keySet()) {
      if (!used.contains(key)) throw new ValidationException("There is no part called " + key + " in this plan.");
    }
    Node root = new Node("all", "Money in", "root", investM, investL, HUNDRED, "system", "Kai default",
        false, bucketNodes);

    // Lines (leaves) and the lumpsum timeline.
    List<Line> lines = new ArrayList<>();
    List<TodayItem> today = new ArrayList<>();
    BigDecimal parked = BigDecimal.ZERO;
    for (Node b : bucketNodes) {
      for (Node c : b.children()) {
        for (Node s : c.children()) {
          String[] parts = s.key().split("\\.");
          BigDecimal share = investM.signum() == 0 ? BigDecimal.ZERO : Inr.one(Inr.pct(s.monthly(), investM));
          lines.add(new Line(s.key(), parts[0], parts[1], parts[2], s.label(), s.monthly(), s.lumpsum(),
              share, s.source(), s.sourceLabel()));
          if (s.lumpsum().signum() == 0) continue;
          if (LONG.equals(parts[0]) && "equity".equals(parts[1])) {
            parked = parked.add(s.lumpsum());
          } else {
            today.add(new TodayItem(s.key(), b.label().split(" ")[0] + " · " + s.label(), s.lumpsum()));
          }
        }
      }
    }
    BigDecimal investedToday = today.stream().map(TodayItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    List<StpRow> stp = new ArrayList<>();
    int months = Math.max(1, cfg.stpMonths());
    if (parked.signum() > 0) {
      today.add(new TodayItem("long.equity.parked", "Long · Liquid fund, parked for the STP", parked));
      BigDecimal per = round100(parked.divide(BigDecimal.valueOf(months), 10, RoundingMode.HALF_UP));
      if (per.multiply(BigDecimal.valueOf(months - 1)).compareTo(parked) > 0) {
        per = parked.divide(BigDecimal.valueOf(months), 0, RoundingMode.DOWN);
      }
      BigDecimal sent = BigDecimal.ZERO;
      for (int m = 1; m <= months; m++) {
        BigDecimal amount = m == months ? parked.subtract(sent) : per;
        stp.add(new StpRow(m, amount));
        sent = sent.add(amount);
      }
    }
    if (investL.signum() > 0 && parked.signum() > 0) {
      notes.add("An STP needs both funds in the same fund house; otherwise Kai schedules a monthly "
          + "redemption and a matching purchase, shown as a manual STP.");
    }
    return new Result(monthly, lumpsum, top, investM, investL, in.age(), risk, root, lines, lens,
        new Timeline(today, investedToday, parked, months, stp), notes);
  }

  private static Node node(String key, Part p, String level, BigDecimal monthly, BigDecimal lumpsum,
                           BigDecimal parentMonthly, boolean edited, List<Node> children) {
    BigDecimal pct = parentMonthly == null || parentMonthly.signum() == 0 ? BigDecimal.ZERO
        : Inr.one(Inr.pct(monthly, parentMonthly));
    return new Node(key, p.label(), level, monthly, lumpsum, pct, edited ? "user" : p.source(),
        edited ? "You" : p.sourceLabel(), edited, children);
  }

  /** Splits a monthly amount among parts, keeping the user's amounts and rebalancing the rest. */
  static Map<String, BigDecimal> split(BigDecimal total, List<Part> parts, Map<String, BigDecimal> edits,
                                       Set<String> used, String prefix, boolean weightsAreAmounts) {
    List<String> keys = parts.stream().map(Part::key).toList();
    Map<String, BigDecimal> weights = new LinkedHashMap<>();
    parts.forEach(p -> weights.put(p.key(), p.weight()));
    Map<String, BigDecimal> pinned = new LinkedHashMap<>();
    for (String k : keys) {
      BigDecimal e = edits.get(prefix + k);
      if (e != null) {
        pinned.put(k, e);
        used.add(prefix + k);
      }
    }
    if (pinned.isEmpty() && weightsAreAmounts) {
      BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      if (sum.compareTo(total) == 0) return weights;
    }
    return distribute(total, keys, weights, pinned, prefix.isEmpty() ? "the plan" : prefix.substring(0, prefix.length() - 1));
  }

  /**
   * Splits the lumpsum among the same parts. A part the user set keeps the same share of its
   * parent as it has in the monthly split; the rest follow {@code weights} (or the parts' own).
   */
  static Map<String, BigDecimal> splitLump(BigDecimal total, List<Part> parts, Map<String, BigDecimal> weights,
                                           Map<String, BigDecimal> monthly, BigDecimal parentMonthly,
                                           Map<String, BigDecimal> edits, String prefix) {
    List<String> keys = parts.stream().map(Part::key).toList();
    Map<String, BigDecimal> w = new LinkedHashMap<>();
    parts.forEach(p -> w.put(p.key(), weights != null ? weights.get(p.key()) : p.weight()));
    Map<String, BigDecimal> pinned = new LinkedHashMap<>();
    if (parentMonthly != null && parentMonthly.signum() > 0) {
      for (String k : keys) {
        if (edits.containsKey(prefix + k)) {
          pinned.put(k, round100(total.multiply(monthly.get(k)).divide(parentMonthly, 10, RoundingMode.HALF_UP)).min(total));
        }
      }
    }
    BigDecimal pinnedSum = pinned.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    if (pinnedSum.compareTo(total) > 0 || (pinned.size() == keys.size() && pinnedSum.compareTo(total) != 0)) {
      pinned.clear();
      for (String k : keys) w.put(k, monthly.get(k));
    }
    return distribute(total, keys, w, pinned, "the lumpsum");
  }

  /**
   * Shares {@code total} among {@code keys}: pinned keys keep their amounts, the others share the
   * rest by weight, each rounded half up to ₹100, with the remainder on the largest of them.
   */
  public static Map<String, BigDecimal> distribute(BigDecimal total, List<String> keys,
                                                   Map<String, BigDecimal> weights,
                                                   Map<String, BigDecimal> pinned, String what) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    BigDecimal pinnedSum = BigDecimal.ZERO;
    for (String k : keys) {
      if (pinned.containsKey(k)) {
        out.put(k, pinned.get(k));
        pinnedSum = pinnedSum.add(pinned.get(k));
      }
    }
    if (pinnedSum.compareTo(total) > 0) {
      throw new ValidationException("The amounts you set add up to " + Inr.group(pinnedSum) + ", more than the "
          + Inr.group(total) + " in " + what + ".");
    }
    List<String> free = keys.stream().filter(k -> !pinned.containsKey(k)).toList();
    BigDecimal rest = total.subtract(pinnedSum);
    if (free.isEmpty()) {
      if (rest.signum() != 0) {
        throw new ValidationException("The amounts you set add up to " + Inr.group(pinnedSum) + ", not the "
            + Inr.group(total) + " in " + what + ". Leave one part for Kai to fill.");
      }
      return reorder(out, keys);
    }
    BigDecimal weightSum = free.stream().map(k -> nz(weights.get(k))).reduce(BigDecimal.ZERO, BigDecimal::add);
    Map<String, BigDecimal> shares = new LinkedHashMap<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (String k : free) {
      BigDecimal raw = weightSum.signum() == 0
          ? (k.equals(free.get(0)) ? rest : BigDecimal.ZERO)
          : rest.multiply(nz(weights.get(k))).divide(weightSum, 10, RoundingMode.HALF_UP);
      BigDecimal r = round100(raw);
      shares.put(k, r);
      sum = sum.add(r);
    }
    BigDecimal diff = rest.subtract(sum);
    if (diff.signum() != 0) {
      String largest = free.get(0);
      for (String k : free) if (shares.get(k).compareTo(shares.get(largest)) > 0) largest = k;
      shares.put(largest, shares.get(largest).add(diff));
      if (shares.get(largest).signum() < 0) shares.put(largest, BigDecimal.ZERO);
    }
    out.putAll(shares);
    return reorder(out, keys);
  }

  private static Map<String, BigDecimal> reorder(Map<String, BigDecimal> m, List<String> keys) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    keys.forEach(k -> out.put(k, m.get(k)));
    return out;
  }

  /** Half up to the nearest ₹100. */
  static BigDecimal round100(BigDecimal v) {
    return v.divide(STEP, 0, RoundingMode.HALF_UP).multiply(STEP);
  }

  private static int tierRank(String tier) {
    return switch (tier == null ? "" : tier) {
      case "survival" -> 0;
      case "important" -> 1;
      default -> 2;
    };
  }

  private static String goalBadge(List<GoalNeed> goals) {
    GoalNeed first = goals.get(0);
    String one = "Goal: " + first.name() + (first.targetDate() == null ? "" : " " + first.targetDate().getYear());
    return goals.size() == 1 ? one : one + " + " + (goals.size() - 1) + " more";
  }
}
