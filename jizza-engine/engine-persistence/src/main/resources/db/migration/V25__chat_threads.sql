-- Chat threads. The main chat is where Jizza coordinates; a topic that belongs to one specialist
-- moves into a thread of its own, co-owned by that specialist and Jizza, which the specialist leads.
-- Each thread keeps a guide: a Markdown learning file of what the thread is for and what it has
-- learned, read at the start of every turn in it and kept up to date after each one.
-- (V25, not V24: V24 is taken by the public organization work on release/M2_3_0.)

-- One column per statement, so the schema check can read each of them.
alter table conversations add column kind varchar(8) not null default 'main';
-- The main chat a thread was opened from. Deleting the main chat deletes its threads.
alter table conversations add column parent_id uuid references conversations(id) on delete cascade;
-- The specialist that leads the thread. Null means Jizza leads it.
alter table conversations add column owner_agent_id uuid references agents(id) on delete set null;
-- What the thread is about, in one or two sentences: what decides whether a message belongs here.
alter table conversations add column topic varchar(400);
-- The thread's learning file, as Markdown.
alter table conversations add column guide text;
alter table conversations add column guide_updated_at timestamptz;
-- 'jizza' while Jizza keeps the guide, 'user' once the user has edited it by hand.
alter table conversations add column guide_source varchar(8);

alter table conversations
  add constraint conversations_kind_check check (kind in ('main','thread'));
alter table conversations
  add constraint conversations_thread_parent check (kind = 'main' or parent_id is not null);
alter table conversations
  add constraint conversations_guide_source_check check (guide_source is null or guide_source in ('jizza','user'));
create index conversations_parent_idx on conversations (parent_id) where parent_id is not null;

-- A message Jizza moves between the main chat and a thread is answered by a job of its own kind,
-- and like a typed message it waits its turn in its conversation.
alter table jobs drop constraint if exists jobs_origin_check;
alter table jobs add constraint jobs_origin_check
  check (origin in ('chat','continuation','schedule','workflow','webhook','signal','handoff','handback'));
drop index if exists jobs_one_running_turn;
create unique index jobs_one_running_turn on jobs(conversation_id)
  where status = 'running' and origin in ('chat','continuation','handoff','handback');
