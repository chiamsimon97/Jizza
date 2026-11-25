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

  public static Map<String, JsonNode> values() {
    try {
      String sql = Files.readString(MIGRATIONS.resolve("V50__finance_foundation.sql"))
          + Files.readString(MIGRATIONS.resolve("V51__finance_accounts_cashflow.sql"));
      Map<String, JsonNode> out = new HashMap<>();
      ObjectMapper mapper = new ObjectMapper();
      for (String key : KaiConfig.KEYS) {
        var m = Pattern.compile("\\('" + Pattern.quote(key) + "',\\s*'system',\\s*'(.*?)'::jsonb",
            Pattern.DOTALL).matcher(sql);
        if (!m.find()) throw new IllegalStateException("No seed for " + key);
        out.put(key, mapper.readTree(m.group(1)));
      }
      return out;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static KaiConfig config() {
    return KaiConfig.from(values());
  }
}
