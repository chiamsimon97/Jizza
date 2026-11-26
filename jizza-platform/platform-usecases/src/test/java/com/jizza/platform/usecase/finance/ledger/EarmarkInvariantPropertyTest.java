package com.jizza.platform.usecase.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.finance.ledger.Earmarks.Claim;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.Lot;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.TermRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Invariant 1 holds after any mix of buys, sales by a goal, sales outside Kai, re-earmarks,
 * splits and bonuses: the goals' units always add up to what the lots still hold, and no claim
 * or lot ever goes below zero. Seeded, so a failure can be replayed.
 */
class EarmarkInvariantPropertyTest {

  static final List<String> GOALS = List.of("U", "P1", "P2", "P3");

  @Test
  void invariantHoldsAfterAnySequence() {
    for (long seed = 1; seed <= 400; seed++) run(seed);
  }

  private void run(long seed) {
    Random rnd = new Random(seed);
    List<Lot> lots = new ArrayList<>();
    List<Claim> claims = new ArrayList<>();
    LocalDate day = LocalDate.of(2020, 1, 1);
    int ids = 0;
    for (int step = 0; step < 60; step++) {
      day = day.plusDays(1 + rnd.nextInt(40));
      BigDecimal held = TaxLotLedger.remaining(lots);
      int op = rnd.nextInt(6);
      String context = "seed " + seed + " step " + step + " op " + op;
      switch (op) {
        case 0 -> { // buy for a goal or for Unassigned
          BigDecimal q = units(rnd);
          lots.add(new Lot("L" + (ids++), day, true, q, q, BigDecimal.valueOf(100 + rnd.nextInt(900))));
          claims = Earmarks.buyFor(claims, GOALS.get(rnd.nextInt(GOALS.size())), q).claims();
        }
        case 1 -> { // a goal sells some of its own units
          if (claims.isEmpty()) continue;
          Claim c = claims.get(rnd.nextInt(claims.size()));
          BigDecimal q = c.units().multiply(BigDecimal.valueOf(rnd.nextInt(100) + 1))
              .divide(BigDecimal.valueOf(100), TaxLotLedger.UNITS, RoundingMode.DOWN);
          if (q.signum() == 0) continue;
          claims = Earmarks.sellFrom(claims, c.planId(), q).claims();
          lots = TaxLotLedger.sell(lots, q, q.multiply(BigDecimal.TEN), day, TermRule.months(12)).after();
        }
        case 2 -> { // an import shows fewer units: sold outside Kai
          if (held.signum() == 0) continue;
          BigDecimal q = held.multiply(BigDecimal.valueOf(rnd.nextInt(100) + 1))
              .divide(BigDecimal.valueOf(100), TaxLotLedger.UNITS, RoundingMode.DOWN);
          if (q.signum() == 0) continue;
          claims = Earmarks.reduce(claims, q, "U").claims();
          lots = TaxLotLedger.sell(lots, q, null, day, TermRule.months(12)).after();
        }
        case 3 -> { // re-earmark between two goals
          if (claims.isEmpty()) continue;
          Claim from = claims.get(rnd.nextInt(claims.size()));
          String to = GOALS.get(rnd.nextInt(GOALS.size()));
          if (to.equals(from.planId())) continue;
          BigDecimal q = from.units().divide(BigDecimal.valueOf(1 + rnd.nextInt(4)), TaxLotLedger.UNITS, RoundingMode.DOWN);
          if (q.signum() == 0) continue;
          claims = Earmarks.reassign(claims, from.planId(), to, q).claims();
        }
        case 4 -> { // split 1 → n or bonus n : d, every lot scaled and claims follow
          if (held.signum() == 0) continue;
          int num = 1 + rnd.nextInt(5);
          int den = 1 + rnd.nextInt(3);
          List<Lot> scaled = new ArrayList<>();
          for (Lot l : lots) {
            BigDecimal q = l.qty().multiply(BigDecimal.valueOf(num)).divide(BigDecimal.valueOf(den), TaxLotLedger.UNITS, RoundingMode.DOWN);
            BigDecimal r = l.remaining().multiply(BigDecimal.valueOf(num)).divide(BigDecimal.valueOf(den), TaxLotLedger.UNITS, RoundingMode.DOWN);
            if (q.signum() == 0) continue;
            scaled.add(new Lot(l.id(), l.acquiredOn(), true, q, r.min(q), l.costPerUnit()));
          }
          lots = scaled;
          claims = Earmarks.scale(claims, TaxLotLedger.remaining(lots)).claims();
        }
        default -> { // a typed reconcile: whatever the claims miss goes to Unassigned
          claims = Earmarks.reconcile(claims, held, "U").claims();
        }
      }
      BigDecimal now = TaxLotLedger.remaining(lots);
      assertThat(Earmarks.total(claims)).as(context).isEqualByComparingTo(now);
      assertThat(claims).as(context).allSatisfy(c -> assertThat(c.units().signum()).isPositive());
      assertThat(lots).as(context).allSatisfy(l -> assertThat(l.remaining().signum()).isNotNegative());
    }
  }

  private static BigDecimal units(Random rnd) {
    return rnd.nextBoolean() ? BigDecimal.valueOf(1 + rnd.nextInt(200))
        : BigDecimal.valueOf(1 + rnd.nextInt(5_000_000), 3);
  }
}
