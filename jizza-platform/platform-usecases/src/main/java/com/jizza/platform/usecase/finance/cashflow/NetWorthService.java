package com.jizza.platform.usecase.finance.cashflow;

import static com.jizza.platform.usecase.finance.model.Inr.nz;
import static com.jizza.platform.usecase.finance.model.Inr.rupees;

import com.jizza.platform.usecase.finance.model.Household;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Net worth (5.7): every asset at its current value (pledged gold included) plus money lent that
 * has not come back, minus every loan, per person and for the whole profile.
 */
public final class NetWorthService {

  private NetWorthService() {}

  public record Slice(String key, BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth) {}

  public record NetWorth(BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth,
                         List<Slice> byClass, List<Slice> byPerson, BigDecimal pledged) {}

  /** {@code personId} null means the whole profile. */
  public static NetWorth compute(Household h, String personId) {
    Map<String, BigDecimal[]> byClass = new LinkedHashMap<>();
    Map<String, BigDecimal[]> byPerson = new LinkedHashMap<>();
    h.people().forEach(p -> byPerson.put(p.id(), zero()));
    BigDecimal assets = BigDecimal.ZERO;
    BigDecimal liabilities = BigDecimal.ZERO;
    BigDecimal pledged = BigDecimal.ZERO;
    for (var a : h.assets()) {
      if (!a.countInNetWorth() || !mine(a.personId(), personId)) continue;
      BigDecimal v = nz(a.value());
      assets = assets.add(v);
      if (a.pledged()) pledged = pledged.add(v);
      byClass.computeIfAbsent(a.assetClass(), k -> zero())[0] = byClass.get(a.assetClass())[0].add(v);
      byPerson.computeIfAbsent(a.personId(), k -> zero())[0] = byPerson.get(a.personId())[0].add(v);
    }
    for (var g : h.loansGiven()) {
      if ("repaid".equals(g.status()) || !mine(g.personId(), personId)) continue;
      BigDecimal v = nz(g.amount());
      assets = assets.add(v);
      byClass.computeIfAbsent("lent", k -> zero())[0] = byClass.get("lent")[0].add(v);
      byPerson.computeIfAbsent(g.personId(), k -> zero())[0] = byPerson.get(g.personId())[0].add(v);
    }
    for (var l : h.loans()) {
      if (!mine(l.personId(), personId)) continue;
      BigDecimal v = nz(l.outstanding());
      liabilities = liabilities.add(v);
      byPerson.computeIfAbsent(l.personId(), k -> zero())[1] = byPerson.get(l.personId())[1].add(v);
    }
    return new NetWorth(rupees(assets), rupees(liabilities), rupees(assets.subtract(liabilities)),
        slices(byClass), slices(personId == null ? byPerson : Map.of(personId,
            byPerson.getOrDefault(personId, zero()))), rupees(pledged));
  }

  private static boolean mine(String owner, String personId) {
    return personId == null || personId.equals(owner);
  }

  private static BigDecimal[] zero() { return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO}; }

  private static List<Slice> slices(Map<String, BigDecimal[]> m) {
    return m.entrySet().stream().map(e -> new Slice(e.getKey(), rupees(e.getValue()[0]),
        rupees(e.getValue()[1]), rupees(e.getValue()[0].subtract(e.getValue()[1])))).toList();
  }
}
