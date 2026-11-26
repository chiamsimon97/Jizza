package com.jizza.platform.api.rest.finance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.security.AuthService;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.finance.orders.OrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kai part D: orders the person places themselves. Prepare fills one in with its tax effect;
 * placing it asks for the password every time (not a remembered step-up) and sends it through the
 * connected broker. Kai never places an order on its own. Exists only while
 * {@code jizza.finance.enabled} is on.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/finance/orders")
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinanceOrderController {

  private final OrderService orders;
  private final AuthService auth;

  public FinanceOrderController(OrderService orders, AuthService auth) {
    this.orders = orders;
    this.auth = auth;
  }

  public record OrderBody(UUID accountId, UUID instrumentId, String side, BigDecimal quantity, String orderType,
                          BigDecimal limitPrice, UUID goalId, String password) {
    OrderService.OrderInput input() {
      return new OrderService.OrderInput(accountId, instrumentId, side, quantity, orderType, limitPrice, goalId);
    }
  }

  @GetMapping
  public List<ObjectNode> list() {
    return orders.list(CurrentUser.userId());
  }

  @PostMapping("/prepare")
  public OrderService.Preview prepare(@RequestBody OrderBody body) {
    if (body == null) throw new ValidationException("Send the order.");
    return orders.prepare(CurrentUser.userId(), body.input());
  }

  /** Checks the password for this one order, then sends it. */
  @PostMapping("/place")
  public OrderService.Placed place(@RequestBody OrderBody body) {
    if (body == null || body.password() == null || body.password().isBlank()) {
      throw new ValidationException("Enter your password to place the order.");
    }
    var me = CurrentUser.require();
    auth.stepUp(me.userId(), me.sessionId(), body.password(), null);
    return orders.place(me.userId(), body.input());
  }
}
