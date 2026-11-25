---
title: Import bank statements and fund statements into Kai
summary: Bring in a bank CSV from any bank, your mutual fund CAS, or your broker's holdings and tradebook. Check every row before saving, and undo an import later.
section: Kai Portfolio
order: 30
updated: 2026-11-25
---

## Where to start

Open **Kai**, then **Portfolio**, then **Connect**. There is one card for each kind of file, with what it brings in and what it can't do. Press **Import a file** on the card you need.

Nothing is saved when you upload. Kai reads the file and shows you what it found. Only **Save** brings it in.

## Bank statements (CSV)

Download your statement as CSV from your bank's website or app. Any bank works: Kai finds the date, description, debit, credit and balance columns from their names. If your bank only offers Excel, open it and save it as CSV.

Choose the account the statement is for, or add one. For a new account you only need the bank's name; the last four digits are optional and only tell accounts apart.

If Kai can't tell which column is which, it shows the first lines of the file and asks you. Pick the line with the column names, then the date, description and amount columns. Kai remembers your choice for that account's next statement.

Bank PDFs can't be read yet.

## Mutual funds (CAS)

Ask CAMS or KFintech for a consolidated account statement (CAS) by email, then upload the PDF. Choose whose statement it is, because one CAS covers one person's funds. Type the PDF password you set when you asked for it. Kai uses it to open the file once and never keeps it. If the password is wrong, Kai says so and nothing is saved.

Each folio becomes an account in Kai, and each scheme's value on the statement date counts towards your net worth.

## Broker holdings and tradebook (CSV)

Download your holdings and your tradebook from your broker's website as CSV, and choose your demat account. A holdings file is the whole account on the day you downloaded it: shares no longer listed are set to nothing. A tradebook brings in each buy and sell.

## Checking before you save

The review shows:

- **New**: rows that will come in.
- **Already imported**: rows an earlier import brought in. They are left out, so nothing is counted twice.
- **Need a look**: bank rows Kai couldn't put in a category, or fund rows whose kind the statement didn't make clear. Kai never guesses. Pick a category now, or leave it and place it later.
- **Couldn't be read**: lines with a missing date or amount. They are listed by line number and left out.

When you pick a category for a row, tick **Always for rows like this** and Kai uses it for similar rows from now on.

## Undo

**Import history** lists every import. **Undo** takes out its rows and puts back every balance and value it changed. If a later import changed the same balance, undo that one first.

The same file can't be imported twice. To bring it in again, undo the earlier import first.
