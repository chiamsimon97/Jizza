-- Kai Portfolio, Phase 0: the foundation every later finance phase builds on.
--
-- Each person's money lives in their own account: every personal row carries user_id and cascades
-- from users, so Jizza's hard delete clears it. There is no household table. fin_profile is the
-- per-user container; fin_person is the user ("self") plus the people they look after who have no
-- login (a child, a parent, an HUF). fin_config holds every Kai default and every tax or regulatory
-- figure, so none of them live in code. Nothing is reachable while jizza.finance.enabled is off.

create table fin_profile (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  base_currency varchar(3) not null default 'INR',
  settings jsonb not null default '{}'::jsonb,
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_profile_one_per_user unique (user_id)
);

create table fin_person (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  display_name varchar(80) not null,
  kind varchar(10) not null,
  type varchar(10) not null default 'individual',
  dob date,
  is_earning boolean not null default false,
  is_dependent boolean not null default false,
  pan_enc bytea,
  pan_last4 varchar(4),
  status varchar(12) not null default 'active',
  handed_over_to_user_id uuid references users(id) on delete set null,
  sort_order int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_person_kind_check
    check (kind in ('self', 'spouse', 'child', 'parent', 'huf', 'other')),
  constraint fin_person_type_check check (type in ('individual', 'minor', 'huf')),
  constraint fin_person_status_check
    check (status in ('active', 'archived', 'deceased', 'handed_over'))
);
create index fin_person_user_idx on fin_person (user_id);
-- One "Me" per account; everyone else is someone this user manages.
create unique index fin_person_one_self on fin_person (user_id) where kind = 'self';

-- A consent note: a managed adult agreed to have their money recorded here, and how.
create table fin_consent (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  scope varchar(10) not null,
  granted_at timestamptz not null default now(),
  withdrawn_at timestamptz,
  channel varchar(40),
  evidence_file_id uuid references files(id) on delete set null,
  purge_after date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_consent_scope_check check (scope in ('view', 'manage'))
);
create index fin_consent_user_idx on fin_consent (user_id);
create index fin_consent_open_idx on fin_consent (person_id) where withdrawn_at is null;

