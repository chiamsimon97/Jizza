-- Kai Portfolio, Phase 1b: goals, the investment plan builder, risk profile and tax regimes.
--
-- A goal is fin_plan (the existing plans table holds life plans and stays separate; a goal may link
-- to one). A personal goal carries user_id; a family goal (Phase 5) carries workspace_id instead.
-- Until holdings are earmarked to goals (Phase 2) and SIPs become schedules (Phase 3), a goal
-- keeps what the user says is already saved for it and goes into it each month, so its funding can
-- be worked out from the start. Nothing here is bought or sold: saving the plan builder writes
-- allocation lines only.

create table fin_risk_assessment (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  questionnaire_version int not null default 1,
  capacity_score int not null,
  tolerance_score int not null,
  capacity_profile varchar(12) not null,
  tolerance_profile varchar(12) not null,
  profile varchar(12) not null,
  conflict_flag boolean not null default false,
  answers jsonb not null default '{}'::jsonb,
  facts jsonb not null default '{}'::jsonb,
  taken_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_risk_profile_check check (profile in ('conservative', 'moderate', 'aggressive')),
  constraint fin_risk_capacity_check
    check (capacity_profile in ('conservative', 'moderate', 'aggressive')),
  constraint fin_risk_tolerance_check
    check (tolerance_profile in ('conservative', 'moderate', 'aggressive')),
  constraint fin_risk_scores_check
    check (capacity_score between 5 and 15 and tolerance_score between 6 and 18)
);
create index fin_risk_assessment_user_idx on fin_risk_assessment (user_id);
create index fin_risk_assessment_person_idx on fin_risk_assessment (person_id, taken_at desc);

create table fin_tax_profile (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  tax_year varchar(7) not null,
  regime varchar(3),
  regime_compare jsonb not null default '{}'::jsonb,
  deductions jsonb not null default '{}'::jsonb,
  rent_paid_monthly numeric(18,2),
  metro boolean,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_tax_profile_regime_check check (regime is null or regime in ('new', 'old')),
  constraint fin_tax_profile_year_check check (tax_year ~ '^[0-9]{4}-[0-9]{2}$'),
  constraint fin_tax_profile_one unique (person_id, tax_year)
);
create index fin_tax_profile_user_idx on fin_tax_profile (user_id);

create table fin_plan (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references users(id) on delete cascade,
  workspace_id uuid references workspaces(id) on delete cascade,
  owner_person_id uuid references fin_person(id) on delete cascade,
  life_plan_id uuid references plans(id) on delete set null,
  type varchar(12) not null,
  name varchar(80) not null,
  target_date date not null,
  target_amount_today numeric(18,2) not null default 0,
  target_months numeric(5,1),
  inflation_key varchar(40) not null default 'cpi',
  priority_tier varchar(12) not null default 'important',
  required_probability numeric(7,4),
  risk_profile varchar(12),
  execution_mode varchar(8) not null default 'observe',
  status varchar(10) not null default 'active',
  dividend_policy varchar(12) not null default 'reinvest',
  successor_plan_id uuid references fin_plan(id) on delete set null,
  colour varchar(16),
  -- Until Phase 2 and 3: what is already saved for the goal and what goes in each month.
  current_value numeric(18,2) not null default 0,
  monthly_contribution numeric(18,2) not null default 0,
  stepup_pct numeric(7,4) not null default 0,
  inputs jsonb not null default '{}'::jsonb,
  created_via varchar(12) not null default 'wizard',
  achieved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_plan_owner_check check ((user_id is null) <> (workspace_id is null)),
  constraint fin_plan_type_check check (type in ('emergency', 'retirement', 'education', 'home',
    'car', 'travel', 'wedding', 'wealth', 'custom', 'general', 'unassigned')),
  constraint fin_plan_inflation_check check (inflation_key in ('cpi', 'education', 'car', 'none')),
  constraint fin_plan_priority_check
    check (priority_tier in ('survival', 'important', 'aspirational')),
  constraint fin_plan_risk_check
    check (risk_profile is null or risk_profile in ('conservative', 'moderate', 'aggressive')),
  constraint fin_plan_mode_check check (execution_mode in ('observe', 'propose')),
  constraint fin_plan_status_check
    check (status in ('draft', 'active', 'paused', 'achieved', 'archived')),
  constraint fin_plan_dividend_check check (dividend_policy in ('reinvest', 'sweep', 'fund_other')),
  constraint fin_plan_created_via_check check (created_via in ('wizard', 'quick_start', 'system')),
  constraint fin_plan_amounts_check check (target_amount_today >= 0 and current_value >= 0
    and monthly_contribution >= 0 and stepup_pct between 0 and 100)
);
create index fin_plan_user_idx on fin_plan (user_id);
create index fin_plan_workspace_idx on fin_plan (workspace_id);
create unique index fin_plan_one_unassigned on fin_plan (user_id, owner_person_id)
  where type = 'unassigned';
