package com.jizza.engine.infra.finance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.finance.FinanceRecords;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link FinanceRecords} on plain SQL. Table names come from a fixed list and column names are
 * checked against the live schema, so no caller text ever reaches the SQL itself; every value is a
 * bound parameter cast to its column's type.
 */
@Component
public class JdbcFinanceRecords implements FinanceRecords {

  /** The personal finance tables this store serves. Phase 0's tables have their own store. */
  public static final List<String> TABLES_IN_ORDER = List.of(
      "fin_account", "fin_asset", "fin_net_worth_snapshot", "fin_category", "fin_income_source",
      "fin_expense_budget", "fin_import_batch", "fin_transaction", "fin_category_rule",
      "fin_liability", "fin_pledge", "fin_loan_payment", "fin_loan_given", "fin_insurance_policy",
      "fin_estate_record", "fin_health_check_run", "fin_action_item", "fin_life_event",
      "fin_review_run",
      // Phase 1b
      "fin_risk_assessment", "fin_tax_profile", "fin_plan", "fin_investment_budget",
      "fin_allocation_line", "fin_projection_run");
  static final Set<String> TABLES = Set.copyOf(TABLES_IN_ORDER);

  private static final Set<String> NOT_WRITABLE = Set.of("id", "user_id", "created_at", "updated_at");

  private static final ObjectMapper READER = new ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

  private final JdbcTemplate jdbc;
  /** table → (column → SQL type to cast to). */
  private final Map<String, Map<String, String>> columns = new ConcurrentHashMap<>();

