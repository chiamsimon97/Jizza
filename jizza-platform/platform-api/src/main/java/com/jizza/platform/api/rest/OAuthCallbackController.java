package com.jizza.platform.api.rest;

import com.jizza.engine.api.connector.OAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public OAuth redirect target (R24).
 *
 * <p>Unauthenticated by necessity: the provider sends the browser here and cannot carry a session
 * cookie or a CSRF token. The single-use, expiring, session-bound {@code state} is what
 * authenticates the callback instead.
 *
 * <p>It answers with a small HTML page rather than JSON because a human is looking at it.
 */
@RestController
public class OAuthCallbackController {

  private final OAuthService oauth;

  public OAuthCallbackController(OAuthService oauth) {
    this.oauth = oauth;
  }

  @GetMapping("/api/oauth/callback")
  public ResponseEntity<String> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false)
                                         String errorDescription) {
    if (error != null) {
      return page(HttpStatus.BAD_REQUEST, "Connection not completed",
          "The service reported: " + escape(error)
              + (errorDescription == null ? "" : " (" + escape(errorDescription) + ")"));
    }
    try {
      var connection = oauth.completeCallback(state, code);
      return page(HttpStatus.OK, "Connected",
          escape(connection.serviceName()) + " is connected. You can close this tab.");
    } catch (RuntimeException e) {
      // The message is ours, not the provider's body, so nothing sensitive is rendered.
      return page(HttpStatus.BAD_REQUEST, "Connection not completed", escape(e.getMessage()));
    }
  }

  private ResponseEntity<String> page(HttpStatus status, String title, String body) {
    return ResponseEntity.status(status)
        .contentType(MediaType.TEXT_HTML)
        .body("""
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <title>%s</title></head>
            <body style="font-family:system-ui;margin:4rem auto;max-width:32rem">
            <h1>%s</h1><p>%s</p></body></html>
            """.formatted(title, title, body));
  }

  /** The provider controls these strings, so nothing goes into the page unescaped. */
  private String escape(String value) {
    if (value == null) return "";
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }
}
