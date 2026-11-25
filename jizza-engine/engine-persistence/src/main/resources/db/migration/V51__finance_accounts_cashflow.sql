-- Kai Portfolio, Phase 1a (1 of 3): accounts, assets, income, budget and the import tables.
--
-- Every row is personal: user_id cascades from users, so Jizza's hard delete clears it, and
-- person_id names whose money it is (the user or someone they manage). Money is numeric(18,2),
-- units numeric(20,6), percentages numeric(7,4) as whole numbers (12.5 means 12.5%). The import
-- tables are created now so the cash-flow figures have one home; Phase 1c fills them.

create table fin_account (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  is_joint boolean not null default false,
  owner_share_pct numeric(7,4) not null default 100,
  joint_user_ids uuid[] not null default '{}',
  joint_label varchar(80),
  kind varchar(20) not null,
  provider varchar(60),
  nickname varchar(80),
  last4 varchar(4),
  currency varchar(3) not null default 'INR',
  count_in_net_worth boolean not null default true,
  source varchar(10) not null default 'manual',
  connection_id uuid references connections(id) on delete set null,
  capabilities varchar(40)[] not null default '{}',
  nominee_status varchar(12) not null default 'unknown',
  is_emergency boolean not null default false,
  status varchar(10) not null default 'active',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_account_kind_check check (kind in ('savings', 'current', 'credit_card', 'demat',
    'mf_folio', 'retirement', 'deposit', 'wallet', 'cash', 'us_broker', 'other')),
  constraint fin_account_source_check check (source in ('manual', 'statement', 'api')),
  constraint fin_account_nominee_check
    check (nominee_status in ('nominated', 'opted_out', 'missing', 'unknown')),
  constraint fin_account_status_check check (status in ('active', 'closed')),
  constraint fin_account_share_check check (owner_share_pct > 0 and owner_share_pct <= 100)
);
create index fin_account_user_idx on fin_account (user_id);
create index fin_account_person_idx on fin_account (person_id);

create table fin_asset (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  account_id uuid references fin_account(id) on delete set null,
  kind varchar(20) not null,
  name varchar(120) not null,
  asset_class varchar(10) not null,
  sub_category varchar(40),
  value numeric(18,2) not null default 0,
  value_as_of date,
  rate numeric(9,6),
  maturity_date date,
  grams numeric(12,3),
  purity_karat int,
  is_emergency boolean not null default false,
  is_estimate boolean not null default false,
  pledged boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_asset_kind_check check (kind in ('savings_balance', 'fd', 'rd', 'epf', 'ppf',
    'nps', 'ssy', 'post_office', 'gold_physical', 'gold_digital', 'property', 'policy_surrender',
    'chit', 'cash', 'vehicle', 'other')),
  constraint fin_asset_class_check check (asset_class in ('cash', 'debt', 'equity', 'gold', 'other')),
  constraint fin_asset_purity_check check (purity_karat is null or purity_karat between 1 and 24)
);
create index fin_asset_user_idx on fin_asset (user_id);
create index fin_asset_person_kind_idx on fin_asset (person_id, kind);

-- One row per month (and after each import). person_id null is the whole profile.
create table fin_net_worth_snapshot (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid references fin_person(id) on delete cascade,
  as_of date not null,
  assets numeric(18,2) not null,
  liabilities numeric(18,2) not null,
  breakdown jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_net_worth_snapshot_one unique nulls not distinct (user_id, person_id, as_of)
);
create index fin_net_worth_snapshot_user_idx on fin_net_worth_snapshot (user_id);

create table fin_category (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  name varchar(60) not null,
  bucket varchar(10) not null,
  is_essential boolean not null default false,
  system_key varchar(40),
  hidden boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_category_bucket_check
    check (bucket in ('need', 'want', 'save', 'income', 'transfer')),
  constraint fin_category_name_unique unique (user_id, name)
);
create index fin_category_user_idx on fin_category (user_id);
create unique index fin_category_system_key on fin_category (user_id, system_key)
  where system_key is not null;

