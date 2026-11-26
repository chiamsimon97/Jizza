package com.jizza.platform.usecase.finance.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import com.jizza.engine.api.util.Json;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SourceHoldingsTest {

  static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

  @Test
  void readsKiteSharesAndFundsFromTextOrAPagedAnswer() {
    var shares = TextNode.valueOf("[{\"tradingsymbol\":\"GOLDBEES\",\"isin\":\"INF000G01011\",\"quantity\":5,"
        + "\"t1_quantity\":1,\"average_price\":50,\"last_price\":0,\"close_price\":55}]");
    var funds = Json.read("{\"data\":[{\"fund\":\"EXAMPLE FUND\",\"tradingsymbol\":\"INF000X01AA1\",\"quantity\":\"10.5\","
        + "\"average_price\":10,\"last_price\":12}],\"pagination\":{\"has_more\":false}}");
    Statement st = SourceHoldings.kite(shares, funds, TODAY);
    assertThat(st.holdings()).hasSize(2);
    var gold = st.holdings().get(0);
    assertThat(gold.units()).isEqualByComparingTo("6");
    assertThat(gold.value()).isEqualByComparingTo("330"); // no last price: the close price
    assertThat(gold.assetClass()).isEqualTo("gold");
    assertThat(gold.subCategory()).isEqualTo("etf");
    var fund = st.holdings().get(1);
    assertThat(fund.isin()).isEqualTo("INF000X01AA1");
    assertThat(fund.value()).isEqualByComparingTo("126.00");
    assertThat(fund.avgCost()).isEqualByComparingTo("10");
  }

  @Test
  void skipsWhatIsNoLongerHeldAndListsWhatItCannotRead() {
    var shares = Json.read("[{\"tradingsymbol\":\"SOLD\",\"quantity\":0},{\"tradingsymbol\":\"NOPRICE\",\"quantity\":3},{\"quantity\":1}]");
    Statement st = SourceHoldings.kite(shares, null, TODAY);
    assertThat(st.holdings()).isEmpty();
    assertThat(st.unreadable()).extracting(Statement.Unreadable::reason).containsExactly("No price", "No symbol");
    assertThat(SourceHoldings.rows(TextNode.valueOf("Please log in first using the login tool"))).isEmpty();
  }
}
