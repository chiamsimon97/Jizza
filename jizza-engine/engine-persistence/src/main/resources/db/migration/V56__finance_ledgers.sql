-- Kai Portfolio, Phase 2 (2 of 3): the two ledgers.
--
-- The tax ledger is fin_holding_lot: what was bought, when and at what cost, consumed oldest
-- first (FIFO) per account when units are sold. The goal ledger is fin_earmark: which goal claims
-- how many of an account's units of an instrument (or how much of an amount-type asset such as an
-- FD). The service keeps the two equal for every person, account and instrument; any gap belongs
-- to that person's Unassigned goal. A goal never pins a lot: tax always comes from FIFO.
--
-- fin_earmark_event and fin_realized_gain are append-only: a trigger refuses any update or
-- delete, except the cascade when the user's account is hard deleted. A mistake is corrected by
-- a new row (a realised gain carries reverses_id for that), never by changing an old one.

create table fin_holding_lot (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  account_id uuid not null references fin_account(id) on delete cascade,
  instrument_id uuid not null references fin_instrument(id) on delete restrict,
  acquired_on date,
  acquired_on_known boolean not null default true,
  qty numeric(20,6) not null,
  remaining_qty numeric(20,6) not null,
  cost_per_unit numeric(18,4),
  source varchar(18) not null,
  funding_source_person_id uuid references fin_person(id) on delete set null,
  import_batch_id uuid references fin_import_batch(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_holding_lot_qty_check check (qty > 0 and remaining_qty between 0 and qty),
  constraint fin_holding_lot_date_check check (acquired_on_known = false or acquired_on is not null),
  constraint fin_holding_lot_source_check check (source in ('buy', 'sip', 'bonus', 'split',
    'switch_in', 'stp_in', 'ipo', 'gift', 'inheritance', 'demerger', 'dividend_reinvest',
    'opening_balance'))
);
create index fin_holding_lot_user_idx on fin_holding_lot (user_id);
create index fin_holding_lot_fifo_idx on fin_holding_lot (account_id, instrument_id, acquired_on);

create table fin_earmark (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  -- A family goal the owner contributes to (a pledge) arrives in Phase 5.
  plan_id uuid not null references fin_plan(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  account_id uuid references fin_account(id) on delete cascade,
  instrument_id uuid references fin_instrument(id) on delete restrict,
  asset_id uuid references fin_asset(id) on delete cascade,
  mode varchar(6) not null,
  units numeric(20,6),
  amount numeric(18,2),
  attributed_cost numeric(18,2),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_earmark_target_check check ((instrument_id is null) <> (asset_id is null)),
  constraint fin_earmark_mode_check check ((mode = 'units' and instrument_id is not null
      and account_id is not null and units is not null and units >= 0)
    or (mode = 'amount' and asset_id is not null and amount is not null and amount >= 0))
);
create index fin_earmark_user_idx on fin_earmark (user_id);
create unique index fin_earmark_units_one on fin_earmark (plan_id, account_id, instrument_id)
  where instrument_id is not null;
create unique index fin_earmark_asset_one on fin_earmark (plan_id, asset_id) where asset_id is not null;
create index fin_earmark_holding_idx on fin_earmark (account_id, instrument_id);

create table fin_earmark_event (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  -- Kept when the earmark row goes (a goal reaches zero), so the history stays whole.
  earmark_id uuid,
  plan_id uuid,
  account_id uuid,
  instrument_id uuid,
  asset_id uuid,
  kind varchar(14) not null,
  delta_units numeric(20,6),
  delta_amount numeric(18,2),
  price numeric(18,4),
  counterparty_plan_id uuid,
  proposal_id uuid,
  note varchar(200),
  occurred_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_earmark_event_kind_check check (kind in ('assign', 'reassign', 'cash_leg', 'buy',
    'sell', 'external_sell', 'corp_action', 'dividend', 'reconcile'))
);
create index fin_earmark_event_user_idx on fin_earmark_event (user_id, occurred_at desc);
create index fin_earmark_event_plan_idx on fin_earmark_event (plan_id);

create table fin_realized_gain (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  tax_year varchar(7) not null,
  instrument_id uuid not null references fin_instrument(id) on delete restrict,
  lot_id uuid,
  plan_id uuid,
  account_id uuid,
  sold_on date not null,
  qty numeric(20,6) not null,
  proceeds numeric(18,2) not null,
  cost numeric(18,2),
  gain numeric(18,2),
  -- st or lt; null when the lot's date is unknown or the holding period is not configured.
  term varchar(2),
  tax_class varchar(14) not null,
  exempt_used numeric(18,2),
  order_id uuid,
  transaction_id uuid,
  import_batch_id uuid,
  reverses_id uuid references fin_realized_gain(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_realized_gain_term_check check (term is null or term in ('st', 'lt')),
  constraint fin_realized_gain_year_check check (tax_year ~ '^[0-9]{4}-[0-9]{2}$')
);
create index fin_realized_gain_person_idx on fin_realized_gain (person_id, tax_year);
create index fin_realized_gain_user_idx on fin_realized_gain (user_id);

create or replace function fin_append_only() returns trigger language plpgsql as $$
begin
  -- A cascade from deleting the user (or their person) runs inside the foreign key's own
  -- trigger, one level deeper; only a direct change is refused.
  if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
    return old;
  end if;
  raise exception '% rows are never changed or removed', tg_table_name
    using errcode = 'check_violation';
end;
$$;

create trigger fin_earmark_event_append_only before update or delete on fin_earmark_event
  for each row execute function fin_append_only();
create trigger fin_realized_gain_append_only before update or delete on fin_realized_gain
  for each row execute function fin_append_only();