  public JdbcFinanceRecords(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ObjectNode> list(String table, UUID userId, Map<String, Object> where) {
    var cols = columns(table);
    var sql = new StringBuilder("select row_to_json(t)::text from " + table + " t where t.user_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(userId);
    appendWhere(cols, where, sql, args);
    sql.append(" order by t.created_at, t.id");
    return jdbc.queryForList(sql.toString(), String.class, args.toArray()).stream()
        .map(JdbcFinanceRecords::toRow).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ObjectNode> get(String table, UUID userId, UUID id) {
    columns(table);
    return jdbc.queryForList("select row_to_json(t)::text from " + table
            + " t where t.id = ? and t.user_id = ?", String.class, id, userId)
        .stream().findFirst().map(JdbcFinanceRecords::toRow);
  }

  @Override
  @Transactional
  public ObjectNode insert(String table, UUID userId, ObjectNode values) {
    var cols = columns(table);
    var names = new StringBuilder("user_id");
    var marks = new StringBuilder("?");
    List<Object> args = new ArrayList<>();
    args.add(userId);
    for (var e : writable(cols, values).entrySet()) {
      names.append(", ").append(e.getKey());
      marks.append(", cast(? as ").append(cols.get(e.getKey())).append(')');
      args.add(param(e.getValue()));
    }
    String json = jdbc.queryForObject("insert into " + table + " as t (" + names + ") values ("
        + marks + ") returning row_to_json(t)::text", String.class, args.toArray());
    return toRow(json);
  }

  @Override
  @Transactional
  public Optional<ObjectNode> update(String table, UUID userId, UUID id, ObjectNode values) {
    var cols = columns(table);
    var set = new StringBuilder("updated_at = now()");
    List<Object> args = new ArrayList<>();
    for (var e : writable(cols, values).entrySet()) {
      set.append(", ").append(e.getKey()).append(" = cast(? as ").append(cols.get(e.getKey())).append(')');
      args.add(param(e.getValue()));
    }
    args.add(id);
    args.add(userId);
    return jdbc.queryForList("update " + table + " as t set " + set
            + " where t.id = ? and t.user_id = ? returning row_to_json(t)::text", String.class,
            args.toArray())
        .stream().findFirst().map(JdbcFinanceRecords::toRow);
  }

  @Override
  @Transactional
  public boolean delete(String table, UUID userId, UUID id) {
    columns(table);
    return jdbc.update("delete from " + table + " where id = ? and user_id = ?", id, userId) > 0;
  }

  @Override
  @Transactional
  public int deleteWhere(String table, UUID userId, Map<String, Object> where) {
    var cols = columns(table);
    var sql = new StringBuilder("delete from " + table + " t where t.user_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(userId);
    appendWhere(cols, where, sql, args);
    return jdbc.update(sql.toString(), args.toArray());
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> ownersWithProfile() {
    return jdbc.queryForList("select user_id from fin_profile order by user_id", UUID.class);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Map<String, String> columns(String table) {
    if (!TABLES.contains(table)) throw new IllegalArgumentException("Not a finance table: " + table);
    return columns.computeIfAbsent(table, t -> {
      Map<String, String> out = new LinkedHashMap<>();
      jdbc.query("""
          select column_name, udt_name from information_schema.columns
           where table_schema = current_schema() and table_name = ? order by ordinal_position
          """, rs -> {
            String udt = rs.getString(2);
            out.put(rs.getString(1), udt.startsWith("_") ? udt.substring(1) + "[]" : udt);
          }, t);
      if (out.isEmpty()) throw new IllegalStateException("Table " + t + " is missing");
      return out;
    });
  }

  private static void appendWhere(Map<String, String> cols, Map<String, Object> where,
                                  StringBuilder sql, List<Object> args) {
    if (where == null) return;
    for (var e : where.entrySet()) {
      String col = snake(e.getKey());
      if (!cols.containsKey(col)) throw new IllegalArgumentException("Unknown field " + e.getKey());
      if (e.getValue() == null) {
        sql.append(" and t.").append(col).append(" is null");
      } else {
        sql.append(" and t.").append(col).append(" = cast(? as ").append(cols.get(col)).append(')');
        args.add(String.valueOf(e.getValue()));
      }
    }
  }

  private static Map<String, JsonNode> writable(Map<String, String> cols, ObjectNode values) {
    Map<String, JsonNode> out = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = values.fields();
    while (it.hasNext()) {
      var e = it.next();
      String col = snake(e.getKey());
      if (!cols.containsKey(col) || NOT_WRITABLE.contains(col) || "bytea".equals(cols.get(col))) {
        throw new IllegalArgumentException("Field " + e.getKey() + " cannot be written");
      }
      out.put(col, e.getValue());
    }
    return out;
  }

  /** A JSON value as the text Postgres casts to the column type. */
  static String param(JsonNode v) {
    if (v == null || v.isNull() || v.isMissingNode()) return null;
    if (v.isTextual()) return v.asText();
    if (v.isBigDecimal()) return v.decimalValue().toPlainString();
    if (v.isNumber() || v.isBoolean()) return v.asText();
    if (v.isArray() && allScalar(v)) {
      var sb = new StringBuilder("{");
      for (int i = 0; i < v.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append('"').append(v.get(i).asText().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
      }
      return sb.append('}').toString();
    }
    return v.toString();
  }

  private static boolean allScalar(JsonNode array) {
    for (JsonNode n : array) if (n.isContainerNode()) return false;
    return true;
  }

  static ObjectNode toRow(String json) {
    try {
      JsonNode raw = READER.readTree(json);
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      raw.fields().forEachRemaining(e -> out.set(camel(e.getKey()), tidy(e.getValue())));
      return out;
    } catch (Exception e) {
      throw new IllegalStateException("Unreadable finance row", e);
    }
  }

  /** 250000.00 reads as 250000 and 14.000000 as 14; nested JSON is left as stored. */
  private static JsonNode tidy(JsonNode v) {
    if (v.isBigDecimal()) {
      BigDecimal d = v.decimalValue().stripTrailingZeros();
      if (d.scale() < 0) d = d.setScale(0);
      return JsonNodeFactory.instance.numberNode(d);
    }
    return v;
  }

  static String snake(String camel) {
    var sb = new StringBuilder();
    for (char c : camel.toCharArray()) {
      if (Character.isUpperCase(c)) sb.append('_').append(Character.toLowerCase(c));
      else sb.append(c);
    }
    return sb.toString();
  }

  static String camel(String snake) {
    var sb = new StringBuilder();
    boolean up = false;
    for (char c : snake.toCharArray()) {
      if (c == '_') { up = true; continue; }
      sb.append(up ? Character.toUpperCase(c) : c);
      up = false;
    }
    return sb.toString();
  }
}
