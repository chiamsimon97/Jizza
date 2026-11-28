package com.jizza.engine.security.authz;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jizza.engine.api.authz.PermissionCatalog;
import com.jizza.engine.api.authz.PermissionDefinition;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.util.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Who may do what, read once at startup from {@code security/permissions.json}.
 *
 * <p>The definitions live in a file rather than a table on purpose: changing who can do what is a
 * reviewed code change and a restart, never a data edit. Anything wrong with the file stops the
 * app from starting, with a message naming the entry, so a typo can never quietly widen or narrow
 * access.
 */
@Component
public class JsonPermissionCatalog implements PermissionCatalog {

  public static final String LOCATION = "security/permissions.json";

  private static final Logger log = LoggerFactory.getLogger(JsonPermissionCatalog.class);
  private static final Pattern NAME = Pattern.compile("^[a-z]+(_[a-z]+)+$");
  private static final Set<String> CATEGORIES = Set.of("read", "read_and_write");

  /** One entry as written in the file. Unknown fields are rejected. */
  record RawEntry(String name, String label, String description, String category, String module,
                  List<String> dependencies, List<String> permittedAccountTypes,
                  List<String> permittedRoles) {}

  private final Map<String, PermissionDefinition> byName;
  private final Map<WorkspaceRole, Map<WorkspaceType, Set<String>>> grants;
  private final String version;

  public JsonPermissionCatalog() {
    this(readClasspath());
  }

  JsonPermissionCatalog(byte[] file) {
    this.version = Hashing.sha256Hex(new String(file, StandardCharsets.UTF_8));
    this.byName = parse(file);
    this.grants = index(byName.values());
    log.info("Loaded {} permissions from {} (version {})", byName.size(), LOCATION,
        version.substring(0, 12));
  }

  public static JsonPermissionCatalog fromBytes(byte[] file) {
    return new JsonPermissionCatalog(file);
  }

  @Override
  public Optional<PermissionDefinition> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  @Override
  public List<PermissionDefinition> all() {
    return List.copyOf(byName.values());
  }

  @Override
  public Set<String> granted(WorkspaceRole role, WorkspaceType type) {
    if (role == null || type == null) return Set.of();
    return grants.getOrDefault(role, Map.of()).getOrDefault(type, Set.of());
  }

  @Override
  public String version() {
    return version;
  }

  // ── Loading and validation ────────────────────────────────────────────────

