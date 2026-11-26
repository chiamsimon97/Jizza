package com.jizza.platform.usecase.finance.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.finance.imports.Statement.Txn;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Made-up bank statements in the layouts PDFBox gives for Indian banks' PDFs. */
class BankPdfImporterTest {

  /** Synthetic, in a dd/mm/yy layout with a value date column. Also used by the end-to-end test. */
  static final String SLASH_LAYOUT = """
      Example Bank Ltd                                Page No .: 1
      Statement of account
      Account No : <redacted>
      Opening Balance : 10,000.00
      Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance
      01/04/26 UPI-GROCERY STORE-PAYMENT 0000612345678 01/04/26 500.00 9,500.00
      02/04/26 NEFT CR-EMPLOYER PVT LTD-SALARY APR N0123456789 02/04/26 50,000.00 59,500.00
      05/04/26 EMI LOAN 12345 0000000000000 05/04/26 12,000.00 47,500.00
      06/04/26 POS ELECTRICITY BOARD BILL 06/04/26 1,250.50 46,249.50
      Page No .: 2
      Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance
      07/04/26 INTEREST CREDIT 07/04/26 49.50 46,299.00
      STATEMENT SUMMARY :-
      Opening Balance Dr Count Cr Count Debits Credits Closing Bal
      """;

  /** Synthetic, day-month-name layout whose narrations wrap and whose figures sit on the last line. */
  static final String WORD_LAYOUT = """
      Account Statement
      Balance as on 31 Mar 2026
      Txn Date Value Date Description Ref No./Cheque No. Debit Credit Balance
      1 Apr 2026 1 Apr 2026 BY TRANSFER-
      UPI/CR/601234/FRIEND NAME/
      SBIN/friend@upi TRANSFER FROM 1,000.00 21,000.00 CR
      3 Apr 2026 3 Apr 2026 TO TRANSFER-
      UPI/DR/601299/RENT/HDFC TRANSFER TO 15,000.00 6,000.00 CR
      """;

  @Test
  void readsDatesWordsAndSidesFromTheBalance() {
    Statement st = BankPdfImporter.parse(SLASH_LAYOUT, 5000);
    assertThat(st.unreadable()).isEmpty();
    assertThat(st.txns()).extracting(Txn::direction).containsExactly("out", "in", "out", "out", "in");
    assertThat(st.txns()).extracting(Txn::amount).extracting(BigDecimal::toPlainString)
        .containsExactly("500.00", "50000.00", "12000.00", "1250.50", "49.50");
    Txn salary = st.txns().get(1);
    assertThat(salary.date()).isEqualTo(LocalDate.of(2026, 4, 2));
    assertThat(salary.description()).contains("SALARY APR").doesNotContain("02/04/26");
    assertThat(st.balance().amount()).isEqualByComparingTo("46299.00");
    assertThat(st.balance().date()).isEqualTo(LocalDate.of(2026, 4, 7));
    assertThat(st.columns()).isNull();
  }

  @Test
  void joinsWrappedNarrationsAndReadsCrBalances() {
    Statement st = BankPdfImporter.parse(WORD_LAYOUT, 5000);
    // No opening balance: the first row's side is not guessed.
    assertThat(st.unreadable()).hasSize(1);
    assertThat(st.unreadable().get(0).reason()).contains("in or out");
    assertThat(st.txns()).hasSize(1);
    Txn rent = st.txns().get(0);
    assertThat(rent.direction()).isEqualTo("out");
    assertThat(rent.amount()).isEqualByComparingTo("15000.00");
    assertThat(rent.description()).contains("TO TRANSFER-").contains("RENT");
    assertThat(st.balance().amount()).isEqualByComparingTo("6000.00");
  }

  @Test
  void newestFirstStatementsWalkTheBalanceBackwards() {
    String text = """
        Opening Balance 1,000.00
        10-04-2026 CASH DEPOSIT 300.00 1,200.00
        05-04-2026 ATM WITHDRAWAL 100.00 900.00
        """;
    Statement st = BankPdfImporter.parse(text, 5000);
    assertThat(st.unreadable()).isEmpty();
    assertThat(st.txns()).extracting(Txn::direction).containsExactly("in", "out");
    assertThat(st.balance().amount()).isEqualByComparingTo("1200.00");
    assertThat(st.balance().date()).isEqualTo(LocalDate.of(2026, 4, 10));
  }

  @Test
  void aRowThatDoesNotAddUpIsListedNotGuessed() {
    String text = """
        Opening Balance 1,000.00
        01-04-2026 SHOP 100.00 900.00
        02-04-2026 SOMETHING 50.00 700.00
        03-04-2026 MORE 100.00 600.00
        """;
    Statement st = BankPdfImporter.parse(text, 5000);
    assertThat(st.unreadable()).extracting(Statement.Unreadable::line).containsExactly(3);
    assertThat(st.txns()).extracting(Txn::direction).containsExactly("out", "out");
  }

  @Test
  void dateDrCrColumnsWithAllThreeFigures() {
    String text = "01-04-2026 FEE 0.00 25.00 1,025.00\n02-04-2026 SHOP 40.00 0.00 985.00\n";
    Statement st = BankPdfImporter.parse(text, 5000);
    assertThat(st.txns()).extracting(Txn::direction).containsExactly("in", "out");
  }

  @Test
  void aPdfWithNoTransactionsSaysSo() {
    assertThatThrownBy(() -> BankPdfImporter.parse("Just a letter\nNothing here", 5000))
        .isInstanceOf(ValidationException.class).hasMessageContaining("couldn't find any transactions");
  }
}
