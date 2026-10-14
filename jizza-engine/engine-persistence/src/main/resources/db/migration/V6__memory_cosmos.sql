-- Memory Cosmos: what the user needs to see who can use each memory, where it came from and who
-- has used it. Ownership, origin and sharing already exist (knowledge_entries.agent_id,
-- memories.domains, memories.source); this adds read stamps, restriction, and history for notes.

-- Which agent read which memory, once per run. item_id points at memories or knowledge_entries,
-- so it has no foreign key; whoever deletes an item deletes its stamps in the same transaction.
create table memory_access (
  id bigserial primary key,
  user_id uuid not null references users(id) on delete cascade,
  item_kind varchar not null check (item_kind in ('fact','note')),
  item_id uuid not null,
  agent_id uuid references agents(id) on delete set null,
  context_kind varchar check (context_kind in ('chat_turn','task','workflow_run','agent_run')),
  context_ref varchar,
  occurred_at timestamptz not null default now()
);
-- One stamp per item, agent and run: a loop that reads the same fact a thousand times writes once.
create unique index memory_access_once on memory_access
  (item_kind, item_id, coalesce(agent_id, '00000000-0000-0000-0000-000000000000'::uuid),
   coalesce(context_ref, ''));
create index on memory_access (item_kind, item_id, occurred_at desc);
create index on memory_access (user_id);

-- Restricted: never handed to an agent, and shown to the user only after a fresh step-up.
alter table memories add column restricted boolean not null default false;
alter table knowledge_entries add column restricted boolean not null default false;
alter table knowledge_entries add column access_count int not null default 0;
alter table knowledge_entries add column last_accessed_at timestamptz;

-- Facts can now also be shared, unshared, moved between domains, and restricted.
alter table memory_history drop constraint memory_history_change_check;
alter table memory_history add constraint memory_history_change_check
  check (change in ('created','reinforced','updated','confirmed','superseded','retired','restored',
                    'shared','unshared','moved','restricted','unrestricted'));

-- What a note said, and which agent held it, before each change the user made.
create table knowledge_history (
  id uuid primary key default gen_random_uuid(),
  entry_id uuid not null references knowledge_entries(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  change varchar not null check (change in ('updated','moved','restricted','unrestricted')),
  previous_title varchar,
  previous_content text,
  previous_agent_id uuid,
  actor_type varchar not null check (actor_type in ('user','agent','system')),
  actor_id uuid,
  created_at timestamptz not null default now()
);
create index on knowledge_history (entry_id, created_at);
