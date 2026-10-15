-- Standing instructions: what the user told Jizza to do when a situation comes up. Only the user
-- creates them (in their own chat message); nothing learned from tool output ever becomes one.
alter table memories drop constraint memories_category_check;
alter table memories add constraint memories_category_check
  check (category in ('profile','preference','relationship','finance','health','professional',
                      'project','task','general','instruction'));

-- A plan the user is working towards (a wedding, a house purchase). Its conditions are confirmed
-- facts about its memory subject, so every agent working on it is handed them by recall.
create table plans (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references users(id) on delete cascade,
  subject_id       uuid references memory_subjects(id) on delete set null,
  conversation_id  uuid references conversations(id) on delete set null,
  title            varchar(160) not null,
  status           varchar(16) not null default 'active'
                   check (status in ('active','paused','done','cancelled')),
  target_date      date,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create index plans_user on plans(user_id, status);

alter table jobs add column plan_id uuid references plans(id) on delete set null;
