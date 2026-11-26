---
title: Holdings, goals and the asset tree in Kai
summary: See what you hold with its tax lots, say which goal each unit is for, set each goal's mix and rebalance band, keep a watchlist, and place orders yourself. Kai never buys or sells on its own.
section: Kai Portfolio
order: 40
updated: 2026-11-26
---

## Where holdings come from

Open **Kai**, then **Portfolio**, then **Investments**. Holdings come from the files you import under **Connect**:

- A mutual fund statement (CAS) gives each scheme with its purchases.
- A broker's tradebook gives each buy and sell.
- A broker's holdings file gives what you hold on the day you downloaded it.

You can also record a purchase by hand from **Lists** with **I bought it**. Kai never places an order on its own.

## Holdings

**Holdings** lists each share, fund or bond with its units, value, gain and the goals it is for. Fund prices come from AMFI each night, or press **Refresh fund prices**. Share prices come from your broker's holdings file, so they are as fresh as your last file. A price read from your file values only your own holdings.

**Needs a look** comes first. A holding is listed there when:

- its goals no longer add up to the units held;
- units were sold outside Kai;
- some units have no purchase date, so their tax term is unknown;
- Kai has no price for it yet.

## Which goal each unit is for

Open a holding to see the earmark editor. Type how many units belong to each goal. The total under the boxes must match the units held before **Save split** turns on. Units no goal claims sit in **Unassigned**.

Moving units between goals is not a sale. Nothing is bought or sold, and no tax arises.

The tax lots below show the order a sale uses them. The oldest lot always goes first, whichever goal sells. A goal never holds particular lots.

When units are sold outside Kai, they come off **Unassigned** first, then off each goal in proportion.

## Shares in a goal under three years away

Kai's mix for a short goal has no direct shares, because shares can fall sharply in that time. If you put shares there anyway, Kai asks first. Choose **Keep the shares** and Kai marks them **Kept by your choice**, but still flags them as the goal gets near.

## Asset tree and allocation

**Asset tree** shows a goal's mix: asset classes, then their parts, each as a share of the level above. Until you save it, it is Kai's starting mix for that goal. Each group must add up to 100% before **Save** turns on.

**Allocation** compares what the goal holds with its targets. By default the band is 5 points either side for a target of 20% or more, and a quarter of the target for smaller ones. An emergency fund is never rebalanced. Kai only shows the drift. Suggestions to fix it come in a later release.

## Rules

Turn on **Advanced** in Settings to see **Rules**. Rules include:

- stop loss;
- profit booking;
- rebalance band;
- execution mode;
- a cap on one holding;
- when to buy more;
- when to sell.

Each rule shows where its value comes from: this holding, its sub-category or class, the goal, all your goals, or Kai's default.

A **lock** at one level means levels below it can only be stricter. Before you save, Kai says how many earmarks the change reaches. Every goal starts in **Observe**: Kai watches and flags, and proposes nothing.

## Lists

**Watching** and **Next to buy** are your own lists; Kai never adds to them or names a fund. Move an item from Watching to Next to buy. Once you have bought it yourself, press **I bought it** and record the account, date, units and price.

## Placing an order yourself

Open a holding and press **Prepare order**, or press **Prepare order** next to something under **Next to buy** in **Lists**. Kai fills in the order: buy or sell, how many and the order type. For a sale it shows which tax lots go first and the short-term or long-term gain on each, at the last price. Change anything you like.

When a connected broker can take the order, press **Place with** and the broker's name, then enter your Jizza password. Kai asks for it on every order. The order goes to your broker, and Kai keeps the broker's reply. Your holdings change only when the next sync shows the trade filled, so Kai never runs ahead of your broker.

Zerodha's hosted Kite server only reads your account, so it can't place orders. Placing needs your own Kite Connect key and a copy of Zerodha's Kite server that you run yourself. Without that, Kai shows **Open in my broker** to place it there, and **I bought it** to record the trade afterwards. Mutual fund orders always go through your fund platform.

Kai suggests what kind of investment fits a goal, such as a large-cap index fund. It never names a fund for you to buy.
