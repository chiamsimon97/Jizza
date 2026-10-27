package com.jizza.engine.api.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import com.jizza.engine.api.util.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserStepsTest {

  static final String KITE = "https://mcp.kite.trade/authorize?session_id=kitemcp-497e%7C1793.FPHS8q%3D";

  @Test
  void kiteLoginLinkBecomesASignInStep() {
    var output = TextNode.valueOf("Please log in using this link: " + KITE + ". Then tell me.");
    List<UserSteps.SignInLink> links = UserSteps.signInLinks("mcp_kite__login", output);

    assertThat(links).containsExactly(new UserSteps.SignInLink(KITE, "Kite"));
  }

  @Test
  void anAuthorizePageCountsFromAnyTool() {
    var output = Json.obj().put("message", "Open https://accounts.example.com/oauth2/auth?x=1 to connect");
    assertThat(UserSteps.signInLinks("some_tool", output))
        .extracting(UserSteps.SignInLink::service).containsExactly("Example");
  }

  @Test
  void ordinaryLinksFromOtherToolsAreLeftAlone() {
    var output = Json.obj().put("article", "https://news.example.com/markets/today");
    assertThat(UserSteps.signInLinks("mcp_news__search", output)).isEmpty();
    assertThat(UserSteps.signInLinks("mcp_kite__login", Json.obj().put("status", "ok"))).isEmpty();
  }

  @Test
  void neverOffersANonWebLink() {
    var output = TextNode.valueOf("javascript:alert(1) ftp://x.example.com/login");
    assertThat(UserSteps.signInLinks("login", output)).isEmpty();
    assertThat(UserSteps.safeUrl("javascript:alert(1)")).isFalse();
    assertThat(UserSteps.safeUrl(KITE)).isTrue();
  }

  @Test
  void theCardOpensTheLinkThenContinues() {
    UiAction card = UserSteps.signInCard(List.of(new UserSteps.SignInLink(KITE, "Kite")));

    assertThat(card.type()).isEqualTo(UiAction.SHOW_STEPS);
    assertThat(card.payload().path("title").asText()).isEqualTo("Sign in to Kite");
    var steps = card.payload().path("steps");
    assertThat(steps).hasSize(2);
    assertThat(steps.get(0).path("button").path("kind").asText()).isEqualTo("link");
    assertThat(steps.get(0).path("button").path("url").asText()).isEqualTo(KITE);
    assertThat(steps.get(1).path("button").path("kind").asText()).isEqualTo("reply");
    assertThat(UserSteps.urls(card)).containsExactly(KITE);
  }
}
