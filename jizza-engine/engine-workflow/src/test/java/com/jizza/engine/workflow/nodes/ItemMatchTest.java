package com.jizza.engine.workflow.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemMatchTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

  private static List<ItemMatch.Item> items(String... labels) {
    return java.util.Arrays.stream(labels).map(l -> new ItemMatch.Item(l, "")).toList();
  }

  @Test
  void namesAndPrefixes() {
    var list = items("Kantara", "Ramayana (Hindi)", "Dune");
    assertThat(ItemMatch.match(list, "ramayana", null, TODAY).chosen()).isEqualTo("Ramayana (Hindi)");
    assertThat(ItemMatch.match(list, "Dune", null, TODAY).chosen()).isEqualTo("Dune");
    assertThat(ItemMatch.match(list, "Avatar", null, TODAY).found()).isFalse();
  }

  @Test
  void datesInManyShapes() {
    var strip = items("Today", "Tomorrow", "Mon 10 Nov");
    assertThat(ItemMatch.match(strip, "9 Nov", null, TODAY).chosen()).isEqualTo("Tomorrow");
    assertThat(ItemMatch.match(strip, "10/11/2026", null, TODAY).chosen()).isEqualTo("Mon 10 Nov");
    var days = items("8", "9", "10");
    assertThat(ItemMatch.match(days, "2026-11-09", null, TODAY).chosen()).isEqualTo("9");
    assertThat(ItemMatch.date("Sunday 9th November", TODAY)).contains(LocalDate.of(2026, 11, 9));
    assertThat(ItemMatch.date("Ramayana", TODAY)).isEmpty();
  }

  @Test
  void pricesSettleTies() {
    assertThat(ItemMatch.price("Gold ₹ 1,250.50 · Silver ₹350")).isEqualByComparingTo(new BigDecimal("350"));
    assertThat(ItemMatch.price("USD 12 per seat")).isEqualByComparingTo(new BigDecimal("12"));
    assertThat(ItemMatch.price("Row 12")).isNull();
    var list = List.of(new ItemMatch.Item("A", "₹ 300"), new ItemMatch.Item("B", "₹ 500"));
    assertThat(ItemMatch.match(list, "", "highestPrice", TODAY).chosen()).isEqualTo("B");
    assertThat(ItemMatch.match(list, "", "first", TODAY).chosen()).isEqualTo("A");
    assertThat(ItemMatch.match(list, "", null, TODAY).found()).isFalse();
  }

  @Test
  void itemsWithTheSameNameAreToldApart() {
    var list = ItemMatch.items(Json.read("""
        [{"index":0,"label":"Ramayana","text":"Ramayana Hindi 2D"},
         {"index":1,"label":"Ramayana","text":"Ramayana Tamil 3D"}]"""));
    assertThat(list).extracting(ItemMatch.Item::display)
        .containsExactly("Ramayana · Hindi 2D", "Ramayana · Tamil 3D");
    var pick = ItemMatch.match(list, "Ramayana", null, TODAY);
    assertThat(pick.found()).isFalse();
    assertThat(pick.tied()).hasSize(2);
    assertThat(ItemMatch.match(list, "ramayana, tamil", null, TODAY).chosen())
        .isEqualTo("Ramayana · Tamil 3D");
  }
}
