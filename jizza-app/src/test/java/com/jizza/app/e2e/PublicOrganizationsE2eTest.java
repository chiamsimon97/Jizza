package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.security.mfa.TotpService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance: public organizations. An organization appears on Discover only once a Super Admin
 * approves its listing, people see only the approved version of its profile, and joining one is
 * approved by its Owner or Admin -- all through the HTTP API.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class PublicOrganizationsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TotpService totp;

  private ApiClient owner;
  private ApiClient developer;
  private ApiClient outsider;
  private ApiClient admin;
  private String adminEmail;
  private String outsiderEmail;
  private UUID org;
  private String name;

  @BeforeEach
  void setUp() {
    name = "Acme " + UUID.randomUUID().toString().substring(0, 8);
    owner = signUp("listing-owner-" + UUID.randomUUID() + "@jizza.local",
        Map.of("accountType", "ORGANIZATION", "organizationName", name));
    org = UUID.fromString(owner.get("/api/me/context").json()
        .path("activeWorkspace").path("id").asText());

    String developerEmail = "listing-dev-" + UUID.randomUUID() + "@jizza.local";
    developer = signUp(developerEmail, Map.of());
    stepUp(owner);
    assertThat(owner.post("/api/workspaces/current/invitations",
        Map.of("email", developerEmail, "role", "DEVELOPER")).status()).isEqualTo(202);
    String invitation = developer.get("/api/me/invitations").json().get(0).path("id").asText();
    assertThat(developer.post("/api/me/invitations/" + invitation + "/accept", null).ok()).isTrue();
    assertThat(developer.post("/api/me/active-workspace", Map.of("workspaceId", org.toString()))
        .ok()).isTrue();

    outsiderEmail = "listing-outsider-" + UUID.randomUUID() + "@jizza.local";
    outsider = signUp(outsiderEmail, Map.of());
    adminEmail = "listing-admin-" + UUID.randomUUID() + "@jizza.local";
    admin = superAdmin(adminEmail);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("update users set platform_role = 'none' where email = ?", adminEmail);
  }

  private ApiClient signUp(String email, Map<String, Object> extra) {
    ApiClient client = new ApiClient(port);
    client.prime();
    Map<String, Object> body = new HashMap<>(extra);
    body.put("email", email);
    body.put("password", PASSWORD);
    var registered = client.post("/api/auth/register", body);
    assertThat(registered.ok()).as(registered.body()).isTrue();
    var login = client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.ok()).as(login.body()).isTrue();
    return client;
  }

  private void stepUp(ApiClient client) {
    assertThat(client.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
  }

  /**
   * A Super Admin signed in with two-step verification: the role alone is not enough. The role is
   * set in the database, as the bootstrapper would from configuration.
   */
  private ApiClient superAdmin(String email) {
    ApiClient client = signUp(email, Map.of());
    jdbc.update("update users set platform_role = 'super_admin' where email = ?", email);
    // Without two-step verification on the session, the role alone is refused.
    assertThat(client.get("/api/admin/listings").status()).isEqualTo(403);

    stepUp(client);
    var setup = client.post("/api/auth/mfa/setup", null);
    assertThat(setup.ok()).as(setup.body()).isTrue();
    String secret = setup.json().path("secret").asText();
    var confirmed = client.post("/api/auth/mfa/confirm",
        Map.of("code", totp.code(secret, Instant.now().getEpochSecond())));
    assertThat(confirmed.ok()).as(confirmed.body()).isTrue();
    String recovery = confirmed.json().path("codes").get(0).asText();

    ApiClient signedIn = new ApiClient(port);
    signedIn.prime();
    String token = signedIn.post("/api/auth/login", Map.of("email", email, "password", PASSWORD))
        .json().path("mfaToken").asText();
    var verified = signedIn.post("/api/auth/mfa/verify", Map.of("mfaToken", token, "code", recovery));
    assertThat(verified.ok()).as(verified.body()).isTrue();
    return signedIn;
  }

  private Map<String, Object> profile(String description) {
    Map<String, Object> p = new HashMap<>();
    p.put("displayName", name);
    p.put("description", description);
    p.put("category", "technology");
    p.put("website", "https://acme.example");
    return p;
  }

  private boolean onDiscover() {
    var page = outsider.get("/api/directory?q=" + name.replace(" ", "%20"));
    assertThat(page.ok()).as(page.body()).isTrue();
    for (JsonNode item : page.json().path("items")) {
      if (item.path("id").asText().equals(org.toString())) return true;
    }
    return false;
  }

  private JsonNode discovered() {
    var profile = outsider.get("/api/directory/" + org);
    assertThat(profile.ok()).as(profile.body()).isTrue();
    return profile.json();
  }

  private String status() {
    return owner.get("/api/organization/listing").json().path("status").asText();
  }

  @Test
  void anOrganizationIsListedOnlyAfterASuperAdminApprovesIt() {
    assertThat(onDiscover()).isFalse();
    assertThat(outsider.get("/api/directory/" + org).status()).isEqualTo(404);
    assertThat(status()).isEqualTo("NONE");

    // Only an Owner or Admin manages the listing; a Developer is refused.
    assertThat(developer.get("/api/organization/listing").status()).isEqualTo(403);
    assertThat(developer.put("/api/organization/listing", profile("Ours")).status()).isEqualTo(403);
    assertThat(developer.post("/api/organization/listing/submit", null).status()).isEqualTo(403);

    assertThat(owner.post("/api/organization/listing/submit", null).status()).isEqualTo(400);
    var bad = new HashMap<>(profile("x"));
    bad.put("website", "javascript:alert(1)");
    assertThat(owner.put("/api/organization/listing", bad).status()).isEqualTo(400);

    var saved = owner.put("/api/organization/listing", profile("Version one"));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    var submitted = owner.post("/api/organization/listing/submit", null);
    assertThat(submitted.ok()).as(submitted.body()).isTrue();
    assertThat(submitted.json().path("status").asText()).isEqualTo("PENDING");
    assertThat(owner.post("/api/organization/listing/submit", null).status()).isEqualTo(409);
    assertThat(onDiscover()).as("pending is not listed").isFalse();

    // Nobody but the Super Admin reviews: not an outsider, and not the organization's own Owner.
    for (ApiClient other : new ApiClient[] {outsider, owner, developer}) {
      assertThat(other.get("/api/admin/listings").status()).isEqualTo(403);
      assertThat(other.post("/api/admin/listings/" + org + "/approve", null).status())
          .isEqualTo(403);
      assertThat(other.post("/api/admin/listings/" + org + "/reject", Map.of("note", "no"))
          .status()).isEqualTo(403);
    }
    assertThat(status()).isEqualTo("PENDING");

    var queue = admin.get("/api/admin/listings?status=PENDING");
    assertThat(queue.ok()).as(queue.body()).isTrue();
    assertThat(queue.body()).contains(org.toString());

    // A rejection needs a reason, and the owner sees it.
    assertThat(admin.post("/api/admin/listings/" + org + "/reject", Map.of()).status())
        .isEqualTo(400);
    var rejected = admin.post("/api/admin/listings/" + org + "/reject",
        Map.of("note", "Add a description of what you do"));
    assertThat(rejected.ok()).as(rejected.body()).isTrue();
    assertThat(rejected.json().path("status").asText()).isEqualTo("REJECTED");
    assertThat(onDiscover()).as("rejected is not listed").isFalse();
    var ownerView = owner.get("/api/organization/listing").json();
    assertThat(ownerView.path("history").get(0).path("action").asText()).isEqualTo("REJECTED");
    assertThat(ownerView.path("history").get(0).path("note").asText())
        .isEqualTo("Add a description of what you do");

    // Fixed and sent again, then approved: now everyone sees it.
    assertThat(owner.put("/api/organization/listing", profile("We build robots")).ok()).isTrue();
    assertThat(owner.post("/api/organization/listing/submit", null).ok()).isTrue();
    var approved = admin.post("/api/admin/listings/" + org + "/approve", null);
    assertThat(approved.ok()).as(approved.body()).isTrue();
    assertThat(approved.json().path("status").asText()).isEqualTo("APPROVED");
    assertThat(onDiscover()).isTrue();
    JsonNode seen = discovered();
    assertThat(seen.path("profile").path("description").asText()).isEqualTo("We build robots");
    assertThat(seen.path("member").asBoolean()).isFalse();
    // Profile fields only: nothing about members, emails or the account's contents.
    assertThat(seen.toString()).doesNotContain("@jizza.local").doesNotContain("members");

    // An edit waits for review while the approved version stays listed.
    assertThat(owner.put("/api/organization/listing", profile("We build better robots")).ok())
        .isTrue();
    assertThat(owner.get("/api/organization/listing").json().path("changesSinceApproval")
        .asBoolean()).isTrue();
    assertThat(owner.post("/api/organization/listing/submit", null).ok()).isTrue();
    assertThat(onDiscover()).as("still listed while the edit is in review").isTrue();
    assertThat(discovered().path("profile").path("description").asText())
        .isEqualTo("We build robots");
    var editRejected = admin.post("/api/admin/listings/" + org + "/reject",
        Map.of("note", "Keep it factual"));
    assertThat(editRejected.json().path("status").asText()).as("a rejected edit stays listed")
        .isEqualTo("APPROVED");
    assertThat(discovered().path("profile").path("description").asText())
        .isEqualTo("We build robots");

    // Three submissions in a day is the limit.
    assertThat(owner.post("/api/organization/listing/submit", null).status()).isEqualTo(429);

    // Suspended: hidden, and it can't be sent again until reinstated.
    assertThat(admin.post("/api/admin/listings/" + org + "/suspend", Map.of()).status())
        .isEqualTo(400);
    assertThat(admin.post("/api/admin/listings/" + org + "/suspend", Map.of("note", "Spam reports"))
        .json().path("status").asText()).isEqualTo("SUSPENDED");
    assertThat(onDiscover()).isFalse();
    assertThat(outsider.get("/api/directory/" + org).status()).isEqualTo(404);
    assertThat(owner.post("/api/organization/listing/submit", null).status()).isEqualTo(409);
    assertThat(admin.post("/api/admin/listings/" + org + "/reinstate", null)
        .json().path("status").asText()).isEqualTo("APPROVED");
    assertThat(onDiscover()).isTrue();

    // Unlisting takes effect at once.
    assertThat(owner.post("/api/organization/listing/unlist", null).json().path("status").asText())
        .isEqualTo("NONE");
    assertThat(onDiscover()).isFalse();
    assertThat(outsider.get("/api/directory/" + org).status()).isEqualTo(404);
  }

  @Test
  void askingToJoinIsAcceptedByAnOwnerAndAddsOneUserMembership() {
    // Nobody can ask to join an organization that isn't listed.
    assertThat(outsider.post("/api/directory/" + org + "/join-request", Map.of()).status())
        .isEqualTo(404);

    assertThat(owner.put("/api/organization/listing", profile("Join us")).ok()).isTrue();
    assertThat(owner.post("/api/organization/listing/submit", null).ok()).isTrue();
    assertThat(admin.post("/api/admin/listings/" + org + "/approve", null).ok()).isTrue();

    var asked = outsider.post("/api/directory/" + org + "/join-request",
        Map.of("message", "I'd like to help"));
    assertThat(asked.ok()).as(asked.body()).isTrue();
    assertThat(outsider.post("/api/directory/" + org + "/join-request", Map.of()).status())
        .isEqualTo(409);
    assertThat(discovered().path("pendingRequest").path("status").asText()).isEqualTo("PENDING");
    // A member asking is told they already belong.
    assertThat(developer.post("/api/directory/" + org + "/join-request", Map.of()).status())
        .isEqualTo(409);

    // Only an Owner or Admin sees and answers requests.
    assertThat(developer.get("/api/organization/join-requests").status()).isEqualTo(403);
    var requests = owner.get("/api/organization/join-requests");
    assertThat(requests.ok()).as(requests.body()).isTrue();
    JsonNode request = requests.json().get(0);
    assertThat(request.path("email").asText()).isEqualTo(outsiderEmail);
    assertThat(request.path("message").asText()).isEqualTo("I'd like to help");
    String id = request.path("id").asText();
    assertThat(developer.post("/api/organization/join-requests/" + id + "/accept", null).status())
        .isEqualTo(403);

    int before = owner.get("/api/workspaces/current/members").json().size();
    var accepted = owner.post("/api/organization/join-requests/" + id + "/accept", null);
    assertThat(accepted.ok()).as(accepted.body()).isTrue();
    assertThat(accepted.json().path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(owner.post("/api/organization/join-requests/" + id + "/accept", null).status())
        .isEqualTo(404);

    JsonNode members = owner.get("/api/workspaces/current/members").json();
    assertThat(members).hasSize(before + 1);
    long joined = 0;
    for (JsonNode m : members) {
      if (m.path("email").asText().equals(outsiderEmail)) {
        joined++;
        assertThat(m.path("role").asText()).isEqualTo("USER");
      }
    }
    assertThat(joined).isEqualTo(1);
    assertThat(discovered().path("member").asBoolean()).isTrue();
  }

  @Test
  void aDeclinedOrCancelledRequestLeavesTheOrganizationAsItWas() {
    assertThat(owner.put("/api/organization/listing", profile("Join us")).ok()).isTrue();
    assertThat(owner.post("/api/organization/listing/submit", null).ok()).isTrue();
    assertThat(admin.post("/api/admin/listings/" + org + "/approve", null).ok()).isTrue();

    assertThat(outsider.post("/api/directory/" + org + "/join-request", Map.of()).ok()).isTrue();
    assertThat(outsider.delete("/api/directory/" + org + "/join-request").ok()).isTrue();
    assertThat(outsider.delete("/api/directory/" + org + "/join-request").status()).isEqualTo(404);

    assertThat(outsider.post("/api/directory/" + org + "/join-request", Map.of()).ok()).isTrue();
    String id = owner.get("/api/organization/join-requests").json().get(0).path("id").asText();
    var declined = owner.post("/api/organization/join-requests/" + id + "/decline", null);
    assertThat(declined.json().path("status").asText()).isEqualTo("DECLINED");
    for (JsonNode m : owner.get("/api/workspaces/current/members").json()) {
      assertThat(m.path("email").asText()).isNotEqualTo(outsiderEmail);
    }
    assertThat(discovered().path("member").asBoolean()).isFalse();
    assertThat(discovered().path("pendingRequest").isNull()).isTrue();
  }
}
