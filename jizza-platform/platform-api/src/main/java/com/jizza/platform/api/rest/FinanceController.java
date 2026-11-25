package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kai's Portfolio: the family finance plan. Built phase by phase behind one server switch,
 * {@code jizza.finance.enabled} ({@code JIZZA_FINANCE_ENABLED}), off by default.
 *
 * <p>While the switch is off this controller is not registered at all, so every
 * {@code /api/finance/**} path answers 404 like any unknown route, and the client hides the tab
 * when {@link #status} is not found. Unfinished phases can therefore merge into the release branch
 * without anyone seeing them.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/finance")
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinanceController {

  /** What the client needs to decide whether to show the Portfolio tab. */
  public record FinanceStatus(boolean enabled) {}

  @GetMapping("/status")
  public FinanceStatus status() {
    return new FinanceStatus(true);
  }
}