-- Kai defaults and regulatory figures. A system row has no user_id; a user's row overrides it,
-- and "Reset to Kai default" deletes the override. Keys under tax. and regulatory. are figures
-- that need verification: until verified_on is set for their financial year, they and every
-- result that uses them carry a "needs verification" badge.
create table fin_config (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references users(id) on delete cascade,
  key varchar(120) not null,
  scope varchar(10) not null,
  value jsonb not null,
  effective_from date not null default date '2000-01-01',
  effective_to date,
  source_url text,
  verified_on date,
  verified_by varchar(80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_config_scope_check check (scope in ('system', 'user')),
  constraint fin_config_scope_owner check ((scope = 'system') = (user_id is null)),
  constraint fin_config_one_row unique nulls not distinct (key, user_id, effective_from)
);
create index fin_config_user_idx on fin_config (user_id);

-- Kai defaults (planning choices, not regulation). Percentages are whole numbers: 6 means 6%.
insert into fin_config (key, scope, value) values
('health.checks', 'system', '{
  "emergencyFund": {"weight": 20, "unit": "months", "green": 6, "amber": 3},
  "debt": {"weight": 20, "unit": "pctOfGrossIncome", "green": 30, "amber": 40, "maxRatePct": 12},
  "savingsRate": {"weight": 20, "unit": "pctOfTakeHome", "green": 20, "amber": 10},
  "goalFunding": {"weight": 15, "unit": "pctFunded", "green": 90, "amber": 60},
  "protection": {"weight": 15, "unit": "gaps", "green": 0, "amber": 1, "premiumFlagPctOfIncome": 8},
  "spendingControl": {"weight": 10, "unit": "needsPctOfTakeHome", "green": 50, "amber": 60}
}'::jsonb),
('survival.order', 'system', '{
  "starterBufferMonths": 3, "fullEmergencyMonths": 6, "highInterestRatePct": 12,
  "emergencyShareOfRestPct": 50, "healthCoverFloor": 1000000, "incomeHikeToGoalsPct": 50
}'::jsonb),
('protection.term_multiple', 'system', '{"upToAge": 45, "multipleUpToAge": 15, "multipleAfter": 10}'::jsonb),
('priority.required_probability', 'system', '{"survival": 90, "important": 75, "aspirational": 50}'::jsonb),
('buckets.months', 'system', '{"shortBelow": 36, "midBelow": 84, "educationGlideMonths": 60}'::jsonb),
('allocation.horizon_by_age', 'system', '[
  {"fromAge": 0, "toAge": 29, "short": 15, "mid": 25, "long": 60},
  {"fromAge": 30, "toAge": 39, "short": 20, "mid": 30, "long": 50},
  {"fromAge": 40, "toAge": 49, "short": 20, "mid": 35, "long": 45},
  {"fromAge": 50, "toAge": 59, "short": 25, "mid": 40, "long": 35},
  {"fromAge": 60, "toAge": 200, "short": 35, "mid": 40, "long": 25}
]'::jsonb),
('allocation.short', 'system', '{"cashAndLiquid": 70, "arbitrageOrUltraShort": 30}'::jsonb),
('allocation.mid_by_risk', 'system', '{
  "conservative": {"equity": 30, "debt": 60, "gold": 10},
  "moderate": {"equity": 50, "debt": 40, "gold": 10},
  "aggressive": {"equity": 65, "debt": 25, "gold": 10}
}'::jsonb),
('allocation.long_by_age', 'system', '[
  {"fromAge": 0, "toAge": 29, "equity": 80, "equityMin": 75, "equityMax": 85, "debt": 15, "gold": 5},
  {"fromAge": 30, "toAge": 39, "equity": 70, "equityMin": 65, "equityMax": 75, "debt": 20, "gold": 10},
  {"fromAge": 40, "toAge": 49, "equity": 60, "equityMin": 50, "equityMax": 65, "debt": 30, "gold": 10},
  {"fromAge": 50, "toAge": 59, "equity": 45, "equityMin": 35, "equityMax": 50, "debt": 45, "gold": 10},
  {"fromAge": 60, "toAge": 200, "equity": 25, "equityMin": 20, "equityMax": 30, "debt": 65, "gold": 10}
]'::jsonb),
('allocation.equity_sub_by_risk', 'system', '{
  "conservative": {"largeCapOrIndex": 70, "flexiCap": 30, "midCap": 0, "smallCap": 0},
  "moderate": {"largeCapOrIndex": 50, "flexiCap": 35, "midCap": 10, "smallCap": 5},
  "aggressive": {"largeCapOrIndex": 35, "flexiCap": 25, "midCap": 25, "smallCap": 15}
}'::jsonb),
('allocation.sleeves', 'system', '{"internationalMaxPct": 10, "directStocksCapPctOfEquity": 20}'::jsonb),
('allocation.lumpsum', 'system', '{"stpMonths": 6, "stpMonthsMax": 12}'::jsonb),
('inflation.profiles', 'system', '{"cpi": 6, "educationMin": 10, "educationMax": 12, "car": 5}'::jsonb),
('returns.expected', 'system', '{"equity": 10, "debt": 6.5, "gold": 7, "cash": 5.5}'::jsonb),
('returns.monte_carlo', 'system', '{
  "volatility": {"equity": 18, "debt": 4, "gold": 15, "cash": 1},
  "correlation": {"equityDebt": 0.1, "equityGold": 0.0, "debtGold": 0.1},
  "paths": 5000
}'::jsonb),
('goals.library', 'system', '[
  {"key": "emergency", "target": "6 x essential monthly expenses", "inflation": "recomputed yearly", "horizon": "short", "priority": "survival"},
  {"key": "retirement", "target": "20 x annual expenses (25-30 x if retiring before 55)", "inflation": "cpi", "horizon": "long", "priority": "important"},
  {"key": "child_education", "target": "fee x years", "inflation": "education", "horizon": "long", "priority": "important"},
  {"key": "home_down_payment", "target": "20% of price + registration", "inflation": "cpi", "horizon": "mid", "priority": "important"},
  {"key": "car", "target": "price minus planned loan", "inflation": "car", "horizon": "short_or_mid", "priority": "aspirational"},
  {"key": "travel_or_wedding", "target": "user amount", "inflation": "cpi", "horizon": "short", "priority": "aspirational"},
  {"key": "custom", "target": "user amount", "inflation": "user", "horizon": "from target year", "priority": "user"}
]'::jsonb),
('risk.questionnaire_v1', 'system', '{
  "tolerance": {"questions": 6, "scorePerAnswer": [1, 2, 3],
    "bands": [{"max": 9, "profile": "conservative"}, {"max": 14, "profile": "moderate"}, {"max": 18, "profile": "aggressive"}]},
  "capacity": {"facts": ["incomeStability", "dependants", "emergencyFund", "debtToIncome", "yearsToNearestImportantGoal"],
    "bands": [{"max": 8, "profile": "conservative"}, {"max": 12, "profile": "moderate"}, {"max": 15, "profile": "aggressive"}]},
  "profileRule": "lower of the two"
}'::jsonb);

-- Tax and regulatory figures, as the spec states them. Every one is seeded unverified
-- (verified_on null), so the app shows "needs verification" until the owner and a CA confirm it.
insert into fin_config (key, scope, value, effective_from, source_url) values
('tax.new_regime', 'system', '{
  "financialYear": "2026-27",
  "slabs": [
    {"upTo": 400000, "ratePct": 0}, {"upTo": 800000, "ratePct": 5}, {"upTo": 1200000, "ratePct": 10},
    {"upTo": 1600000, "ratePct": 15}, {"upTo": 2000000, "ratePct": 20}, {"upTo": 2400000, "ratePct": 25},
    {"upTo": null, "ratePct": 30}
  ],
  "standardDeduction": 75000, "rebate87A": {"maxRebate": 60000, "taxableIncomeUpTo": 1200000}
}'::jsonb, date '2026-04-01',
 'https://www.businesstoday.in/personal-finance/tax/story/tax-slabs-fy-2026-27-what-budget-2026-changed-for-individual-taxpayers-and-which-regime-works-best-514044-2026-02-01'),
