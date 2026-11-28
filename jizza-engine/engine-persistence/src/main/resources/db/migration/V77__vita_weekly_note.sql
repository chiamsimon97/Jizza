-- Vita Phase 4 (spec 2.6, 5.9, 5.12): the weekly health note per person, kept once it is sent so
-- it goes out once a week and can be read again. Every sentence carries the ids of its rows.
create table vita_weekly_note (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  week_start date not null,
  note jsonb not null,
  sent_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_weekly_note_monday check (extract(isodow from week_start) = 1)
);
create unique index vita_weekly_note_one on vita_weekly_note (user_id, person_id, week_start);

-- Correlations (spec 5.9). Both are product choices the user can change, not clinical figures:
-- how many journal days a person needs before Vita compares anything, and how strong a rank
-- correlation (Spearman's rho, either sign) must be before Vita says one thing "goes with" another.
insert into vita_config (key, scope, value) values
('insights.min_journal_days', 'system', '{"days": 21}'::jsonb),
('insights.min_strength', 'system', '{"rho": 0.4}'::jsonb);
