-- Chat module: conversations worth listing, messages that redraw as they looked live, and jobs
-- (a chat turn, later a scheduled plan step) with the timeline the Activity panel shows.

alter table conversations add column title varchar(120);
alter table conversations add column title_source varchar(8) not null default 'auto'
  check (title_source in ('auto','user'));
alter table conversations add column pinned boolean not null default false;
alter table conversations add column archived_at timestamptz;
alter table conversations add column last_message_at timestamptz;
alter table conversations add column message_count int not null default 0;

update conversations c set
  last_message_at = (select max(m.created_at) from messages m where m.conversation_id = c.id),
  message_count   = (select count(*) from messages m where m.conversation_id = c.id),
  title = (select left(regexp_replace(m.content, '\s+', ' ', 'g'), 60) from messages m
           where m.conversation_id = c.id and m.role = 'user' order by m.created_at limit 1);

create index conversations_user_recent
  on conversations(user_id, pinned desc, coalesce(last_message_at, created_at) desc);

alter table messages drop constraint messages_role_check;
alter table messages add constraint messages_role_check check (role in ('user','assistant','event'));
alter table messages add column status varchar(12) not null default 'complete'
  check (status in ('streaming','complete','failed','cancelled'));
alter table messages add column job_id uuid;
alter table messages add column client_message_id uuid;
alter table messages add column metadata jsonb not null default '{}'::jsonb;
create unique index messages_client_id on messages(conversation_id, client_message_id)
  where client_message_id is not null;
create index messages_text_search on messages using gin (to_tsvector('simple', content));

create table jobs (
  id                    uuid primary key default gen_random_uuid(),
  user_id               uuid not null references users(id) on delete cascade,
  conversation_id       uuid references conversations(id) on delete cascade,
  origin                varchar(16) not null
                        check (origin in ('chat','continuation','schedule','workflow','webhook','signal')),
  title                 varchar(160),
  summary               text,
  status                varchar(20) not null
                        check (status in ('running','awaiting_user','completed','failed','cancelled')),
  user_message_id       uuid references messages(id) on delete set null,
  assistant_message_id  uuid references messages(id) on delete set null,
  input_tokens          int not null default 0,
  output_tokens         int not null default 0,
  started_at            timestamptz not null default now(),
  finished_at           timestamptz
);
create index jobs_conversation on jobs(conversation_id, started_at);
create index jobs_user_recent on jobs(user_id, started_at desc);
-- One reply at a time per conversation.
create unique index jobs_one_running_turn on jobs(conversation_id)
  where status = 'running' and origin in ('chat','continuation');

create table job_events (
  job_id      uuid not null references jobs(id) on delete cascade,
  seq         int not null,
  type        varchar(32) not null,
  agent_id    uuid references agents(id) on delete set null,
  agent_name  varchar(120),
  payload     jsonb not null default '{}'::jsonb,
  at          timestamptz not null default now(),
  primary key (job_id, seq)
);
