-- Hybrid memory: what Jizza knows about the user and the people, accounts and plans in their life.
--
-- Two layers, both scoped by user_id and cascading from users so "forget me" still removes it all:
--   * subjects  -- the things memories are grouped around ("Priya", relation "wife"; "Priya's
--                  brokerage account", linked to its Connector), with typed relations between them;
--   * memories  -- individual facts, each about zero or more subjects (zero = the user), with a
--                  category, the agent domains allowed to see it, provenance, confidence and an
--                  embedding for meaning-based retrieval.
-- Per-agent knowledge_entries are unchanged: those stay each agent's private working notes.

create table memory_subjects (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  kind varchar not null
    check (kind in ('person','account','asset','plan','organization','place','other')),
  name varchar not null,
  relation varchar,
  aliases text[] not null default '{}',
  attributes jsonb,
  connection_id uuid references connections(id) on delete set null,
  -- Agent domains allowed to see this subject; empty = every agent.
  domains text[] not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);
create unique index memory_subjects_user_kind_name_uk on memory_subjects (user_id, kind, lower(name));
create index on memory_subjects (user_id);

create table memory_subject_relations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  from_subject_id uuid not null references memory_subjects(id) on delete cascade,
  to_subject_id uuid not null references memory_subjects(id) on delete cascade,
  relation varchar not null,
  created_at timestamptz not null default now(),
  constraint memory_subject_relations_uk unique (from_subject_id, to_subject_id, relation),
  constraint memory_subject_relations_no_self check (from_subject_id <> to_subject_id)
);
create index on memory_subject_relations (to_subject_id);

create table memories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  category varchar not null
    check (category in ('profile','preference','relationship','finance','health','professional',
                        'project','task','general')),
  -- Agent domains allowed to see this fact; empty = every agent. Enforced in the query.
  domains text[] not null default '{}',
  classification varchar not null default 'none'
    check (classification in ('none','pii','health','financial')),
  content text not null,
  -- The attribute this fact sets on its subjects; same key + same subjects = the same slot.
  fact_key varchar,
  data jsonb,
  confidence real not null default 1.0 check (confidence between 0 and 1),
  importance real not null default 0.5 check (importance between 0 and 1),
  source varchar not null check (source in ('user','agent','conversation','import')),
  source_ref varchar,
  confirmed boolean not null default false,
  status varchar not null default 'active'
    check (status in ('active','pending','superseded','retired')),
  superseded_by uuid references memories(id) on delete set null,
  content_hash varchar not null,
  embedding vector(1536),
  -- Which embedding provider produced the vector; vectors from different providers are not
  -- comparable, so search only uses rows embedded by the current one and the rest are re-embedded.
  embedding_model varchar,
  expires_at timestamptz,
  last_accessed_at timestamptz,
  access_count int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);
create index on memories (user_id, status);
create index on memories (user_id, fact_key) where fact_key is not null;
create index on memories (user_id, content_hash);
create index on memories using gin (domains);
create index on memories using hnsw (embedding vector_cosine_ops);

-- Which subjects a fact is about.
create table memory_about (
  memory_id uuid not null references memories(id) on delete cascade,
  subject_id uuid not null references memory_subjects(id) on delete cascade,
  primary key (memory_id, subject_id)
);
create index on memory_about (subject_id);

-- What a fact said before each change. Deleted with the fact: forgetting means forgetting.
create table memory_history (
  id uuid primary key default gen_random_uuid(),
  memory_id uuid not null references memories(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  change varchar not null
    check (change in ('created','reinforced','updated','confirmed','superseded','retired','restored')),
  previous_content text,
  previous_data jsonb,
  actor_type varchar not null check (actor_type in ('user','agent','system')),
  actor_id uuid,
  note text,
  created_at timestamptz not null default now()
);
create index on memory_history (memory_id, created_at);

-- The user's consent for memory.
create table memory_settings (
  user_id uuid primary key references users(id) on delete cascade,
  auto_learn boolean not null default true,
  retention_days int check (retention_days is null or retention_days > 0),
  updated_at timestamptz not null default now()
);
