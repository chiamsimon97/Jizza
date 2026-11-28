package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.RequiresPermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every route that declares a {@link RequiresPermission} is called by a member of each role in an
 * Organization account. A role without the permission is refused with {@code permission-denied};
 * a role with it is never refused for that reason. The routes, their permissions and each role's
 * grants are all read from the running application, so the matrix follows any change to them.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class RolePermissionMatrixE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final Pattern VAR = Pattern.compile("\\{[^}]+}");

  @LocalServerPort private int port;
  @Autowired @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient owner;
  private UUID org;
  private final Map<String, ApiClient> members = new LinkedHashMap<>();
  private final Map<String, Set<String>> grants = new HashMap<>();

  @BeforeEach
  void setUp() {
    model.reset();
    owner = signUp("matrix-owner-" + UUID.randomUUID() + "@jizza.local",
        Map.of("accountType", "ORGANIZATION", "organizationName", "Matrix Co"));
    org = UUID.fromString(owner.get("/api/me/context").json()
        .path("activeWorkspace").path("id").asText());
    for (String role : List.of("ADMIN", "DEVELOPER", "USER")) {
      String email = "matrix-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@jizza.local";
      ApiClient member = signUp(email, Map.of());
      assertThat(owner.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
      var invited = owner.post("/api/workspaces/current/invitations",
          Map.of("email", email, "role", role));
      assertThat(invited.status()).as(invited.body()).isEqualTo(202);
      String invitation = member.get("/api/me/invitations").json().get(0).path("id").asText();
      assertThat(member.post("/api/me/invitations/" + invitation + "/accept", null).ok()).isTrue();
      var switched = member.post("/api/me/active-workspace", Map.of("workspaceId", org.toString()));
      assertThat(switched.ok()).as(switched.body()).isTrue();
      Set<String> granted = new HashSet<>();
      switched.json().path("permissions").forEach(p -> granted.add(p.asText()));
      members.put(role, member);
      grants.put(role, granted);
    }
  }

  private ApiClient signUp(String email, Map<String, Object> extra) {
    ApiClient client = new ApiClient(port);
    client.prime();
    Map<String, Object> body = new HashMap<>(extra);
    body.put("email", email);
    body.put("password", PASSWORD);
    assertThat(client.post("/api/auth/register", body).ok()).isTrue();
    assertThat(client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
    return client;
  }

  private static Set<String> required(HandlerMethod handler) {
    Set<String> out = new HashSet<>();
    AnnotatedElementUtils.findMergedRepeatableAnnotations(handler.getMethod(),
        RequiresPermission.class).forEach(a -> out.add(a.value()));
    AnnotatedElementUtils.findMergedRepeatableAnnotations(handler.getBeanType(),
        RequiresPermission.class).forEach(a -> out.add(a.value()));
    return out;
  }

  @Test
  void eachRoleGetsExactlyTheRoutesItsPermissionsAllow() {
    // The roles really differ, or the matrix proves nothing.
    assertThat(grants.get("ADMIN")).isNotEqualTo(grants.get("USER"));
    sweep(members, grants);
  }

  /**
   * The same sweep for a Family (Vita spec 4.4): its owner assigns Admin and User but never
   * Developer, and its roles hold only the members permissions, so no agent, connector, device,
   * guardrail or listing route opens for it.
   */
  @Test
  void aFamilyHasAdminAndUserMembersAndOnlyTheMembersRoutes() {
    ApiClient familyOwner = signUp("matrix-family-" + UUID.randomUUID() + "@jizza.local", Map.of());
    assertThat(familyOwner.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var created = familyOwner.post("/api/workspaces/families", Map.of("name", "Matrix Family"));
    assertThat(created.ok()).as(created.body()).isTrue();
    UUID family = UUID.fromString(created.json().path("id").asText());
    assertThat(familyOwner.post("/api/me/active-workspace", Map.of("workspaceId", family.toString())).ok()).isTrue();

    String developer = "matrix-family-dev-" + UUID.randomUUID() + "@jizza.local";
    signUp(developer, Map.of());
    assertThat(familyOwner.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var refused = familyOwner.post("/api/workspaces/current/invitations", Map.of("email", developer, "role", "DEVELOPER"));
    assertThat(refused.status()).as(refused.body()).isBetween(400, 403);

    Map<String, ApiClient> familyMembers = new LinkedHashMap<>();
    Map<String, Set<String>> familyGrants = new HashMap<>();
    for (String role : List.of("ADMIN", "USER")) {
      String email = "matrix-family-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@jizza.local";
      ApiClient member = signUp(email, Map.of());
      assertThat(familyOwner.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
      var invited = familyOwner.post("/api/workspaces/current/invitations", Map.of("email", email, "role", role));
      assertThat(invited.status()).as(invited.body()).isEqualTo(202);
      String invitation = member.get("/api/me/invitations").json().get(0).path("id").asText();
      assertThat(member.post("/api/me/invitations/" + invitation + "/accept", null).ok()).isTrue();
      var switched = member.post("/api/me/active-workspace", Map.of("workspaceId", family.toString()));
      assertThat(switched.ok()).as(switched.body()).isTrue();
      Set<String> granted = new HashSet<>();
      switched.json().path("permissions").forEach(p -> granted.add(p.asText()));
      assertThat(granted).allMatch(p -> p.startsWith("members_"));
      familyMembers.put(role, member);
      familyGrants.put(role, granted);
    }
    assertThat(familyGrants.get("ADMIN")).contains("members_invite");
    sweep(familyMembers, familyGrants);
  }

  private void sweep(Map<String, ApiClient> members, Map<String, Set<String>> grants) {
    List<String> wronglyRefused = new ArrayList<>();
    List<String> wronglyAllowed = new ArrayList<>();
    int checked = 0;
    for (var entry : mappings.getHandlerMethods().entrySet()) {
      Set<String> needs = required(entry.getValue());
      if (needs.isEmpty()) continue;
      for (String pattern : entry.getKey().getPatternValues()) {
        if (pattern.contains("/membership") || pattern.endsWith("/logout")) continue;
        String path = VAR.matcher(pattern).replaceAll(r -> UUID.randomUUID().toString());
        var methods = entry.getKey().getMethodsCondition().getMethods();
        for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
          for (var member : members.entrySet()) {
            String role = member.getKey();
            boolean allowed = grants.get(role).containsAll(needs);
            var result = member.getValue().send(method.name(), path,
                method == RequestMethod.GET ? null : Map.of());
            // 415: the route only takes a file upload, and refused the request before any handler,
            // permission check included, could run.
            boolean denied = result.status() == 403
                && result.problemType().endsWith("permission-denied");
            boolean neverRan = result.status() == 415;
            checked++;
            if (allowed && denied) wronglyRefused.add(role + " " + method + " " + pattern + " " + needs);
            if (!allowed && !denied && !neverRan) wronglyAllowed.add(role + " " + method + " " + pattern + " "
                + needs + " -> " + result.status());
          }
        }
      }
    }
    assertThat(checked).as("route x role checks").isGreaterThan(50);
    assertThat(wronglyAllowed).as("roles let through without the permission").isEmpty();
    assertThat(wronglyRefused).as("roles refused despite holding the permission").isEmpty();
  }

  @Test
  void aRemovedMemberLosesAccessOnTheirNextCall() {
    ApiClient admin = members.get("ADMIN");
    assertThat(admin.get("/api/workspaces/current/members").ok()).isTrue();

    String adminId = null;
    for (var m : owner.get("/api/workspaces/current/members").json()) {
      if ("ADMIN".equals(m.path("role").asText())) adminId = m.path("id").asText();
    }
    assertThat(owner.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    assertThat(owner.delete("/api/workspaces/current/members/" + adminId).ok()).isTrue();

    // Their session no longer reaches the organization.
    var after = admin.get("/api/me/context");
    if (after.ok()) {
      assertThat(after.json().path("activeWorkspace").path("id").asText())
          .isNotEqualTo(org.toString());
    } else {
      assertThat(after.status()).isEqualTo(401);
    }
    assertThat(admin.get("/api/workspaces/current/members").ok()).isFalse();
  }
}
