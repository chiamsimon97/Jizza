-- Vita Health, Phase 2b: health insurance and claims (spec 4.2, 4.3). Vita owns the health kinds of
-- Kai's fin_insurance_policy (health, super_topup, critical_illness, personal_accident and the new
-- government_scheme); health-only fields go in vita_policy_detail so the shared row stays generic.

-- Government cover such as PM-JAY, CGHS, ECHS and ESIC. Forward only: every earlier kind stays.
alter table fin_insurance_policy drop constraint fin_insurance_kind_check;
alter table fin_insurance_policy add constraint fin_insurance_kind_check check (kind in ('term', 'health',
  'super_topup', 'personal_accident', 'critical_illness', 'vehicle', 'home', 'endowment', 'ulip',
  'government_scheme'));

-- One per policy. The policy and card numbers are stored encrypted, with the last four digits
-- beside them for the masked view. The cashless card is one of the person's Vita documents, kept
-- in the encrypted file store.
create table vita_policy_detail (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  policy_id uuid not null references fin_insurance_policy(id) on delete cascade,
  policy_number_enc bytea,
  policy_number_last4 varchar(4),
  card_number_enc bytea,
  card_number_last4 varchar(4),
  tpa_name varchar(120),
  cashless_card_document_id uuid references vita_document(id) on delete set null,
  room_rent_limit numeric(18,2),
  copay_pct numeric(5,2),
  waiting_periods jsonb,
  network_hospitals_note varchar(2000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_policy_detail_one unique (policy_id),
  constraint vita_policy_detail_copay_check check (copay_pct is null or copay_pct between 0 and 100),
  constraint vita_policy_detail_room_check check (room_rent_limit is null or room_rent_limit >= 0)
);
create index vita_policy_detail_user_idx on vita_policy_detail (user_id);

-- A claim against a policy for one covered person's stay. Kai reads only the totals: "used this
-- policy year" is the paid amount of settled claims whose stay falls in that year.
create table vita_claim (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  policy_id uuid not null references fin_insurance_policy(id) on delete cascade,
  event_id uuid references vita_event(id) on delete set null,
  visit_id uuid references vita_visit(id) on delete set null,
  admitted_on date not null,
  discharged_on date,
  amount_claimed numeric(18,2),
  amount_paid numeric(18,2),
  status varchar(10) not null default 'draft',
  document_ids uuid[],
  notes varchar(2000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_claim_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_claim_status_check check (status in ('draft', 'filed', 'query', 'settled', 'rejected')),
  constraint vita_claim_dates_check check (discharged_on is null or discharged_on >= admitted_on),
  constraint vita_claim_amounts_check check ((amount_claimed is null or amount_claimed >= 0)
    and (amount_paid is null or amount_paid >= 0))
);
create index vita_claim_person_idx on vita_claim (user_id, person_id, admitted_on);
create index vita_claim_policy_idx on vita_claim (policy_id, status);