  private static byte[] readClasspath() {
    try (InputStream in = new ClassPathResource(LOCATION).getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + LOCATION + " from the classpath", e);
    }
  }

  static Map<String, PermissionDefinition> parse(byte[] file) {
    ObjectMapper mapper = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();
    RawEntry[] raw;
    try {
      raw = mapper.readValue(file, RawEntry[].class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw invalid("the file is not a valid JSON array of permissions: " + e.getOriginalMessage());
    } catch (IOException e) {
      throw invalid("it could not be read: " + e.getMessage());
    }
    if (raw == null || raw.length == 0) throw invalid("it defines no permissions");

    Map<String, PermissionDefinition> out = new LinkedHashMap<>();
    for (RawEntry e : raw) {
      PermissionDefinition def = toDefinition(e);
      if (out.putIfAbsent(def.name(), def) != null) {
        throw invalid("'" + def.name() + "' is defined more than once");
      }
    }
    validateDependencies(out);
    for (String required : Permissions.ALL) {
      if (!out.containsKey(required)) {
        throw invalid("'" + required + "' is used by the code (Permissions." + required
            .toUpperCase(Locale.ROOT) + ") but is not defined");
      }
    }
    return Collections.unmodifiableMap(out);
  }

  private static PermissionDefinition toDefinition(RawEntry e) {
    if (e == null) throw invalid("an entry is null");
    String name = e.name();
    if (name == null || !NAME.matcher(name).matches()) {
      throw invalid("'" + name + "' is not a valid permission name (lower_snake_case, e.g. flow_view)");
    }
    require(name, "label", e.label());
    require(name, "description", e.description());
    require(name, "module", e.module());
    if (e.category() == null || !CATEGORIES.contains(e.category())) {
      throw invalid("'" + name + "' has category '" + e.category() + "'; use one of " + CATEGORIES);
    }
    if (e.dependencies() == null) throw invalid("'" + name + "' is missing 'dependencies'");
    Set<WorkspaceType> types = EnumSet.noneOf(WorkspaceType.class);
    for (String t : listOrFail(name, "permittedAccountTypes", e.permittedAccountTypes())) {
      types.add(switch (t) {
        case "personal" -> WorkspaceType.PERSONAL;
        case "organization" -> WorkspaceType.ORGANIZATION;
        case "family" -> WorkspaceType.FAMILY;
        default -> throw invalid("'" + name + "' lists unknown account type '" + t
            + "'; use personal, organization or family");
      });
    }
    Set<WorkspaceRole> roles = EnumSet.noneOf(WorkspaceRole.class);
    for (String r : listOrFail(name, "permittedRoles", e.permittedRoles())) {
      roles.add(switch (r) {
        case "owner" -> WorkspaceRole.OWNER;
        case "admin" -> WorkspaceRole.ADMIN;
        case "developer" -> WorkspaceRole.DEVELOPER;
        case "user" -> WorkspaceRole.USER;
        default -> throw invalid("'" + name + "' lists unknown role '" + r + "'; use owner, admin, "
            + "developer or user (super admin access is built in and never listed)");
      });
    }
    return new PermissionDefinition(name, e.label(), e.description(), e.category(), e.module(),
        List.copyOf(e.dependencies()), Collections.unmodifiableSet(types),
        Collections.unmodifiableSet(roles));
  }

  /**
   * Every dependency exists, there are no cycles, a read permission never needs a write one, and
   * wherever a permission is granted its dependencies are granted too.
   */
  private static void validateDependencies(Map<String, PermissionDefinition> defs) {
    for (PermissionDefinition def : defs.values()) {
      for (String dep : def.dependencies()) {
        PermissionDefinition target = defs.get(dep);
        if (target == null) throw invalid("'" + def.name() + "' depends on unknown '" + dep + "'");
        if (dep.equals(def.name())) throw invalid("'" + def.name() + "' depends on itself");
        if (def.category().equals("read") && target.category().equals("read_and_write")) {
          throw invalid("'" + def.name() + "' is a read permission but depends on the write "
              + "permission '" + dep + "'");
        }
        for (WorkspaceRole role : def.permittedRoles()) {
          for (WorkspaceType type : def.permittedAccountTypes()) {
            if (!target.grants(role, type)) {
              throw invalid("'" + def.name() + "' is granted to " + lower(role) + " in "
                  + lower(type) + " accounts, but its dependency '" + dep + "' is not");
            }
          }
        }
      }
    }
    Map<String, Integer> state = new HashMap<>();
    for (String name : defs.keySet()) visit(name, defs, state, new ArrayList<>());
  }

  private static void visit(String name, Map<String, PermissionDefinition> defs,
                            Map<String, Integer> state, List<String> path) {
    Integer s = state.get(name);
    if (s != null && s == 2) return;
    path.add(name);
    if (s != null && s == 1) throw invalid("dependency cycle: " + String.join(" -> ", path));
    state.put(name, 1);
    for (String dep : defs.get(name).dependencies()) visit(dep, defs, state, path);
    state.put(name, 2);
    path.remove(path.size() - 1);
  }

  private static Map<WorkspaceRole, Map<WorkspaceType, Set<String>>> index(
      java.util.Collection<PermissionDefinition> defs) {
    Map<WorkspaceRole, Map<WorkspaceType, Set<String>>> out = new EnumMap<>(WorkspaceRole.class);
    for (WorkspaceRole role : WorkspaceRole.values()) {
      Map<WorkspaceType, Set<String>> byType = new EnumMap<>(WorkspaceType.class);
      for (WorkspaceType type : WorkspaceType.values()) {
        Set<String> names = new HashSet<>();
        for (PermissionDefinition def : defs) if (def.grants(role, type)) names.add(def.name());
        byType.put(type, Set.copyOf(names));
      }
      out.put(role, Collections.unmodifiableMap(byType));
    }
    return Collections.unmodifiableMap(out);
  }

  private static List<String> listOrFail(String name, String field, List<String> values) {
    if (values == null || values.isEmpty()) {
      throw invalid("'" + name + "' needs at least one value in '" + field + "'");
    }
    return values;
  }

  private static void require(String name, String field, String value) {
    if (value == null || value.isBlank()) throw invalid("'" + name + "' is missing '" + field + "'");
  }

  private static String lower(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT);
  }

  private static IllegalStateException invalid(String why) {
    return new IllegalStateException(LOCATION + " is invalid: " + why);
  }
}
