package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: Individual and Organization accounts, membership requests, switching between
 * accounts, and role-based access to connectors and the Members tab -- all through the HTTP API.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class WorkspacesAndRbacE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;

  private ApiClient owner;
  private ApiClient bob;
  private String bobEmail;
  private UUID acme;

  @BeforeEach
  void setUp() {
    String ownerEmail = "org-owner-" + UUID.randomUUID() + "@jizza.local";
    owner = signUp(ownerEmail, Map.of("accountType", "ORGANIZATION", "organizationName", "Acme"));
    bobEmail = "bob-" + UUID.randomUUID() + "@jizza.local";
    bob = signUp(bobEmail, Map.of());

    var context = owner.get("/api/me/context");
    assertThat(context.ok()).as(context.body()).isTrue();
    acme = UUID.fromString(context.json().path("activeWorkspace").path("id").asText());
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

  /** The owner invites Bob, Bob accepts and switches into Acme. */
  private void bobJoinsAs(String role) {
    stepUp(owner);
    var invited = owner.post("/api/workspaces/current/invitations",
        Map.of("email", bobEmail, "role", role));
    assertThat(invited.status()).as(invited.body()).isEqualTo(202);
    String invitationId = bob.get("/api/me/invitations").json().get(0).path("id").asText();
    assertThat(bob.post("/api/me/invitations/" + invitationId + "/accept", null).ok()).isTrue();
    var switched = bob.post("/api/me/active-workspace", Map.of("workspaceId", acme.toString()));
    assertThat(switched.ok()).as(switched.body()).isTrue();
  }

  private UUID bobsMemberId() {
    for (var m : owner.get("/api/workspaces/current/members").json()) {
      if (m.path("email").asText().equals(bobEmail)) return UUID.fromString(m.path("id").asText());
    }
    throw new AssertionError("Bob is not a member");
  }

  private Map<String, Object> connection(String name) {
    Map<String, Object> body = new HashMap<>();
    body.put("serviceName", name);
    body.put("authScheme", "API_KEY");
    body.put("baseUrl", "https://api.example.com");
    return body;
  }

  @Test
  void anOrganizationSignUpStartsTheOwnerInTheOrganizationWithTheirOwnAccountBeside() {
    var context = owner.get("/api/me/context").json();
    assertThat(context.path("activeWorkspace").path("type").asText()).isEqualTo("ORGANIZATION");
    assertThat(context.path("activeWorkspace").path("name").asText()).isEqualTo("Acme");
    assertThat(context.path("role").asText()).isEqualTo("OWNER");
    assertThat(context.path("workspaces")).hasSize(2);
    assertThat(context.path("permissions").toString()).contains("members_invite");
    assertThat(context.path("assignableRoles").toString())
        .contains("ADMIN", "DEVELOPER", "USER").doesNotContain("OWNER");

    var individual = bob.get("/api/me/context").json();
    assertThat(individual.path("activeWorkspace").path("type").asText()).isEqualTo("PERSONAL");
    assertThat(individual.path("permissions").toString()).doesNotContain("members_view");
  }

  @Test
  void aPendingRequestIsShownToTheInviteeAndAcceptingLetsThemSwitchAccounts() {
    stepUp(owner);
    var invited = owner.post("/api/workspaces/current/invitations",
        Map.of("email", bobEmail, "role", "DEVELOPER"));
    assertThat(invited.status()).as(invited.body()).isEqualTo(202);
    var again = owner.post("/api/workspaces/current/invitations",
        Map.of("email", bobEmail, "role", "DEVELOPER"));
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.problemType()).endsWith("/invitation-pending");

    assertThat(bob.get("/api/me/context").json().path("pendingInvitationCount").asLong())
        .isEqualTo(1);
    var received = bob.get("/api/me/invitations").json();
    assertThat(received).hasSize(1);
    assertThat(received.get(0).path("workspaceName").asText()).isEqualTo("Acme");
    assertThat(received.get(0).path("role").asText()).isEqualTo("DEVELOPER");

    var accepted = bob.post("/api/me/invitations/" + received.get(0).path("id").asText()
        + "/accept", null);
    assertThat(accepted.ok()).as(accepted.body()).isTrue();
    assertThat(bob.get("/api/me/invitations").json()).isEmpty();

    String before = bob.sessionCookie();
    var switched = bob.post("/api/me/active-workspace", Map.of("workspaceId", acme.toString()));
    assertThat(switched.ok()).as(switched.body()).isTrue();
    assertThat(bob.sessionCookie()).as("switching issues a new session").isNotEqualTo(before);
    assertThat(switched.json().path("role").asText()).isEqualTo("DEVELOPER");
    assertThat(switched.json().path("permissions").toString())
        .contains("connector_create").doesNotContain("members_invite");

    // The old token was revoked with the switch.
    ApiClient stale = new ApiClient(port);
    stale.prime();
    stale.useSessionCookie(before);
    var replay = stale.send("GET", "/api/me/context", null);
    assertThat(replay.status()).isEqualTo(401);

    // And back to the Individual account.
    UUID personal = UUID.fromString(switched.json().path("workspaces").get(0).path("id").asText());
    var back = bob.post("/api/me/active-workspace", Map.of("workspaceId", personal.toString()));
    assertThat(back.json().path("activeWorkspace").path("type").asText()).isEqualTo("PERSONAL");
  }

  @Test
  void connectorsBelongToTheAccountTheyWereCreatedIn() {
    stepUp(owner);
    assertThat(owner.post("/api/connections", connection("Acme CRM")).ok()).isTrue();
    stepUp(bob);
    assertThat(bob.post("/api/connections", connection("Bob's Mail")).ok()).isTrue();

    bobJoinsAs("DEVELOPER");
    assertThat(bob.get("/api/connections").body()).contains("Acme CRM").doesNotContain("Bob's Mail");
    assertThat(owner.get("/api/connections").body()).doesNotContain("Bob's Mail");
  }

  @Test
  void aUserRoleCannotSeeConnectorsOrManageMembers() {
    bobJoinsAs("USER");
    var connectors = bob.get("/api/connections");
    assertThat(connectors.status()).isEqualTo(403);
    assertThat(connectors.problemType()).endsWith("/permission-denied");
    assertThat(connectors.json().path("permission").asText()).isEqualTo("connector_view");

    assertThat(bob.get("/api/workspaces/current/members").ok()).isTrue();
    stepUp(bob);
    var invite = bob.post("/api/workspaces/current/invitations",
        Map.of("email", "someone@jizza.local", "role", "USER"));
    assertThat(invite.status()).isEqualTo(403);
  }

  @Test
  void permissionIsCheckedBeforeStepUp() {
    bobJoinsAs("USER");
    // No step-up on Bob's new session: he is told he lacks the permission, not asked to re-enter
    // his password for something he could never do.
    var invite = bob.post("/api/workspaces/current/invitations",
        Map.of("email", "someone@jizza.local", "role", "USER"));
    assertThat(invite.status()).isEqualTo(403);
    assertThat(invite.problemType()).endsWith("/permission-denied");
  }

  @Test
  void theOwnerChangesARoleAndRemovingAMemberMovesThemBackToTheirOwnAccount() {
    bobJoinsAs("DEVELOPER");
    assertThat(bob.get("/api/connections").ok()).isTrue();

    UUID memberId = bobsMemberId();
    stepUp(owner);
    var changed = owner.patch("/api/workspaces/current/members/" + memberId, Map.of("role", "USER"));
    assertThat(changed.ok()).as(changed.body()).isTrue();
    assertThat(changed.json().path("role").asText()).isEqualTo("USER");
    assertThat(bob.get("/api/connections").status()).isEqualTo(403);

    var removed = owner.delete("/api/workspaces/current/members/" + memberId);
    assertThat(removed.ok()).as(removed.body()).isTrue();
    var context = bob.get("/api/me/context").json();
    assertThat(context.path("activeWorkspace").path("type").asText()).isEqualTo("PERSONAL");
    assertThat(context.path("workspaces")).hasSize(1);
  }

  @Test
  void nobodyChangesTheOwnerOrThemselves() {
    var members = owner.get("/api/workspaces/current/members").json();
    assertThat(members).hasSize(1);
    assertThat(members.get(0).path("you").asBoolean()).isTrue();
    stepUp(owner);
    var self = owner.patch("/api/workspaces/current/members/" + members.get(0).path("id").asText(),
        Map.of("role", "ADMIN"));
    assertThat(self.status()).isEqualTo(403);
    var ownerInvite = owner.post("/api/workspaces/current/invitations",
        Map.of("email", "x-" + UUID.randomUUID() + "@jizza.local", "role", "OWNER"));
    assertThat(ownerInvite.status()).isIn(400, 403);
  }

  @Test
  void aMemberCanLeaveAndAStrangerCannotEnter() {
    bobJoinsAs("DEVELOPER");
    var left = bob.delete("/api/workspaces/current/membership");
    assertThat(left.ok()).as(left.body()).isTrue();
    assertThat(left.json().path("activeWorkspace").path("type").asText()).isEqualTo("PERSONAL");

    var enter = bob.post("/api/me/active-workspace", Map.of("workspaceId", acme.toString()));
    assertThat(enter.status()).isEqualTo(404);
  }

  @Test
  void anIndividualAccountHasNoMembersTab() {
    var members = bob.get("/api/workspaces/current/members");
    assertThat(members.status()).isEqualTo(403);
  }

  @Test
  void anIndividualCanCreateAnOrganizationAndOwnsIt() {
    stepUp(bob);
    var created = bob.post("/api/workspaces", Map.of("name", "Bob's Bakery"));
    assertThat(created.ok()).as(created.body()).isTrue();
    assertThat(created.json().path("role").asText()).isEqualTo("OWNER");
    List<String> names = new java.util.ArrayList<>();
    bob.get("/api/me/context").json().path("workspaces")
        .forEach(w -> names.add(w.path("name").asText()));
    assertThat(names).contains("Bob's Bakery");
  }

  @Test
  void thePermissionDefinitionsAreServedWithoutGrants() {
    var list = owner.get("/api/permissions");
    assertThat(list.ok()).isTrue();
    assertThat(list.body()).contains("connector_view").doesNotContain("permittedRoles");
  }
}
