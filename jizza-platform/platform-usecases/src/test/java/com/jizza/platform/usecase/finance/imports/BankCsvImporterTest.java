package com.jizza.platform.usecase.finance.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Synthetic statements in the layouts Indian banks export; no real statement is used. */
class BankCsvImporterTest {

  private static Statement parse(String csv) {
    return BankCsvImporter.parse(csv.getBytes(StandardCharsets.UTF_8), null, 5000);
  }

  @Test
  void readsAnHdfcStyleExportWithDebitAndCreditColumns() {
    Statement st = parse("""
        Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
        01/04/25,UPI-GROCERY MART-123@ok-500100,0000500100,01/04/25,"1,250.50",,"48,749.50"
        03/04/25,SALARY APR ACME TECH,0000000001,03/04/25,,"85,000.00","1,33,749.50"
        05/04/25,ELECTRICITY BILL PAY,0000000002,05/04/25,"2,100.00",,"1,31,649.50"
        """);
    assertThat(st.columns().sure()).isTrue();
    assertThat(st.txns()).hasSize(3);
    var first = st.txns().get(0);
    assertThat(first.date()).isEqualTo(LocalDate.of(2025, 4, 1));
    assertThat(first.amount()).isEqualByComparingTo("1250.50");
    assertThat(first.direction()).isEqualTo("out");
    assertThat(st.txns().get(1).direction()).isEqualTo("in");
    assertThat(st.balance().amount()).isEqualByComparingTo("131649.50");
    assertThat(st.balance().date()).isEqualTo(LocalDate.of(2025, 4, 5));
  }

  @Test
  void skipsTheLinesAboveAnSbiStyleHeaderAndTheTotalsBelow() {
    Statement st = parse("""
        Account Name,:,<redacted>
        Account Number,:,<redacted>
        Branch,:,Somewhere

        Txn Date,Value Date,Description,Ref No./Cheque No.,Debit,Credit,Balance
        2 Apr 2025,2 Apr 2025,TO TRANSFER-UPI/DR/12345/RENT,,15000.00,,20000.00
        4 Apr 2025,4 Apr 2025,BY TRANSFER-NEFT SALARY,,,50000.00,70000.00
        ,,Total,,15000.00,50000.00,
        **This is a computer generated statement**
        """);
    assertThat(st.columns().headerRow()).isEqualTo(4);
    assertThat(st.txns()).extracting(Statement.Txn::direction).containsExactly("out", "in");
    assertThat(st.unreadable()).isEmpty();
    assertThat(st.balance().amount()).isEqualByComparingTo("70000");
  }

  @Test
  void readsCanaraStyleDatesWithATimeOfDayAndANewestFirstOrder() {
    Statement st = parse("""
        Txn Date,Value Date,Cheque No.,Description,Branch Code,Debit,Credit,Balance
        10-04-2025 18:02:11,10-04-2025,,UPI/PHARMACY,1234,450.00,,9550.00
        08-04-2025 09:15:00,08-04-2025,,NEFT CR,1234,,10000.00,10000.00
        """);
    assertThat(st.txns()).hasSize(2);
    assertThat(st.txns().get(0).date()).isEqualTo(LocalDate.of(2025, 4, 10));
    assertThat(st.balance().amount()).isEqualByComparingTo("9550");
  }

  @Test
  void readsOneAmountColumnWithADrCrColumn() {
    Statement st = parse("""
        Date;Particulars;Amount;Dr/Cr;Balance
        2025-04-01;School fees;12000;DR;8000 Cr
        2025-04-02;Refund;300;CR;8300 Cr
        """);
    assertThat(st.txns()).extracting(Statement.Txn::direction).containsExactly("out", "in");
    assertThat(st.balance().amount()).isEqualByComparingTo("8300");
  }

  @Test
  void listsRowsItCannotReadAndNeverGuessesThem() {
    Statement st = parse("""
        Date,Description,Debit,Credit,Balance
        01/04/2025,Good row,100,,900
        32/04/2025,Bad date,100,,800
        02/04/2025,No amount,,,800
        03/04/2025,Both sides,100,100,800
        04/04/2025,Not a number,abc,,800
        """);
    assertThat(st.txns()).hasSize(1);
    assertThat(st.unreadable()).extracting(Statement.Unreadable::line).containsExactly(3, 4, 5, 6);
  }

  @Test
  void asksForTheColumnsWhenTheHeadersSayNothingAndUsesTheChoice() {
    String csv = """
        When,What,Out,In
        01/04/2025,Something,100,
        02/04/2025,Other,,50
        """;
    Statement unsure = parse(csv);
    assertThat(unsure.columns().sure()).isFalse();
    assertThat(unsure.txns()).isEmpty();
    assertThat(unsure.columns().sample()).isNotEmpty();

    var mapping = Json.obj().put("headerRow", 0).put("date", "When").put("description", "What")
        .put("debit", "Out").put("credit", "In");
    Statement st = BankCsvImporter.parse(csv.getBytes(StandardCharsets.UTF_8), mapping, 5000);
    assertThat(st.columns().sure()).isTrue();
    assertThat(st.txns()).extracting(Statement.Txn::direction).containsExactly("out", "in");
    assertThat(st.columns().mapping().path("debit").asText()).isEqualTo("Out");

    var wrong = Json.obj().put("headerRow", 0).put("date", "Nope");
    assertThatThrownBy(() -> BankCsvImporter.parse(csv.getBytes(StandardCharsets.UTF_8), wrong, 5000))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void usesTheColumnsChosenForThisAccountBeforeWhenItCannotTellAndTheyFit() {
    var remembered = Json.obj().put("headerRow", 0).put("date", "When").put("description", "What")
        .put("debit", "Out").put("credit", "In");
    String moved = "Statement for April\n\nWhen,What,Out,In\n01/04/2025,Something,100,\n";
    Statement st = BankCsvImporter.parse(moved.getBytes(StandardCharsets.UTF_8), null, remembered, 5000);
    assertThat(st.columns().sure()).isTrue();
    assertThat(st.columns().headerRow()).isEqualTo(2);
    assertThat(st.txns()).hasSize(1);
    // A file the remembered columns don't fit still asks.
    Statement other = BankCsvImporter.parse("A,B\n1,2\n".getBytes(StandardCharsets.UTF_8), null, remembered, 5000);
    assertThat(other.columns().sure()).isFalse();
  }

  @Test
  void refusesAFileLongerThanTheLimit() {
    StringBuilder sb = new StringBuilder("Date,Description,Debit,Credit\n");
    for (int i = 0; i < 80; i++) sb.append("01/04/2025,Row,1,\n");
    assertThatThrownBy(() -> BankCsvImporter.parse(sb.toString().getBytes(StandardCharsets.UTF_8), null, 20))
        .isInstanceOf(Csv.TooLong.class);
  }
}
