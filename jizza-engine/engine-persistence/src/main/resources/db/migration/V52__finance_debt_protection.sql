-- Kai Portfolio, Phase 1a (2 of 3): loans and borrowings, pledges, money lent, insurance, wills.
--
-- Rates are whole percents a year (14 means 14%); effective_annual_rate is the normalised rate the
-- debt planner uses, whatever form the user typed in rate_input ("2 per 100 a month" = 24).
-- A family or friend lender's name is the owner's own note: wherever the loan is shared later it
-- reads "Family / friend" (is_private).

create table fin_liability (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  is_joint boolean not null default false,
  owner_share_pct numeric(7,4) not null default 100,
  joint_user_ids uuid[] not null default '{}',
  joint_label varchar(80),
  kind varchar(30) not null,
  lender_type varchar(12) not null default 'bank',
  lender_name varchar(120),
  is_private boolean not null default false,
  principal_taken numeric(18,2),
  outstanding numeric(18,2) not null default 0,
  rate_input varchar(80),
  effective_annual_rate numeric(9,6),
  rate_type varchar(10) not null default 'fixed',
  repayment_mode varchar(14) not null default 'emi',
  emi_or_interest numeric(18,2),
  start_date date,
  maturity_or_promised_date date,
  prepayment_penalty_pct numeric(7,4),
  tax_benefit varchar(12) not null default 'none',
  collateral_type varchar(12) not null default 'none',
  status varchar(10) not null default 'active',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_liability_kind_check check (kind in ('HOME', 'VEHICLE', 'EDUCATION', 'PERSONAL',
    'CREDIT_CARD', 'CONSUMER_EMI', 'GOLD_LOAN', 'LOAN_AGAINST_SECURITIES', 'LOAN_AGAINST_FD',
    'LOAN_AGAINST_INSURANCE', 'PPF_LOAN', 'OVERDRAFT', 'BUSINESS', 'EMPLOYER_ADVANCE',
    'FAMILY_FRIEND', 'MONEYLENDER', 'CHIT_PRIZE', 'OTHER')),
  constraint fin_liability_lender_check check (lender_type in ('bank', 'nbfc', 'card_issuer',
    'employer', 'family', 'friend', 'moneylender', 'chit_fund', 'other')),
  constraint fin_liability_rate_type_check
    check (rate_type in ('fixed', 'floating', 'flat', 'zero', 'informal')),
  constraint fin_liability_mode_check check (repayment_mode in ('emi', 'interest_only', 'bullet',
    'revolving', 'flexible')),
  constraint fin_liability_tax_check check (tax_benefit in ('none', 'section_24b', 'section_80c',
    'section_80e', 'both', 'other')),
  constraint fin_liability_collateral_check check (collateral_type in ('none', 'property',
    'vehicle', 'gold', 'silver', 'securities', 'fd', 'policy', 'other')),
  constraint fin_liability_status_check check (status in ('active', 'closed')),
  constraint fin_liability_share_check check (owner_share_pct > 0 and owner_share_pct <= 100)
);
create index fin_liability_user_idx on fin_liability (user_id);
create index fin_liability_person_status_idx on fin_liability (person_id, status);

create table fin_pledge (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  liability_id uuid not null references fin_liability(id) on delete cascade,
  asset_kind varchar(12) not null,
  asset_id uuid references fin_asset(id) on delete set null,
  instrument_id uuid,
  units numeric(20,6),
  grams numeric(12,3),
  purity_karat int,
  valuation numeric(18,2),
  valued_on date,
  ltv_cap_pct numeric(7,4),
  ltv_now_pct numeric(7,4),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_pledge_kind_check check (asset_kind in ('gold', 'silver', 'securities', 'fd',
    'policy', 'property')),
  constraint fin_pledge_purity_check check (purity_karat is null or purity_karat between 1 and 24)
);
create index fin_pledge_user_idx on fin_pledge (user_id);
create index fin_pledge_liability_idx on fin_pledge (liability_id);

create table fin_loan_payment (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  liability_id uuid not null references fin_liability(id) on delete cascade,
  kind varchar(10) not null default 'actual',
  due_or_paid_on date not null,
  amount numeric(18,2) not null,
  principal_part numeric(18,2),
  interest_part numeric(18,2),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_loan_payment_kind_check check (kind in ('actual', 'promised'))
);
create index fin_loan_payment_user_idx on fin_loan_payment (user_id);
create index fin_loan_payment_liability_idx on fin_loan_payment (liability_id, due_or_paid_on);

create table fin_loan_given (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  borrower_name varchar(120) not null,
  is_private boolean not null default true,
  amount numeric(18,2) not null,
  rate numeric(9,6),
  given_on date,
  expected_back_on date,
  status varchar(10) not null default 'on_track',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_loan_given_status_check
    check (status in ('on_track', 'overdue', 'doubtful', 'repaid'))
);
create index fin_loan_given_user_idx on fin_loan_given (user_id);

create table fin_insurance_policy (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  kind varchar(20) not null,
  provider_name varchar(120),
  sum_assured numeric(18,2) not null default 0,
  deductible numeric(18,2),
  premium numeric(18,2),
  premium_frequency varchar(10) not null default 'yearly',
  renewal_date date,
  policy_end_date date,
  employer_provided boolean not null default false,
  covered_person_ids uuid[] not null default '{}',
  surrender_value numeric(18,2),
  document_file_id uuid references files(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_insurance_kind_check check (kind in ('term', 'health', 'super_topup',
    'personal_accident', 'critical_illness', 'vehicle', 'home', 'endowment', 'ulip')),
  constraint fin_insurance_frequency_check
    check (premium_frequency in ('monthly', 'quarterly', 'yearly', 'single'))
);
create index fin_insurance_policy_user_idx on fin_insurance_policy (user_id);
create index fin_insurance_policy_person_kind_idx on fin_insurance_policy (person_id, kind);

create table fin_estate_record (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  will_status varchar(10) not null default 'none',
  will_date date,
  executor varchar(120),
  notes text,
  document_file_id uuid references files(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_estate_will_check check (will_status in ('none', 'draft', 'registered')),
  constraint fin_estate_record_one unique (person_id)
);
create index fin_estate_record_user_idx on fin_estate_record (user_id);
