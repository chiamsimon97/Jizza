package com.jizza.platform.usecase.finance.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoriserTest {

  private final UUID groceries = UUID.randomUUID();
  private final UUID salary = UUID.randomUUID();
  private final UUID mine = UUID.randomUUID();

  @Test
  void theOwnersRulesComeFirstThenPlainWordsAndOtherwiseNothing() {
    var rule = Json.obj().put("matchText", "corner bakery").put("categoryId", mine.toString());
    var c = new Categoriser(List.of(rule), Map.of("groceries", groceries, "salary", salary));
    assertThat(c.categorise("UPI-CORNER BAKERY-cb@okbank-123456", "out")).isEqualTo(mine);
    assertThat(c.categorise("POS GROCERY STORE 4411", "out")).isEqualTo(groceries);
    assertThat(c.categorise("NEFT SALARY APR", "in")).isEqualTo(salary);
    // "salary" paid out is not income.
    assertThat(c.categorise("SALARY TO HELPER", "out")).isNull();
    assertThat(c.categorise("UPI-RANDOM SHOP-123", "out")).isNull();
  }

  @Test
  void aRuleIsMadeFromTheTellingWordsOnly() {
    assertThat(Categoriser.matchText("UPI-CORNER BAKERY-cb@okbank-123456-Payment from Phone"))
        .isEqualTo("corner bakery okbank");
    assertThat(Categoriser.matchText("12345 / 678")).isEmpty();
  }
}