create unique index fin_plan_one_emergency on fin_plan (user_id)
  where type = 'emergency' and status in ('draft', 'active', 'paused');

create table fin_plan_contributor (
  id uuid primary key default gen_random_uuid(),
  plan_id uuid not null references fin_plan(id) on delete cascade,
  contributor_user_id uuid not null references users(id) on delete cascade,
  person_id uuid references fin_person(id) on delete set null,
  share_pct numeric(7,4) not null,
  effective_from date not null default current_date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_plan_contributor_share_check check (share_pct > 0 and share_pct <= 100),
  constraint fin_plan_contributor_one unique (plan_id, contributor_user_id, effective_from)
);
create index fin_plan_contributor_plan_idx on fin_plan_contributor (plan_id);

create table fin_investment_budget (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid references fin_person(id) on delete cascade,
  lumpsum numeric(18,2) not null default 0,
  monthly_amount numeric(18,2) not null default 0,
  frequency varchar(10) not null default 'monthly',
  stepup_pct numeric(7,4) not null default 0,
  topup_month int,
  topup_amount numeric(18,2),
  -- The plan builder's chips and the nodes the user set by hand: {age, riskProfile, edits[]}.
  choices jsonb not null default '{}'::jsonb,
  valid_from date not null default current_date,
  saved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_investment_budget_frequency_check check (frequency in ('monthly', 'quarterly')),
  constraint fin_investment_budget_topup_check check (topup_month is null or topup_month between 1 and 12),
  constraint fin_investment_budget_amounts_check
    check (lumpsum >= 0 and monthly_amount >= 0 and stepup_pct between 0 and 100)
);
create index fin_investment_budget_user_idx on fin_investment_budget (user_id);
create unique index fin_investment_budget_one on fin_investment_budget (user_id)
  where person_id is null;

