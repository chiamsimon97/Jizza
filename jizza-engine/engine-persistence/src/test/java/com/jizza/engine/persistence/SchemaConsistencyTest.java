package com.jizza.engine.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks the JPA entities against the Flyway migrations without needing a database, so a
 * mapping mistake fails the module build even where no Docker daemon is available to run
 * {@code ddl-auto=validate}.
 */
class SchemaConsistencyTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final Path ENTITY_DIR = Path.of("src/main/java/com/jizza/engine/persistence/entity");

  @Test
  void everyEntityColumnExistsInTheMigrations() throws Exception {
    Map<String, Set<String>> schema = parseSchema();
    List<String> problems = new ArrayList<>();

    for (Class<?> entity : entityClasses()) {
      String table = entity.getAnnotation(Table.class).name();
      Set<String> columns = schema.get(table);
      if (columns == null) {
        problems.add(entity.getSimpleName() + " maps to unknown table '" + table + "'");
        continue;
      }
      for (Field f : entity.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || f.isAnnotationPresent(Transient.class)) continue;
        // An element collection's column lives in its own collection table, not the entity's.
        if (f.isAnnotationPresent(jakarta.persistence.ElementCollection.class)) continue;
        Column col = f.getAnnotation(Column.class);
        String name = col != null && !col.name().isBlank() ? col.name() : camelToSnake(f.getName());
        if (!columns.contains(name)) {
          problems.add(entity.getSimpleName() + "." + f.getName() + " -> " + table + "." + name
              + " does not exist");
        }
      }
    }
    assertThat(problems).as("entity/schema mismatches").isEmpty();
  }

  @Test
  void everyContractTableHasAnEntity() throws Exception {
    Set<String> mapped = new HashSet<>();
    for (Class<?> e : entityClasses()) mapped.add(e.getAnnotation(Table.class).name());
    assertThat(mapped).containsAll(com.jizza.engine.persistence.support.SchemaInfo.CONTRACT_TABLES);
  }

  private List<Class<?>> entityClasses() throws Exception {
    List<Class<?>> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(ENTITY_DIR)) {
      for (Path p : files.toList()) {
        String simple = p.getFileName().toString().replace(".java", "");
        Class<?> c = Class.forName("com.jizza.engine.persistence.entity." + simple);
        if (c.isAnnotationPresent(Entity.class)) out.add(c);
      }
    }
    return out;
  }

  private static String camelToSnake(String name) {
    return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  /** Reads {@code create table} and {@code alter table … add column} statements out of the SQL. */
  private Map<String, Set<String>> parseSchema() throws IOException {
    String sql;
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      StringBuilder sb = new StringBuilder();
      for (Path p : files.sorted().toList()) {
        if (p.toString().endsWith(".sql")) sb.append(Files.readString(p)).append('\n');
      }
      sql = stripComments(sb.toString());
    }

    Map<String, Set<String>> schema = new HashMap<>();
    Matcher create = Pattern.compile("create\\s+table\\s+(\\w+)\\s*\\((.*?)\\n\\);",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(sql);
    while (create.find()) {
      schema.put(create.group(1).toLowerCase(Locale.ROOT), columnsOf(create.group(2)));
    }
    Matcher alter = Pattern.compile(
        "alter\\s+table\\s+(\\w+)\\s+add\\s+column\\s+(?:if\\s+not\\s+exists\\s+)?(\\w+)",
        Pattern.CASE_INSENSITIVE).matcher(sql);
    while (alter.find()) {
      schema.computeIfAbsent(alter.group(1).toLowerCase(Locale.ROOT), k -> new HashSet<>())
          .add(alter.group(2).toLowerCase(Locale.ROOT));
    }
    return schema;
  }

  private static String stripComments(String sql) {
    return sql.replaceAll("(?m)--.*$", "");
  }

  /** Splits a create-table body on top-level commas and keeps the leading identifier of each. */
  private static Set<String> columnsOf(String body) {
    Set<String> cols = new HashSet<>();
    int depth = 0;
    StringBuilder current = new StringBuilder();
    List<String> parts = new ArrayList<>();
    for (char c : body.toCharArray()) {
      if (c == '(') depth++;
      if (c == ')') depth--;
      if (c == ',' && depth == 0) { parts.add(current.toString()); current.setLength(0); }
      else current.append(c);
    }
    parts.add(current.toString());
    Set<String> keywords = Set.of("unique", "primary", "foreign", "check", "constraint");
    for (String part : parts) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) continue;
      String first = trimmed.split("\\s+")[0].toLowerCase(Locale.ROOT);
      if (!keywords.contains(first)) cols.add(first);
    }
    return cols;
  }
}
