package com.jizza.engine.infra.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.finance.FinanceMarket;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link FinanceMarket} on plain SQL; columns are a fixed list, every value a bound parameter. */
@Component
public class JdbcFinanceMarket implements FinanceMarket {

  /** Writable instrument columns and the type each value is cast to. */
  private static final Map<String, String> INSTRUMENT = new LinkedHashMap<>();
  private static final Map<String, String> ACTION = new LinkedHashMap<>();

  static {
    for (String c : List.of("isin", "symbol", "exchange", "name", "type", "asset_class", "sub_category",
        "cap_bucket", "currency", "amfi_code", "tax_class")) INSTRUMENT.put(c, "varchar");
    INSTRUMENT.put("bucket_as_of", "date");
    INSTRUMENT.put("look_through_as_of", "date");
    INSTRUMENT.put("exit_load", "jsonb");
    INSTRUMENT.put("look_through", "jsonb");
    INSTRUMENT.put("added_by", "uuid");
    ACTION.put("instrument_id", "uuid");
    ACTION.put("kind", "varchar");
    ACTION.put("ex_date", "date");
    ACTION.put("ratio_num", "numeric");
    ACTION.put("ratio_den", "numeric");
    ACTION.put("new_instrument_id", "uuid");
    ACTION.put("cash_per_unit", "numeric");
    ACTION.put("source", "varchar");
  }

  private final JdbcTemplate jdbc;

