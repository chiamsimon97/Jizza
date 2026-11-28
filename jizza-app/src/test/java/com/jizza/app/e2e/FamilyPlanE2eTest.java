package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The Family plan (Vita spec 4.4): created from inside an Individual account, once per owner; its
 * owner assigns Admin and User but never Developer; a member can leave and the owner cannot;
 * sign-up as a Family is a validation error, never a 500; a Family is never listed publicly; and
 * no agent or connector route opens for it.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FamilyPlanE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;

  private ApiClient signUp(String email) {
    ApiClient c = new ApiClient(port);
    c.prime();
    assertThat(c.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(c.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    return c;
  }

  private static String email(String who) {
    return "family-" + who + "-" + UUID.randomUUID() + "@jizza.local";
  }

  private void stepUp(ApiClient c) {
    assertThat(c.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
  }

  private UUID createFamily(ApiClient owner) {
    stepUp(owner);
    var created = owner.post("/api/workspaces/families", Map.of("name", "Our Family"));
    assertThat(created.ok()).as(created.body()).isTrue();
    assertThat(created.json().path("type").asText()).isEqualTo("FAMILY");
    return UUID.fromString(created.json().path("id").asText());
  }

  private void enter(ApiClient c, UUID workspace) {
    var r = c.post("/api/me/active-workspace", Map.of("workspaceId", workspace.toString()));
    assertThat(r.ok()).as(r.body()).isTrue();
  }

  @Test
  void aFamilyIsCreatedOncePerOwnerAndNeverAtSignUp() {
    ApiClient owner = signUp(email("owner"));
    createFamily(owner);
    stepUp(owner);
    assertThat(owner.post("/api/workspaces/families", Map.of("name", "Second Family")).status()).isEqualTo(409);
    assertThat(owner.post("/api/workspaces/families", Map.of("name", "X")).status()).isEqualTo(400);

    ApiClient fresh = new ApiClient(port);
    fresh.prime();
    Map<String, Object> body = new HashMap<>(Map.of("email", email("signup"), "password", PASSWORD,
        "accountType", "FAMILY", "organizationName", "Our Family"));
    var refused = fresh.post("/api/auth/register", body);
    assertThat(refused.status()).as(refused.body()).isEqualTo(400);
  }

  @Test
  void theOwnerAssignsAdminAndUserButNotDeveloperAndOnlyMembersRoutesOpen() {
    ApiClient owner = signUp(email("owner"));
    UUID family = createFamily(owner);
    enter(owner, family);
    String dev = email("dev");
    String admin = email("admin");
    ApiClient adminClient = signUp(admin);
    signUp(dev);
    stepUp(owner);
    var developer = owner.post("/api/workspaces/current/invitations", Map.of("email", dev, "role", "DEVELOPER"));
    assertThat(developer.status()).as(developer.body()).isBetween(400, 403);
    stepUp(owner);
    assertThat(owner.post("/api/workspaces/current/invitations", Map.of("email", admin, "role", "ADMIN")).status())
        .isEqualTo(202);
    String invitation = adminClient.get("/api/me/invitations").json().get(0).path("id").asText();
    assertThat(adminClient.post("/api/me/invitations/" + invitation + "/accept", null).ok()).isTrue();

    // The Family's own page is Members; nothing Individual or Organization-only opens there.
    var context = owner.get("/api/me/context").json();
    assertThat(context.path("activeWorkspace").path("type").asText()).isEqualTo("FAMILY");
    for (JsonNode p : context.path("permissions")) assertThat(p.asText()).startsWith("members_");
    assertThat(owner.get("/api/workspaces/current/members").ok()).isTrue();
    // GET /api/agents lists the caller's own agents in any account (it has no permission); the
    // routes that act on agents, connectors, devices or the listing are refused.
    for (String path : new String[] {"/api/mcp-servers", "/api/organization/listing", "/api/devices"}) {
      var r = owner.get(path);
      assertThat(r.status()).as(path + " " + r.body()).isIn(403, 404);
    }
    assertThat(owner.post("/api/agents", Map.of("name", "Helper")).status()).isIn(403, 404);
  }

  @Test
  void aMemberCanLeaveAndTheOwnerCannot() {
    ApiClient owner = signUp(email("owner"));
    UUID family = createFamily(owner);
    enter(owner, family);
    String memberEmail = email("member");
    ApiClient member = signUp(memberEmail);
    stepUp(owner);
    assertThat(owner.post("/api/workspaces/current/invitations", Map.of("email", memberEmail, "role", "USER")).status())
        .isEqualTo(202);
    String invitation = member.get("/api/me/invitations").json().get(0).path("id").asText();
    assertThat(member.post("/api/me/invitations/" + invitation + "/accept", null).ok()).isTrue();

    var ownerLeaves = owner.delete("/api/workspaces/current/membership");
    assertThat(ownerLeaves.status()).as(ownerLeaves.body()).isBetween(400, 409);
    assertThat(ownerLeaves.body()).contains("Family");

    enter(member, family);
    assertThat(member.delete("/api/workspaces/current/membership").ok()).isTrue();
    assertThat(member.post("/api/me/active-workspace", Map.of("workspaceId", family.toString())).ok()).isFalse();
  }
}