('tax.old_regime', 'system', '{"financialYear": "2026-27", "standardDeduction": 50000}'::jsonb,
 date '2026-04-01', null),
('tax.capital_gains', 'system', '{
  "financialYear": "2026-27",
  "equity": {"shortTermMonths": 12, "stcgPct": 20, "ltcgPct": 12.5, "ltcgExemption": 125000},
  "debtFundsBoughtFrom": "2023-04-01", "debtFundsTaxedAt": "slab", "debtFundDebtShareAbovePct": 65,
  "sttOnEquityRedemptionPct": 0.001
}'::jsonb, date '2026-04-01', null),
('regulatory.gold_loan_ltv', 'system', '{
  "tiers": [{"upTo": 250000, "capPct": 85}, {"upTo": 500000, "capPct": 80}, {"upTo": null, "capPct": 75}],
  "warnAtPct": 80, "bulletMaxTenureMonths": 12, "bulletLtvBasis": "amountRepayableAtMaturity",
  "warnDaysBeforeMaturity": 60
}'::jsonb, date '2026-04-01', null),
('regulatory.sip_failure', 'system', '{"consecutiveFailuresUpToMonthly": 3, "consecutiveFailuresQuarterlyOrLonger": 2}'::jsonb,
 date '2000-01-01', null),
('regulatory.clubbing_relationships', 'system', '{
  "clubbed": ["spouse", "minor_child", "sons_wife"], "notClubbed": ["parent", "adult_child"]
}'::jsonb, date '2000-01-01', null),
('regulatory.lrs_tcs', 'system', '{"thresholdPerYear": 1000000, "ratePctAbove": 20}'::jsonb,
 date '2000-01-01', null);
