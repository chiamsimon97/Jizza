package com.jizza.platform.usecase.finance.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kai's defaults exactly as the migrations seed them, read from the SQL files, so the fixture tests
 * run on the shipped configuration and not on copies of it.
 */
public final class KaiDefaults {

  private KaiDefaults() {}

  private static final Path MIGRATIONS =
      Path.of("../../jizza-engine/engine-persistence/src/main/resources/db/migration");

  /** The finance migrations in order; a later seed of a key replaces an earlier one. */
  static final java.util.List<String> FILES = java.util.List.of("V50__finance_foundation.sql",
      "V51__finance_accounts_cashflow.sql", "V54__finance_goals.sql");

  public static Map<String, JsonNode> values() {
    return values(KaiConfig.KEYS);
  }

  public static Map<String, JsonNode> values(java.util.Collection<String> keys) {
    try {
      StringBuilder sql = new StringBuilder();
      for (String f : FILES) sql.append(Files.readString(MIGRATIONS.resolve(f)));
      Map<String, JsonNode> out = new HashMap<>();
      ObjectMapper mapper = new ObjectMapper();
      for (String key : keys) {
        var m = Pattern.compile("\\('" + Pattern.quote(key) + "',\\s*'system',\\s*'(.*?)'::jsonb",
            Pattern.DOTALL).matcher(sql);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last == null) throw new IllegalStateException("No seed for " + key);
        out.put(key, mapper.readTree(last));
      }
      return out;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static KaiConfig config() {
    return KaiConfig.from(values());
  }

  public static PlanConfig plan() {
    return PlanConfig.from(values(PlanConfig.KEYS));
  }
}