create table fin_income_source (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  kind varchar(10) not null default 'salary',
  gross_monthly numeric(18,2),
  take_home_monthly numeric(18,2) not null default 0,
  basic_monthly numeric(18,2),
  hra_monthly numeric(18,2),
  stability varchar(10) not null default 'stable',
  next_hike_month date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_income_source_kind_check
    check (kind in ('salary', 'business', 'rent', 'pension', 'interest', 'other')),
  constraint fin_income_source_stability_check check (stability in ('stable', 'variable'))
);
create index fin_income_source_user_idx on fin_income_source (user_id);

create table fin_expense_budget (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  category_id uuid not null references fin_category(id) on delete cascade,
  monthly_amount numeric(18,2) not null default 0,
  source varchar(10) not null default 'manual',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_expense_budget_source_check check (source in ('manual', 'import')),
  constraint fin_expense_budget_one unique (user_id, category_id)
);
create index fin_expense_budget_user_idx on fin_expense_budget (user_id);

create table fin_import_batch (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  account_id uuid references fin_account(id) on delete set null,
  kind varchar(20) not null,
  file_id uuid references files(id) on delete set null,
  content_hash varchar(64) not null,
  mapping jsonb not null default '{}'::jsonb,
  status varchar(12) not null default 'parsed',
  stats jsonb not null default '{}'::jsonb,
  error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_import_batch_kind_check check (kind in ('bank_csv', 'bank_pdf', 'cas_pdf',
    'broker_holdings', 'broker_tradebook')),
  constraint fin_import_batch_status_check
    check (status in ('parsed', 'needs_review', 'committed', 'failed', 'undone')),
  constraint fin_import_batch_once unique (user_id, content_hash)
);
create index fin_import_batch_user_idx on fin_import_batch (user_id);

create table fin_transaction (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  account_id uuid not null references fin_account(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  import_batch_id uuid references fin_import_batch(id) on delete cascade,
  txn_date date not null,
  amount numeric(18,2) not null,
  direction varchar(3) not null,
  description text,
  kind varchar(10) not null,
  category_id uuid references fin_category(id) on delete set null,
  instrument_id uuid,
  units numeric(20,6),
  price numeric(18,4),
  dedupe_key varchar(64) not null,
  schedule_instance_id uuid,
  needs_mapping boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_transaction_direction_check check (direction in ('in', 'out')),
  constraint fin_transaction_kind_check check (kind in ('spend', 'income', 'transfer', 'buy',
    'sell', 'dividend', 'interest', 'fee', 'emi', 'sip', 'swp', 'stp_in', 'stp_out')),
  constraint fin_transaction_once unique (account_id, dedupe_key)
);
create index fin_transaction_user_idx on fin_transaction (user_id);
create index fin_transaction_account_date_idx on fin_transaction (account_id, txn_date);

create table fin_category_rule (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  match_text varchar(120) not null,
  category_id uuid not null references fin_category(id) on delete cascade,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_category_rule_one unique (user_id, match_text)
);
create index fin_category_rule_user_idx on fin_category_rule (user_id);

-- Kai default for the prepay-or-invest rule of the debt planner (5.13). Rates are whole percents.
insert into fin_config (key, scope, value) values
('debt.prepay_or_invest', 'system',
 '{"repayAbovePct": 12, "splitFromPct": 9, "splitPrepayPct": 50, "method": "avalanche"}'::jsonb),
('cashflow.default_categories', 'system', '[
  {"key": "essentials", "name": "Essentials", "bucket": "need", "essential": true},
  {"key": "rent", "name": "Rent", "bucket": "need", "essential": true},
  {"key": "groceries", "name": "Groceries and food", "bucket": "need", "essential": true},
  {"key": "bills", "name": "Bills and utilities", "bucket": "need", "essential": true},
  {"key": "school", "name": "School fees", "bucket": "need", "essential": true},
  {"key": "medical", "name": "Medical", "bucket": "need", "essential": true},
  {"key": "transport", "name": "Transport", "bucket": "need", "essential": true},
  {"key": "lifestyle", "name": "Lifestyle and other", "bucket": "want", "essential": false},
  {"key": "shopping", "name": "Shopping", "bucket": "want", "essential": false},
  {"key": "travel", "name": "Travel", "bucket": "want", "essential": false},
  {"key": "investing", "name": "Investing (SIPs, PPF, RD)", "bucket": "save", "essential": false},
  {"key": "salary", "name": "Salary", "bucket": "income", "essential": false},
  {"key": "transfer", "name": "Transfers", "bucket": "transfer", "essential": false}
]'::jsonb);
