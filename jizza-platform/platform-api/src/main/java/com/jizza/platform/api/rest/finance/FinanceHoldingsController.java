package com.jizza.platform.api.rest.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.finance.FinanceMarket.Price;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.finance.ledger.HoldingsUseCase;
import com.jizza.platform.usecase.finance.lists.ListsUseCase;
import com.jizza.platform.usecase.finance.market.InstrumentService;
import com.jizza.platform.usecase.finance.market.PriceService;
import com.jizza.platform.usecase.finance.rules.RulesService;
import com.jizza.platform.usecase.finance.tree.TreeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kai Portfolio, Phase 2: instruments and prices, holdings and their tax lots, earmarks (which
 * goal owns which units), each goal's asset tree and allocation, rules, and the watch and next
 * lists. Every route is the caller's own data only; another account's ids read as not found.
 * Exists only while {@code jizza.finance.enabled} is on.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/finance")
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinanceHoldingsController {

  private final InstrumentService instruments;
  private final PriceService prices;
  private final HoldingsUseCase holdings;
  private final TreeService trees;
  private final RulesService rules;
  private final ListsUseCase lists;

  public FinanceHoldingsController(InstrumentService instruments, PriceService prices, HoldingsUseCase holdings,
                                   TreeService trees, RulesService rules, ListsUseCase lists) {
    this.instruments = instruments;
    this.prices = prices;
    this.holdings = holdings;
    this.trees = trees;
    this.rules = rules;
    this.lists = lists;
  }

  // ── Instruments and prices ──────────────────────────────────────────────────

  @GetMapping("/instruments")
  public List<ObjectNode> searchInstruments(@RequestParam(value = "q", required = false) String q) {
    return instruments.search(CurrentUser.userId(), q);
  }

  @PostMapping("/instruments")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode createInstrument(@RequestBody JsonNode body) {
    return instruments.create(CurrentUser.userId(), body);
  }

  @PatchMapping("/instruments/{id}")
  public ObjectNode updateInstrument(@PathVariable UUID id, @RequestBody JsonNode body) {
    return instruments.update(CurrentUser.userId(), id, body);
  }

  @PostMapping("/prices/refresh")
  public PriceService.Refresh refreshPrices() {
    return prices.refreshNow(CurrentUser.userId());
  }

  public record PriceBody(UUID instrumentId, LocalDate date, BigDecimal price) {}

  @PostMapping("/prices")
  @ResponseStatus(HttpStatus.CREATED)
  public Price putPrice(@RequestBody PriceBody body) {
    return prices.putManual(CurrentUser.userId(), body.instrumentId(), body.date(), body.price());
  }

  // ── Holdings and earmarks ───────────────────────────────────────────────────

  @GetMapping("/holdings")
  public ObjectNode holdings(@RequestParam(value = "person", required = false) UUID personId,
                             @RequestParam(value = "goal", required = false) UUID goalId) {
    return holdings.list(CurrentUser.userId(), personId, goalId);
  }

  @GetMapping("/holdings/{accountId}/{instrumentId}")
  public ObjectNode holding(@PathVariable UUID accountId, @PathVariable UUID instrumentId) {
    return holdings.holding(CurrentUser.userId(), accountId, instrumentId);
  }

  /** Splits one holding's units (or one asset's amount) between goals; the parts must add up. */
  @PutMapping("/earmarks")
  public ObjectNode putEarmarks(@RequestBody JsonNode body) {
    return holdings.put(CurrentUser.userId(), body);
  }

  @PostMapping("/holdings/purchases")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode purchase(@RequestBody JsonNode body) {
    return holdings.purchase(CurrentUser.userId(), body);
  }

  @PostMapping("/holdings/sales")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode sale(@RequestBody JsonNode body) {
    return holdings.sale(CurrentUser.userId(), body);
  }

  @GetMapping("/realized-gains")
  public ObjectNode realisedGains(@RequestParam(value = "taxYear", required = false) String taxYear) {
    return holdings.realisedGains(CurrentUser.userId(), taxYear);
  }

  @PostMapping("/corporate-actions")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode corporateAction(@RequestBody JsonNode body) {
    return holdings.corporateAction(CurrentUser.userId(), body);
  }

  // ── Asset tree and allocation ───────────────────────────────────────────────

  @GetMapping("/tree")
  public TreeService.TreeView tree(@RequestParam("goal") UUID goalId) {
    return trees.get(CurrentUser.userId(), goalId);
  }

  @PutMapping("/tree/{goalId}")
  public TreeService.TreeView putTree(@PathVariable UUID goalId, @RequestBody JsonNode body) {
    return trees.put(CurrentUser.userId(), goalId, body);
  }

  @GetMapping("/allocation")
  public TreeService.AllocationView allocation(@RequestParam(value = "goal", required = false) UUID goalId) {
    return trees.allocation(CurrentUser.userId(), goalId);
  }

  // ── Rules ───────────────────────────────────────────────────────────────────

  @GetMapping("/rules/effective")
  public RulesService.RulesView effectiveRules(@RequestParam(value = "goal", required = false) UUID goalId,
                                               @RequestParam(value = "instrument", required = false) UUID instrumentId,
                                               @RequestParam(value = "node", required = false) UUID nodeId) {
    return rules.view(CurrentUser.userId(), goalId, instrumentId, nodeId);
  }

  @PutMapping("/rules")
  public RulesService.Saved putRule(@RequestBody JsonNode body) {
    return rules.put(CurrentUser.userId(), body);
  }

  @PostMapping("/rules/preview")
  public Map<String, Integer> previewRule(@RequestBody JsonNode body) {
    return Map.of("affectedEarmarks", rules.preview(CurrentUser.userId(), body));
  }

  @GetMapping("/rule-templates")
  public List<ObjectNode> ruleTemplates() {
    return rules.templates(CurrentUser.userId());
  }

  @PostMapping("/rule-templates")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode saveRuleTemplate(@RequestBody JsonNode body) {
    return rules.saveTemplate(CurrentUser.userId(), body);
  }

  /** The body is the level to apply it at: {level, goalId, nodeId, instrumentId}. */
  @PostMapping("/rule-templates/{id}/apply")
  public Map<String, Integer> applyRuleTemplate(@PathVariable UUID id, @RequestBody JsonNode target) {
    return Map.of("affectedEarmarks", rules.applyTemplate(CurrentUser.userId(), id, target));
  }

  // ── Watch and next lists ────────────────────────────────────────────────────

  @GetMapping("/lists")
  public List<ObjectNode> lists(@RequestParam(value = "type", required = false) String type) {
    return lists.list(CurrentUser.userId(), type);
  }

  @PostMapping("/lists")
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode createListItem(@RequestBody JsonNode body) {
    return lists.create(CurrentUser.userId(), body);
  }

  @PatchMapping("/lists/{id}")
  public ObjectNode updateListItem(@PathVariable UUID id, @RequestBody JsonNode body) {
    return lists.update(CurrentUser.userId(), id, body);
  }

  @DeleteMapping("/lists/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteListItem(@PathVariable UUID id) {
    lists.delete(CurrentUser.userId(), id);
  }

  @PostMapping("/lists/{id}/promote")
  public ObjectNode promote(@PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
    return lists.promote(CurrentUser.userId(), id, body);
  }
}
