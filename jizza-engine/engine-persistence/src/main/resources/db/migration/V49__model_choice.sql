-- Phase D: a choice of models. A person connects AI providers with their own key; each connection
-- keeps the models it offers and what each can do. One connection per provider per person.
create table llm_providers (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  provider varchar not null,
  base_url text,
  api_key bytea,
  key_hint varchar,
  enabled boolean not null default true,
  models jsonb not null default '[]'::jsonb,
  models_refreshed_at timestamptz,
  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint llm_providers_provider_check
    check (provider in ('openai', 'anthropic', 'gemini', 'openrouter', 'ollama', 'custom')),
  constraint llm_providers_one_per_provider unique (user_id, provider)
);

-- The model an agent answers with by default (null: its tier, as before).
alter table agents add column model varchar;

-- The model and depth (quick, normal, deep) a conversation was set to; null is Auto and normal.
alter table conversations add column model varchar;
alter table conversations add column depth varchar;

-- Who paid for a call: the platform's key, or the person's own. Only platform spend counts
-- towards the platform's spend ceiling.
alter table llm_usage add column paid_by varchar not null default 'platform';
