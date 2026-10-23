package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Contract sweep over every REST endpoint the application registers, read at runtime from Spring's
 * own handler mapping, so an endpoint added later is covered without editing this test.
 *
 * <p>Three promises hold for every route:
 * <ul>
 *   <li>without a session it is refused (401/403), unless it is one of the few public routes;</li>
 *   <li>with a session, a made-up id, a wrong-shaped id, an empty body or malformed JSON is
 *       answered with a 4xx problem, never a 500;</li>
 *   <li>without the CSRF header, a mutating call is refused.</li>
 * </ul>
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ApiContractE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  /** Routes that are reachable without a session, by design. */
  private static final Set<String> PUBLIC = Set.of(
      "/api/auth/csrf", "/api/auth/login", "/api/auth/register", "/api/auth/recover",
      "/api/auth/recover/complete", "/api/auth/mfa/verify", "/api/auth/invitations/{token}",
      "/api/oauth/callback", "/api/mcp/oauth/callback", "/api/webhooks/{token}",
      "/api/auth/logout");

  /** Routes that end or reshape the caller's own session, which the sweep re-creates instead. */
  private static final Set<String> SESSION_ENDING = Set.of(
      "/api/auth/logout", "/api/auth/logout-everywhere", "/api/data", "/api/auth/password",
      "/api/workspaces/current/membership", "/api/me/active-workspace");

  private static final Pattern VAR = Pattern.compile("\\{([^}]+)}");

  @LocalServerPort private int port;
  @Autowired @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;
  private String email;

  record Route(String method, String pattern) {
    boolean mutating() { return !"GET".equals(method); }
    boolean hasVars() { return pattern.contains("{"); }
  }

  @BeforeEach
  void setUp() {
    model.reset();
    email = "contract-" + UUID.randomUUID() + "@jizza.local";
    api = new ApiClient(port);
    api.prime();
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
    signIn();
  }

  private void signIn() {
    api = new ApiClient(port);
    api.prime();
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.ok()).as("login: %s", login.body()).isTrue();
  }

  private List<Route> routes() {
    Set<Route> out = new java.util.LinkedHashSet<>();
    mappings.getHandlerMethods().forEach((info, method) -> {
      Set<String> patterns = info.getPatternValues();
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : patterns) {
        if (!pattern.startsWith("/api/")) continue;
        for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
          out.add(new Route(m.name(), pattern));
        }
      }
    });
    return out.stream().sorted(java.util.Comparator.comparing(Route::pattern)
        .thenComparing(Route::method)).toList();
  }

  /** Fills each path variable with something well-formed that matches nothing. */
  private static String fill(String pattern, boolean wellFormed) {
    Matcher m = VAR.matcher(pattern);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String name = m.group(1).replaceAll(":.*", "");
      String value;
      if (!wellFormed) value = "not-an-id";
      else if (name.equalsIgnoreCase("revision")) value = "999";
      else if (name.equalsIgnoreCase("kind")) value = "fact";
      else if (name.equalsIgnoreCase("type")) value = "TOOL";
      else if (name.equalsIgnoreCase("bodyId")) value = "SUBJECT:" + UUID.randomUUID();
      else if (name.equalsIgnoreCase("tool") || name.equalsIgnoreCase("ref")
          || name.equalsIgnoreCase("token")) value = "nothing-" + UUID.randomUUID();
      else value = UUID.randomUUID().toString();
      m.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  @Test
  void theSweepSeesTheWholeSurface() {
    // A guard on the sweep itself: if the handler mapping came back thin, every other test in this
    // class would pass vacuously.
    assertThat(routes()).hasSizeGreaterThan(200);
  }

  @Test
  void everyPrivateRouteRefusesACallerWithoutASession() {
    ApiClient anonymous = new ApiClient(port);
    anonymous.prime();
    List<String> leaks = new ArrayList<>();
    for (Route route : routes()) {
      if (PUBLIC.contains(route.pattern())) continue;
      var result = anonymous.send(route.method(), fill(route.pattern(), true),
          route.mutating() ? Map.of() : null);
      if (result.status() != 401 && result.status() != 403) {
        leaks.add(route.method() + " " + route.pattern() + " -> " + result.status());
      }
    }
    assertThat(leaks).as("routes answering without a session").isEmpty();
  }

  @Test
  void publicRoutesNeverFailWithAServerError() {
    ApiClient anonymous = new ApiClient(port);
    anonymous.prime();
    List<String> failures = new ArrayList<>();
    for (Route route : routes()) {
      if (!PUBLIC.contains(route.pattern())) continue;
      var result = anonymous.send(route.method(), fill(route.pattern(), true),
          route.mutating() ? Map.of() : null);
      if (result.status() >= 500) failures.add(route.method() + " " + route.pattern() + " -> "
          + result.status() + " " + result.body());
    }
    assertThat(failures).isEmpty();
  }

  @Test
  void unknownIdsAndEmptyBodiesAreAnsweredWithAProblemNotAServerError() {
    List<String> failures = new ArrayList<>();
    for (Route route : routes()) {
      if (SESSION_ENDING.contains(route.pattern())) continue;
      for (boolean wellFormed : route.hasVars() ? new boolean[] {true, false} : new boolean[] {true}) {
        String path = fill(route.pattern(), wellFormed);
        var result = api.send(route.method(), path, route.mutating() ? Map.of() : null);
        if (result.status() >= 500) {
          failures.add(route.method() + " " + path + " -> " + result.status() + " "
              + abbreviate(result.body()));
        }
        if (result.status() == 401) signIn();
      }
    }
    assertThat(failures).as("server errors").isEmpty();
  }

  @Test
  void pagingAndSearchParametersAtTheirBoundariesAreNotServerErrors() {
    List<String> failures = new ArrayList<>();
    String huge = "x".repeat(5_000);
    for (Route route : routes()) {
      if (route.mutating() || route.hasVars()) continue;
      for (String query : List.of("limit=-1&offset=-1", "limit=0", "limit=2147483647&offset=2147483647",
          "limit=abc", "q=" + huge, "q=%27%3B%20drop%20table%20users%3B--", "from=yesterday",
          "from=2030-01-01T00:00:00Z&to=2000-01-01T00:00:00Z", "tz=Not/AZone")) {
        String path = route.pattern() + "?" + query;
        var result = api.get(path);
        if (result.status() >= 500) {
          failures.add("GET " + path.substring(0, Math.min(path.length(), 120)) + " -> "
              + result.status() + " " + abbreviate(result.body()));
        }
        if (result.status() == 401) signIn();
      }
    }
    assertThat(failures).as("server errors at parameter boundaries").isEmpty();
  }

  @Test
  void anOversizedBodyIsRefusedBeforeItIsRead() {
    String big = "{\"message\":\"" + "x".repeat(3 * 1024 * 1024) + "\"}";
    var result = api.sendRaw("POST", "/api/chat/turns", big);
    assertThat(result.status()).isEqualTo(413);
    assertThat(result.json().path("title").asText()).isEqualTo("Request too large");
  }

  @Test
  void anOverlongChatMessageIsAValidationError() {
    var result = api.post("/api/chat/turns", Map.of("message", "x".repeat(40_000)));
    assertThat(result.status()).isEqualTo(400);
    assertThat(result.body()).contains("message");
  }

  @Test
  void malformedJsonIsABadRequest() {
    List<String> failures = new ArrayList<>();
    for (Route route : routes()) {
      if (!route.mutating() || SESSION_ENDING.contains(route.pattern())) continue;
      if (route.method().equals("DELETE")) continue;
      String path = fill(route.pattern(), true);
      var result = api.sendRaw(route.method(), path, "{\"broken\": ");
      if (result.status() >= 500) {
        failures.add(route.method() + " " + path + " -> " + result.status() + " "
            + abbreviate(result.body()));
      }
      if (result.status() == 401) signIn();
    }
    assertThat(failures).as("server errors on malformed JSON").isEmpty();
  }

  @Test
  void mutatingRoutesRefuseACallWithoutTheCsrfToken() {
    Set<String> accepted = new TreeSet<>();
    for (Route route : routes()) {
      if (!route.mutating() || PUBLIC.contains(route.pattern())) continue;
      var result = api.sendWithoutCsrf(route.method(), fill(route.pattern(), true), Map.of());
      if (result.status() != 403) accepted.add(route.method() + " " + route.pattern() + " -> "
          + result.status());
    }
    assertThat(accepted).as("mutating routes that did not demand the CSRF token").isEmpty();
  }

  @Test
  void problemResponsesHaveAStableShape() {
    var missing = api.get("/api/agents/" + UUID.randomUUID());
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.json().path("type").asText()).isNotBlank();
    assertThat(missing.json().path("status").asInt()).isEqualTo(404);
    // Nothing internal leaks out of a problem body.
    assertThat(missing.body()).doesNotContain("Exception").doesNotContain("at com.jizza");

    var badId = api.get("/api/agents/not-an-id");
    assertThat(badId.status()).isEqualTo(400);
    assertThat(badId.body()).doesNotContain("Exception");
  }

  private static String abbreviate(String body) {
    if (body == null) return "";
    return body.length() > 300 ? body.substring(0, 300) + "…" : body;
  }
}
