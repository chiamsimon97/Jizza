package com.jizza.platform.usecase.finance.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** A made-up CAS in the CAMS / KFintech text layout, as PDFBox reads it. */
class CasImporterTest {

  /** Synthetic: invented folios, schemes and ISINs. Also used by the end-to-end test. */
  static final String CAS = """
      Consolidated Account Statement
      01-Apr-2024 To 31-Mar-2025
      Example Mutual Fund
      Folio No: 1234567 / 89  PAN: <redacted>  KYC: OK
      <redacted>
      EX01-Example Flexi Cap Fund - Direct Plan - Growth - ISIN: INF000X01AA1(Advisor: DIRECT) Registrar : CAMS
      Opening Unit Balance: 0.000
      05-Apr-2024 Purchase - Systematic Investment 5,000.00 100.000 50.0000 100.000
      05-Apr-2024 *** Stamp Duty *** 0.25
      05-May-2024 SIP Purchase 5,000.00 95.238 52.5000 195.238
      10-Jan-2025 Redemption (1,800.00) (30.000) 60.0000 165.238
      Closing Unit Balance: 165.238 NAV on 31-Mar-2025: INR 61.0000 Total Cost Value: 8,500.00 Market Value on 31-Mar-2025: INR 10,079.52
      EX02-Example Liquid Fund - Direct - Growth - ISIN: INF000X01BB2(Advisor: DIRECT) Registrar : CAMS
      Opening Unit Balance: 10.000
      12-Jun-2024 Something odd happened 100.00 0.050 2000.0000 10.050
      Closing Unit Balance: 10.050 NAV on 31-Mar-2025: INR 2,100.0000
      Other Mutual Fund
      Folio No: 99887766  PAN: <redacted>
      OT01-Other Gold Savings Fund - Growth - ISIN: INF000Y01CC3 Registrar : KFINTECH
      01-Jul-2024 Switch In - From Other Fund 2,000.00 200.000 10.0000 200.000
      Closing Unit Balance: 200.000 NAV on 31-Mar-2025: INR 11.0000 Market Value on 31-Mar-2025: INR 2,200.00
      """;

  @Test
  void readsFoliosSchemesTransactionsAndClosingValues() {
    Statement st = CasImporter.parse(CAS, 5000);
    assertThat(st.groups()).extracting(Statement.Group::key).containsExactly("folio:1234567/89", "folio:99887766");
    assertThat(st.groups().get(0).last4()).isEqualTo("4567");
    assertThat(st.groups().get(0).provider()).isEqualTo("Example Mutual Fund");

    assertThat(st.txns()).extracting(Statement.Txn::kind)
        .containsExactly("sip", "fee", "sip", "sell", "buy", "stp_in");
    var sip = st.txns().get(0);
    assertThat(sip.date()).isEqualTo(LocalDate.of(2024, 4, 5));
    assertThat(sip.amount()).isEqualByComparingTo("5000");
    assertThat(sip.units()).isEqualByComparingTo("100");
    assertThat(sip.price()).isEqualByComparingTo("50");
    assertThat(sip.direction()).isEqualTo("out");
    var sale = st.txns().get(3);
    assertThat(sale.units()).isEqualByComparingTo("-30");
    assertThat(sale.amount()).isEqualByComparingTo("1800");
    assertThat(sale.direction()).isEqualTo("in");
    // The words don't say what this was: kept, but marked for a look rather than guessed.
    assertThat(st.txns().get(4).kindKnown()).isFalse();

    assertThat(st.holdings()).hasSize(3);
    var flexi = st.holdings().get(0);
    assertThat(flexi.name()).isEqualTo("Example Flexi Cap Fund - Direct Plan - Growth");
    assertThat(flexi.isin()).isEqualTo("INF000X01AA1");
    assertThat(flexi.assetClass()).isEqualTo("equity");
    assertThat(flexi.value()).isEqualByComparingTo("10079.52");
    assertThat(flexi.asOf()).isEqualTo(LocalDate.of(2025, 3, 31));
    // No market value written: units times the stated NAV.
    assertThat(st.holdings().get(1).value()).isEqualByComparingTo("21105.00");
    assertThat(st.holdings().get(1).assetClass()).isEqualTo("debt");
    assertThat(st.holdings().get(2).assetClass()).isEqualTo("gold");
    assertThat(st.holdings().get(2).group()).isEqualTo("folio:99887766");
  }

  @Test
  void refusesTextThatIsNotACas() {
    assertThatThrownBy(() -> CasImporter.parse("Hello\nThis is a letter\n", 5000))
        .isInstanceOf(ValidationException.class).hasMessageContaining("consolidated account statement");
  }
}