create table fin_allocation_line (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  budget_id uuid not null references fin_investment_budget(id) on delete cascade,
  plan_id uuid references fin_plan(id) on delete set null,
  node_key varchar(80) not null,
  bucket varchar(5) not null,
  asset_class varchar(10) not null,
  sub_category varchar(40) not null,
  instrument_id uuid,
  monthly_amount numeric(18,2) not null default 0,
  lumpsum_amount numeric(18,2) not null default 0,
  pct numeric(7,4) not null default 0,
  source varchar(8) not null,
  source_label varchar(80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_allocation_line_bucket_check check (bucket in ('short', 'mid', 'long')),
  constraint fin_allocation_line_class_check
    check (asset_class in ('cash', 'debt', 'equity', 'gold', 'other')),
  constraint fin_allocation_line_source_check
    check (source in ('system', 'age', 'horizon', 'risk', 'goal', 'user'))
);
create index fin_allocation_line_user_idx on fin_allocation_line (user_id);
create index fin_allocation_line_budget_idx on fin_allocation_line (budget_id);

create table fin_projection_run (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references users(id) on delete cascade,
  workspace_id uuid references workspaces(id) on delete cascade,
  plan_id uuid not null references fin_plan(id) on delete cascade,
  method varchar(12) not null,
  funded_pct numeric(9,4),
  required_sip numeric(18,2),
  success_prob numeric(7,4),
  p10 numeric(18,2),
  p50 numeric(18,2),
  assumptions jsonb not null default '{}'::jsonb,
  run_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_projection_run_owner_check check ((user_id is null) <> (workspace_id is null)),
  constraint fin_projection_run_method_check check (method in ('deterministic', 'monte_carlo'))
);
create index fin_projection_run_user_idx on fin_projection_run (user_id);
create index fin_projection_run_plan_idx on fin_projection_run (plan_id, run_at desc);

-- Kai defaults for Phase 1b. Planning choices, not regulation; the user can change each one.
-- Education inflation: the spec gives 10-12%; Kai plans with the top of the range so the target
-- is not short. The goal library now carries numbers the goal wizard pre-fills; the years are
-- starting points the user edits.
delete from fin_config where scope = 'system' and key in ('inflation.profiles', 'goals.library');
insert into fin_config (key, scope, value) values
('inflation.profiles', 'system', '{"cpi": 6, "educationMin": 10, "educationMax": 12, "education": 12, "car": 5, "none": 0}'::jsonb),
('goals.library', 'system', '[
  {"type": "emergency", "name": "Emergency fund", "priority": "survival", "inflation": "none", "years": 1, "rule": "essentialMonths", "months": 6},
  {"type": "retirement", "name": "Retirement", "priority": "important", "inflation": "cpi", "rule": "yearsOfExpenses", "multiple": 20, "earlyMultiple": 25, "earlyBeforeAge": 55, "retireAge": 60},
  {"type": "education", "name": "Child education", "priority": "important", "inflation": "education", "rule": "feeTimesYears", "atChildAge": 18, "years": 15, "courseYears": 4},
  {"type": "home", "name": "Home down payment", "priority": "important", "inflation": "cpi", "rule": "shareOfPrice", "downPaymentPct": 20, "years": 5},
  {"type": "car", "name": "Car", "priority": "aspirational", "inflation": "car", "rule": "priceMinusLoan", "years": 3},
  {"type": "travel", "name": "Travel", "priority": "aspirational", "inflation": "cpi", "rule": "amount", "years": 1},
  {"type": "wedding", "name": "Wedding", "priority": "aspirational", "inflation": "cpi", "rule": "amount", "years": 3},
  {"type": "custom", "name": "My goal", "priority": "aspirational", "inflation": "cpi", "rule": "amount", "years": 5}
]'::jsonb);

-- Tax figures for the old regime and the cess. Every one is seeded unverified (verified_on null),
-- so each result that uses them carries "needs verification" until the owner and a CA confirm it.
-- Age 60 and over (other slabs and limits), surcharge and marginal relief are not modelled: the
-- comparison says "ask your CA" when they could apply.
delete from fin_config where scope = 'system' and key = 'tax.old_regime';
insert into fin_config (key, scope, value, effective_from, source_url) values
('tax.old_regime', 'system', '{
  "financialYear": "2026-27",
  "slabs": [
    {"upTo": 250000, "ratePct": 0}, {"upTo": 500000, "ratePct": 5}, {"upTo": 1000000, "ratePct": 20},
    {"upTo": null, "ratePct": 30}
  ],
  "standardDeduction": 50000, "rebate87A": {"maxRebate": 12500, "taxableIncomeUpTo": 500000},
  "limits": {"section80C": 150000, "section80CCD1B": 50000, "section80DSelf": 25000,
    "section80DParents": 25000, "section80DParentsSenior": 50000, "homeLoanInterest": 200000},
  "hra": {"metroPctOfBasic": 50, "otherPctOfBasic": 40, "rentAbovePctOfBasic": 10}
}'::jsonb, date '2026-04-01', null),
('tax.cess', 'system', '{"financialYear": "2026-27", "healthAndEducationPct": 4,
  "surchargeAboveIncome": 5000000}'::jsonb, date '2026-04-01', null);
