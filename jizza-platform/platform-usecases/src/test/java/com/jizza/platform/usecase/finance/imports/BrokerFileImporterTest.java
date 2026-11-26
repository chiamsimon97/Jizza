package com.jizza.platform.usecase.finance.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BrokerFileImporterTest {

  private static final LocalDate TODAY = LocalDate.of(2025, 4, 10);

  @Test
  void readsAHoldingsExportWithACurrentValue() {
    Statement st = BrokerFileImporter.holdings("""
        Instrument,Qty.,Avg. cost,LTP,Cur. val,P&L,Net chg.,Day chg.
        EXAMPLECO,10,1500.50,1600,16000,995,6.6,0.5
        GOLDBEES,100,50,62.5,6250,1250,25,0.1
        SGBEXAMPLE-GB,5,5000,6500,32500,7500,30,0
        Total,,,,54750,,,
        """.getBytes(StandardCharsets.UTF_8), TODAY, 5000);
    assertThat(st.holdings()).hasSize(3);
    assertThat(st.holdings().get(0).value()).isEqualByComparingTo("16000");
    assertThat(st.holdings().get(0).assetClass()).isEqualTo("equity");
    assertThat(st.holdings().get(0).subCategory()).isEqualTo("shares");
    assertThat(st.holdings().get(1).assetClass()).isEqualTo("gold");
    assertThat(st.holdings().get(1).subCategory()).isEqualTo("etf");
    assertThat(st.holdings().get(2).subCategory()).isEqualTo("sgb");
    assertThat(st.holdings().get(0).asOf()).isEqualTo(TODAY);
  }

  @Test
  void worksOutTheValueFromQuantityAndPriceWhenThereIsNoValueColumn() {
    Statement st = BrokerFileImporter.holdings("""
        Symbol,ISIN,Sector,Quantity Available,Average Price,Previous Closing Price
        EXAMPLECO,INE000A01011,IT,12,100,110.5
        NOPRICE,INE000A01029,IT,3,100,
        """.getBytes(StandardCharsets.UTF_8), TODAY, 5000);
    assertThat(st.holdings()).hasSize(1);
    assertThat(st.holdings().get(0).value()).isEqualByComparingTo("1326.00");
    assertThat(st.holdings().get(0).isin()).isEqualTo("INE000A01011");
    assertThat(st.unreadable()).extracting(Statement.Unreadable::reason).containsExactly("No price or value");
  }

  @Test
  void readsATradebook() {
    Statement st = BrokerFileImporter.tradebook("""
        symbol,isin,trade_date,exchange,segment,series,trade_type,auction,quantity,price,trade_id,order_id,order_execution_time
        EXAMPLECO,INE000A01011,2025-03-03,NSE,EQ,EQ,buy,false,10,1500.5,T1,O1,2025-03-03T10:00:00
        EXAMPLECO,INE000A01011,2025-03-20,NSE,EQ,EQ,sell,false,4,1600,T2,O2,2025-03-20T10:00:00
        EXAMPLECO,INE000A01011,2025-03-21,NSE,EQ,EQ,hold,false,4,1600,T3,O3,2025-03-21T10:00:00
        """.getBytes(StandardCharsets.UTF_8), 5000);
    assertThat(st.txns()).extracting(Statement.Txn::kind).containsExactly("buy", "sell");
    assertThat(st.txns().get(0).amount()).isEqualByComparingTo("15005.00");
    assertThat(st.txns().get(0).direction()).isEqualTo("out");
    assertThat(st.txns().get(1).units()).isEqualByComparingTo("-4");
    assertThat(st.txns().get(0).ref()).isEqualTo("INE000A01011#T1");
    assertThat(st.unreadable()).hasSize(1);
  }

  @Test
  void asksForTheColumnsWhenItCannotFindThem() {
    Statement st = BrokerFileImporter.tradebook("a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8), 5000);
    assertThat(st.columns().sure()).isFalse();
    assertThat(st.columns().sample()).hasSize(2);
    assertThat(st.txns()).isEmpty();
  }

  /** Made-up export whose headers Kai doesn't know: the person says which column is which. */
  static final String ODD = """
      Report for <redacted>
      Scrip Title,Units Held,Invested Amt,Mkt Val,Code
      EXAMPLECO,10,15000,16000,INE000A01011
      OTHERCO,4,800,1000,
      """;

  @Test
  void readsHoldingsWithTheColumnsThePersonChose() {
    Statement asked = BrokerFileImporter.holdings(ODD.getBytes(StandardCharsets.UTF_8), null, null, TODAY, 5000);
    assertThat(asked.columns().sure()).isFalse();
    assertThat(asked.holdings()).isEmpty();

    var chosen = com.jizza.engine.api.util.Json.obj().put("headerRow", 1).put("name", "Scrip Title")
        .put("qty", "Units Held").put("invested", "Invested Amt").put("value", "Mkt Val").put("isin", "Code");
    Statement st = BrokerFileImporter.holdings(ODD.getBytes(StandardCharsets.UTF_8), chosen, null, TODAY, 5000);
    assertThat(st.columns().sure()).isTrue();
    assertThat(st.columns().sample()).isNotEmpty();
    assertThat(st.holdings()).hasSize(2);
    assertThat(st.holdings().get(0).value()).isEqualByComparingTo("16000");
    assertThat(st.holdings().get(0).avgCost()).isEqualByComparingTo("1500");
    assertThat(st.holdings().get(0).isin()).isEqualTo("INE000A01011");
    assertThat(st.holdings().get(1).avgCost()).isEqualByComparingTo("200");

    // The same choice is used again for this account's next file, found by its column names.
    Statement next = BrokerFileImporter.holdings(ODD.replace("Report for <redacted>\n", "").getBytes(StandardCharsets.UTF_8),
        null, chosen, TODAY, 5000);
    assertThat(next.holdings()).hasSize(2);

    // A choice that leaves out what Kai needs is refused with what to pick.
    var thin = com.jizza.engine.api.util.Json.obj().put("headerRow", 1).put("name", "Scrip Title");
    assertThatThrownBy(() -> BrokerFileImporter.holdings(ODD.getBytes(StandardCharsets.UTF_8), thin, null, TODAY, 5000))
        .isInstanceOf(ValidationException.class).hasMessageContaining("quantity");
  }
}
