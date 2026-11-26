-- Kai Portfolio, Phase 2 (1 of 3): instruments, prices, exchange rates and corporate actions.
--
-- Shared reference data: an instrument, its price on a date and an exchange rate are the same for
-- everyone, so these rows carry no user_id. Every price has the date it is for; nothing is valued
-- without one. A corporate action the user enters by hand is theirs alone (user_id set): it is
-- applied to their own lots and earmarks and never to anyone else's.

create table fin_instrument (
  id uuid primary key default gen_random_uuid(),
  isin varchar(12),
  symbol varchar(40),
  exchange varchar(10),
  name varchar(160) not null,
  type varchar(10) not null,
  asset_class varchar(10) not null default 'other',
  sub_category varchar(40),
  cap_bucket varchar(6),
  bucket_as_of date,
  currency varchar(3) not null default 'INR',
  amfi_code varchar(12),
  tax_class varchar(14) not null default 'other',
  exit_load jsonb,
  look_through jsonb,
  look_through_as_of date,
  -- Who typed it in, if a user did: only they may correct it, since everyone shares the row.
  added_by uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_instrument_type_check check (type in ('stock', 'etf', 'mf', 'bond', 'sgb', 'reit',
    'us_stock', 'us_etf', 'other')),
  constraint fin_instrument_class_check
    check (asset_class in ('cash', 'debt', 'equity', 'gold', 'other')),
  constraint fin_instrument_cap_check check (cap_bucket is null or cap_bucket in ('large', 'mid', 'small')),
  constraint fin_instrument_tax_check check (tax_class in ('equity', 'specified_debt', 'other')),
  constraint fin_instrument_isin_check check (isin is null or isin ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$')
);
create unique index fin_instrument_isin on fin_instrument (isin) where isin is not null;
create unique index fin_instrument_symbol on fin_instrument (exchange, symbol)
  where exchange is not null and symbol is not null;
create index fin_instrument_amfi on fin_instrument (amfi_code) where amfi_code is not null;
create index fin_instrument_name on fin_instrument (lower(name));

-- A public price (the AMFI feed) has no user_id and is everyone's. A price read from a user's own
-- file or typed in by them has their user_id and values only their holdings: one person's file
-- never moves anyone else's figures.
create table fin_price (
  id bigserial primary key,
  instrument_id uuid not null references fin_instrument(id) on delete cascade,
  user_id uuid references users(id) on delete cascade,
  price_date date not null,
  price numeric(18,4) not null,
  source varchar(40) not null,
  created_at timestamptz not null default now(),
  constraint fin_price_positive check (price > 0)
);
create unique index fin_price_one on fin_price
  (instrument_id, price_date, coalesce(user_id, '00000000-0000-0000-0000-000000000000'::uuid));

create table fin_fx_rate (
  pair varchar(7) not null,
  rate_date date not null,
  rate numeric(18,6) not null,
  source varchar(40) not null,
  created_at timestamptz not null default now(),
  primary key (pair, rate_date),
  constraint fin_fx_rate_pair_check check (pair ~ '^[A-Z]{3}/[A-Z]{3}$'),
  constraint fin_fx_rate_positive check (rate > 0)
);

create table fin_corporate_action (
  id uuid primary key default gen_random_uuid(),
  -- Who entered it by hand (applied to their holdings only); null for a detected, shared action.
  user_id uuid references users(id) on delete cascade,
  instrument_id uuid not null references fin_instrument(id) on delete cascade,
  kind varchar(10) not null,
  ex_date date not null,
  ratio_num numeric(12,4),
  ratio_den numeric(12,4),
  new_instrument_id uuid references fin_instrument(id) on delete set null,
  cash_per_unit numeric(18,4),
  source varchar(8) not null default 'manual',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_corporate_action_kind_check
    check (kind in ('split', 'bonus', 'merger', 'demerger', 'buyback')),
  constraint fin_corporate_action_source_check check (source in ('manual', 'detected')),
  constraint fin_corporate_action_ratio_check check ((ratio_num is null and ratio_den is null)
    or (ratio_num > 0 and ratio_den > 0))
);
create index fin_corporate_action_instrument_idx on fin_corporate_action (instrument_id, ex_date);
create index fin_corporate_action_user_idx on fin_corporate_action (user_id);