  public JdbcFinanceMarket(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ObjectNode> searchInstruments(String q, int limit) {
    String like = "%" + (q == null ? "" : q.trim().toLowerCase(Locale.ROOT)
        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
    return rows("""
        select row_to_json(t)::text from fin_instrument t
         where lower(t.name) like ? or lower(coalesce(t.symbol, '')) like ? or lower(coalesce(t.isin, '')) like ?
         order by (lower(coalesce(t.symbol, '')) = ?) desc, length(t.name), t.name limit ?""",
        like, like, like, q == null ? "" : q.trim().toLowerCase(Locale.ROOT), Math.max(1, Math.min(limit, 50)));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ObjectNode> instrument(UUID id) {
    return rows("select row_to_json(t)::text from fin_instrument t where t.id = ?", id).stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ObjectNode> instruments(Collection<UUID> ids) {
    if (ids == null || ids.isEmpty()) return List.of();
    return rows("select row_to_json(t)::text from fin_instrument t where t.id = any(?::uuid[])",
        uuidArray(ids));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ObjectNode> instrumentByIsin(String isin) {
    if (isin == null || isin.isBlank()) return Optional.empty();
    return rows("select row_to_json(t)::text from fin_instrument t where t.isin = ?",
        isin.trim().toUpperCase(Locale.ROOT)).stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ObjectNode> instrumentBySymbol(String exchange, String symbol) {
    if (exchange == null || symbol == null) return Optional.empty();
    return rows("select row_to_json(t)::text from fin_instrument t where t.exchange = ? and t.symbol = ?",
        exchange.trim().toUpperCase(Locale.ROOT), symbol.trim().toUpperCase(Locale.ROOT)).stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ObjectNode> instrumentsWithAmfiCode() {
    return rows("select row_to_json(t)::text from fin_instrument t where t.amfi_code is not null order by t.id");
  }

  @Override
  @Transactional(readOnly = true)
  public List<ObjectNode> fundsWithoutAmfiCode() {
    return rows("select row_to_json(t)::text from fin_instrument t where t.amfi_code is null and t.isin like 'INF%' order by t.id");
  }

  @Override
  @Transactional
  public ObjectNode insertInstrument(ObjectNode values) {
    var w = writable(INSTRUMENT, values);
    var names = new StringBuilder();
    var marks = new StringBuilder();
    List<Object> args = new ArrayList<>();
    for (var e : w.entrySet()) {
      if (!names.isEmpty()) { names.append(", "); marks.append(", "); }
      names.append(e.getKey());
      marks.append("cast(? as ").append(INSTRUMENT.get(e.getKey())).append(')');
      args.add(JdbcFinanceRecords.param(e.getValue()));
    }
    return JdbcFinanceRecords.toRow(jdbc.queryForObject("insert into fin_instrument as t (" + names
        + ") values (" + marks + ") returning row_to_json(t)::text", String.class, args.toArray()));
  }

  @Override
  @Transactional
  public Optional<ObjectNode> updateInstrument(UUID id, ObjectNode values) {
    var set = new StringBuilder("updated_at = now()");
    List<Object> args = new ArrayList<>();
    for (var e : writable(INSTRUMENT, values).entrySet()) {
      set.append(", ").append(e.getKey()).append(" = cast(? as ").append(INSTRUMENT.get(e.getKey())).append(')');
      args.add(JdbcFinanceRecords.param(e.getValue()));
    }
    args.add(id);
    return jdbc.queryForList("update fin_instrument as t set " + set + " where t.id = ? returning row_to_json(t)::text",
        String.class, args.toArray()).stream().findFirst().map(JdbcFinanceRecords::toRow);
  }

  @Override
  public boolean usedByOthers(UUID instrumentId, UUID owner) {
    Boolean used = jdbc.queryForObject("""
        select exists (select 1 from fin_holding_lot where instrument_id = ? and user_id <> ?)
            or exists (select 1 from fin_list_item where instrument_id = ? and user_id <> ?)
            or exists (select 1 from fin_node where instrument_id = ? and user_id is distinct from ?)
        """, Boolean.class, instrumentId, owner, instrumentId, owner, instrumentId, owner);
    return Boolean.TRUE.equals(used);
  }

  @Override
  @Transactional
  public int putPrices(List<Price> prices) {
    return put(null, prices);
  }

  @Override
  @Transactional
  public int putOwnPrices(UUID owner, List<Price> prices) {
    if (owner == null) throw new IllegalArgumentException("owner");
    return put(owner, prices);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Price> ownPrice(UUID owner, UUID instrumentId, LocalDate date) {
    return jdbc.query("select price, source from fin_price where instrument_id = ? and user_id = ? and price_date = ?",
        (rs, i) -> new Price(instrumentId, date, rs.getBigDecimal(1), rs.getString(2)),
        instrumentId, owner, Date.valueOf(date)).stream().findFirst();
  }

  @Override
  @Transactional
  public void deleteOwnPrice(UUID owner, UUID instrumentId, LocalDate date) {
    jdbc.update("delete from fin_price where instrument_id = ? and user_id = ? and price_date = ?",
        instrumentId, owner, Date.valueOf(date));
  }

  private int put(UUID owner, List<Price> prices) {
    if (prices == null || prices.isEmpty()) return 0;
    int[][] done = jdbc.batchUpdate("""
        insert into fin_price (instrument_id, user_id, price_date, price, source) values (?, ?, ?, ?, ?)
        on conflict (instrument_id, price_date, coalesce(user_id, '00000000-0000-0000-0000-000000000000'::uuid))
        do update set price = excluded.price, source = excluded.source""",
        prices, 500, (ps, p) -> {
          ps.setObject(1, p.instrumentId());
          ps.setObject(2, owner);
          ps.setDate(3, Date.valueOf(p.date()));
          ps.setBigDecimal(4, p.price());
          ps.setString(5, p.source());
        });
    int n = 0;
    for (int[] batch : done) n += batch.length;
    return n;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, Price> latestPrices(Collection<UUID> instrumentIds, LocalDate asOf) {
    return latestPrices(instrumentIds, asOf, null);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, Price> latestPrices(Collection<UUID> instrumentIds, LocalDate asOf, UUID owner) {
    Map<UUID, Price> out = new HashMap<>();
    if (instrumentIds == null || instrumentIds.isEmpty()) return out;
    jdbc.query("""
        select distinct on (instrument_id) instrument_id, price_date, price, source from fin_price
         where instrument_id = any(?::uuid[]) and price_date <= ?
           and (user_id is null or user_id = ?)
         order by instrument_id, price_date desc, (user_id is not null)""", rs -> {
          UUID id = rs.getObject(1, UUID.class);
          out.put(id, new Price(id, rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getString(4)));
        }, uuidArray(instrumentIds), Date.valueOf(asOf), owner);
    return out;
  }

  @Override
  @Transactional
  public void putFxRate(String pair, LocalDate date, BigDecimal rate, String source) {
    jdbc.update("""
        insert into fin_fx_rate (pair, rate_date, rate, source) values (?, ?, ?, ?)
        on conflict (pair, rate_date) do update set rate = excluded.rate, source = excluded.source""",
        pair, Date.valueOf(date), rate, source);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BigDecimal> fxRate(String pair, LocalDate asOf) {
    return jdbc.queryForList("select rate from fin_fx_rate where pair = ? and rate_date <= ? order by rate_date desc limit 1",
        BigDecimal.class, pair, Date.valueOf(asOf)).stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ObjectNode> corporateActions(UUID userId, UUID instrumentId) {
    return rows("""
        select row_to_json(t)::text from fin_corporate_action t
         where t.instrument_id = ? and (t.user_id is null or t.user_id = ?) order by t.ex_date, t.created_at""",
        instrumentId, userId);
  }

  @Override
  @Transactional
  public ObjectNode insertCorporateAction(UUID userId, ObjectNode values) {
    var names = new StringBuilder("user_id");
    var marks = new StringBuilder("?");
    List<Object> args = new ArrayList<>();
    args.add(userId);
    for (var e : writable(ACTION, values).entrySet()) {
      names.append(", ").append(e.getKey());
      marks.append(", cast(? as ").append(ACTION.get(e.getKey())).append(')');
      args.add(JdbcFinanceRecords.param(e.getValue()));
    }
    return JdbcFinanceRecords.toRow(jdbc.queryForObject("insert into fin_corporate_action as t (" + names
        + ") values (" + marks + ") returning row_to_json(t)::text", String.class, args.toArray()));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private List<ObjectNode> rows(String sql, Object... args) {
    return jdbc.queryForList(sql, String.class, args).stream().map(JdbcFinanceRecords::toRow).toList();
  }

  private static String uuidArray(Collection<UUID> ids) {
    var sb = new StringBuilder("{");
    for (UUID id : Set.copyOf(ids)) {
      if (sb.length() > 1) sb.append(',');
      sb.append(id);
    }
    return sb.append('}').toString();
  }

  private static Map<String, JsonNode> writable(Map<String, String> allowed, ObjectNode values) {
    Map<String, JsonNode> out = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = values.fields();
    while (it.hasNext()) {
      var e = it.next();
      String col = JdbcFinanceRecords.snake(e.getKey());
      if (!allowed.containsKey(col)) throw new IllegalArgumentException("Field " + e.getKey() + " cannot be written");
      out.put(col, e.getValue());
    }
    return out;
  }
}
