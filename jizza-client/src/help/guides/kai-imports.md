---
title: Import bank statements and fund statements into Kai
summary: Bring in a bank statement (CSV or PDF) from any bank, your mutual fund CAS, or your broker's holdings and tradebook, or let Kai pick up mailed statements from your email. Check every row before saving, and undo an import later.
section: Kai Portfolio
order: 30
updated: 2026-11-26
---

## Where to start

Open **Kai**, then **Portfolio**, then **Connect**. There is one card for each kind of file, with what it brings in and what it can't do. Press **Import a file** on the card you need.

Nothing is saved when you upload. Kai reads the file and shows you what it found. Only **Save** brings it in.

## Bank statements (CSV or PDF)

Download your statement as CSV from your bank's website or app. Any bank works: Kai finds the date, description, debit, credit and balance columns from their names. If your bank only offers Excel, open it and save it as CSV.

Choose the account the statement is for, or add one. For a new account you only need the bank's name; the last four digits are optional and only tell accounts apart.

If Kai can't tell which column is which, it shows the first lines of the file and asks you. Pick the line with the column names, then the date, description and amount columns. Kai remembers your choice for that account's next statement.

You can also upload the PDF statement your bank mails you. If it's locked, type its password (banks often use your customer ID or date of birth); Kai uses it once and never keeps it. A PDF has no columns, so Kai reads each row's date, words, amount and the balance after it, and tells money in from money out by how the balance moved. For the first row it needs the opening balance printed on the statement. Any row whose balance doesn't add up, or whose side Kai can't tell, is listed under "couldn't be read" and never guessed. A scanned (picture) PDF can't be read: download CSV instead.

## Mutual funds (CAS)

Ask CAMS or KFintech for a consolidated account statement (CAS) by email, then upload the PDF. Choose whose statement it is, because one CAS covers one person's funds. Type the PDF password you set when you asked for it. Kai uses it to open the file once and never keeps it. If the password is wrong, Kai says so and nothing is saved.

Each folio becomes an account in Kai, and each scheme's value on the statement date counts towards your net worth.

## Broker holdings and tradebook (CSV)

Download your holdings and your tradebook from your broker's website as CSV, and choose your demat account. A holdings file is the whole account on the day you downloaded it: shares no longer listed are set to nothing. A tradebook brings in each buy and sell.

## Connected brokers (Zerodha's Kite)

Instead of downloading a holdings file, you can let Kai read your holdings straight from your broker.

1. Open **Connectors** and add **Zerodha Kite** from the list (its address is `https://mcp.kite.trade/mcp`). On its page, press **Sign in** and log in with your Zerodha account. Zerodha asks you to sign in again from time to time; the page shows **Sign in** when it does.
2. In Kai, open **Portfolio**, then **Connect**. Under **Connected brokers**, choose Kite and the demat account it holds, then press **Connect**.
3. Press **Sync now**. Kai reads your shares and your mutual funds held with Zerodha and opens the review below, just like a holdings file.

**Or just ask Kai in chat.** Once Kite is signed in, say "Sync my Zerodha" to Kai. Kai links Kite to your Zerodha demat account by itself (or makes one for you if you don't have one yet), reads your shares, your mutual funds and the cash left in your trading account, and tells you what it found. Nothing is saved until you say so; then it all lands in **Holdings** and **Net worth**, and you can undo it under **Connect**, **Import history**. Which goal each holding is for stays yours to set.

Kai also syncs on weekdays at 4:30 PM, after the market closes, while your Kite sign-in is still valid. When something has changed, you get a notification to review it. If nothing has changed since your last saved sync, nothing happens.

Kai uses only Kite's tools that read your holdings and your cash balance. It never places, changes or cancels an order. Zerodha's hosted server doesn't offer order placing anyway.

## Statements from your email

Your CAS and many bank statements arrive by email every month. Kai can pick them up from your mailbox so you don't have to download and upload them.

1. Open **Connectors** and add Gmail (or another mail server that can search and read mail). Sign in when it asks.
2. In Kai, open **Portfolio**, then **Connect**. Under **Statements from your email**, choose what to look for:
   - **Mutual fund CAS**: choose whose funds. Leave **From** empty to look for mails from CAMS and KFintech.
   - **Bank statement**: choose the bank account, and type the address your bank sends statements from. It's on any statement email.
3. Type the PDF password (for a CAS it's usually your PAN in capitals), then press **Add**.
4. Press **Check now**. Each new statement opens in the review below, just like an upload.

Kai also checks every morning at 7 AM and sends you a notification when a new statement is ready to review. The first check looks back 45 days.

The password is kept encrypted and is never shown again; you can change it with **Change password**. If a statement doesn't open with it, the source says **Password needed**. Kai only searches and reads your mail. It never sends, moves, labels or deletes anything. Other PDFs from the same sender, such as newsletters, are left alone, and a statement you already have is never brought in twice.

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
