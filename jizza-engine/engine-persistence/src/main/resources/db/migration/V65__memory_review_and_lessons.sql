-- Memory that learns after the work, with the user deciding what is kept.
--
--   * History rows name the run that made the change (a chat turn, a task, a workflow run, or the
--     memory review), so a passport says "changed by Kai in a chat", not only "by Kai".
--   * Lessons: an agent's how-to notes ("this site's Search button stays disabled until a date is
--     picked"). They are knowledge entries of kind 'lesson' in the agent's own namespace, about one
--     tool, site, workflow or nothing in particular. Only the user creates them, usually by
--     approving a suggestion from the memory review; an agent never writes one.
--   * The memory review: once a night it reads the day's chats, failed tool calls and failed
--     workflow steps, and proposes changes. Nothing changes until the user approves a proposal.

alter table memory_history add column context_ref varchar;
alter table knowledge_history add column context_ref varchar;

-- Agents' own writes to their notes are kept in the note's history too, not only the user's.
alter table knowledge_history drop constraint if exists knowledge_history_change_check;
alter table knowledge_history add constraint knowledge_history_change_check
  check (change in ('created','updated','moved','restricted','unrestricted'));

alter table knowledge_entries add column kind varchar not null default 'note'
  check (kind in ('note','lesson'));
-- What a lesson is about: 'tool:<tool name>', 'site:<host>', 'workflow:<id>' or 'general'.
alter table knowledge_entries add column about_key varchar;
-- The same in the user's words: "IRCTC website", "Zerodha holdings".
alter table knowledge_entries add column about_label varchar;
-- How many times what the lesson is about went wrong before it was learned.
alter table knowledge_entries add column occurrences int not null default 0;
create index knowledge_entries_agent_kind on knowledge_entries (agent_id, kind);

alter table memory_settings add column review_enabled boolean not null default true;
alter table memory_settings add column last_reviewed_at timestamptz;

-- One pass of the memory review over one user's recent activity.
create table memory_reviews (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references users(id) on delete cascade,
  trigger        varchar not null check (trigger in ('nightly','manual')),
  window_start   timestamptz not null,
  window_end     timestamptz not null,
  status         varchar not null check (status in ('running','done','skipped','failed')),
  -- Why it was skipped or failed, in plain words.
  detail         text,
  proposals      int not null default 0,
  input_tokens   int not null default 0,
  output_tokens  int not null default 0,
  started_at     timestamptz not null default now(),
  finished_at    timestamptz
);
create index memory_reviews_user on memory_reviews (user_id, started_at desc);

-- A change the review suggests. Applied only when the user approves it.
create table memory_proposals (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references users(id) on delete cascade,
  review_id    uuid references memory_reviews(id) on delete set null,
  kind         varchar not null check (kind in ('merge','confirm','retire','update','lesson')),
  status       varchar not null default 'open'
               check (status in ('open','approved','dismissed','expired','stale')),
  -- What would change, in one line, and why the review thinks so.
  summary      text not null,
  reason       text,
  -- The change itself: fact ids, new wording, the agent and subject of a lesson.
  payload      jsonb not null default '{}'::jsonb,
  -- What it is based on: chats, failed tool calls, failed workflow steps (ids and short labels).
  evidence     jsonb not null default '[]'::jsonb,
  -- How many times it happened in the evidence.
  occurrences  int not null default 1,
  -- Same kind, same targets, same wording: a dismissed proposal is not raised again.
  fingerprint  varchar not null,
  -- The fact or lesson the approval created or changed.
  result_ref   varchar,
  created_at   timestamptz not null default now(),
  decided_at   timestamptz
);
create index memory_proposals_user_status on memory_proposals (user_id, status, created_at desc);
create index memory_proposals_fingerprint on memory_proposals (user_id, fingerprint);
