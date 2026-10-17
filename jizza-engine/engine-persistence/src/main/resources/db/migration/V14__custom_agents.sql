-- Custom agents: people create their own specialists, arrange them in teams that report to one
-- another, and organizations share agent templates their members add to their own team. A custom
-- agent is an ordinary row in agents, run by the same runtime and judged by the same guardrails.

-- ── What an agent is ──────────────────────────────────────────────────────────
-- One column per statement, so the schema check can read each of them.
alter table agents add column is_custom boolean not null default false;
-- "When to use me": what its manager is told when choosing who to hand work to.
alter table agents add column description varchar(300);
-- draft and archived agents are always disabled; paused is enabled = false on an active agent.
alter table agents add column status varchar not null default 'active';
alter table agents add column model_tier varchar not null default 'standard';
-- Built-in memory domains (finance, health, professional) a custom agent may also read.
alter table agents add column memory_domains text[] not null default '{}';
alter table agents add column avatar jsonb;
alter table agents add column monthly_cost_limit numeric(12,4);
-- Who it reports to. Null only for Jizza.
alter table agents add column parent_agent_id uuid references agents(id) on delete set null;
alter table agents add column responsibilities text[] not null default '{}';
-- Which of its reports handles what, in the user's words; used when it hands work down.
alter table agents add column hands_off_to text;
alter table agents add column memory_from_manager boolean not null default true;
alter table agents add column origin_template_id uuid;
alter table agents add column origin_template_version int;
alter table agents add column created_at timestamptz not null default now();
alter table agents add column updated_at timestamptz not null default now();

alter table agents
  add constraint agents_status_check check (status in ('draft','active','archived'));
alter table agents
  add constraint agents_model_tier_check check (model_tier in ('standard','deep'));
alter table agents
  add constraint agents_not_own_manager check (parent_agent_id is null or parent_agent_id <> id);
create index agents_parent_idx on agents (parent_agent_id) where parent_agent_id is not null;

-- Kai, Vita and Cato report to Jizza.
update agents a set parent_agent_id = j.id
  from agents j
 where j.user_id = a.user_id and j.is_orchestrator and not a.is_orchestrator;

-- Built-ins get their use case, so every roster reads the same way.
update agents set description = case key
  when 'health' then 'Health: fitness, sleep, nutrition, symptoms, appointments, medications.'
  when 'finance' then 'Money: accounts, spending, budgets, bills, investments, tax.'
  when 'professional' then 'Work: calendar, meetings, email drafts, documents, deadlines.'
  end
 where not is_orchestrator and key in ('health','finance','professional');

-- ── Removing an agent ────────────────────────────────────────────────────────
-- Agents are archived, not deleted. When an archived agent with nothing left depending on it is
-- deleted for good, its history keeps the event but loses the link, and its own rules go with it.
alter table delegation_traces drop constraint if exists delegation_traces_from_agent_fkey;
alter table delegation_traces
  add constraint delegation_traces_from_agent_fkey
  foreign key (from_agent) references agents(id) on delete set null;
alter table delegation_traces drop constraint if exists delegation_traces_to_agent_fkey;
alter table delegation_traces
  add constraint delegation_traces_to_agent_fkey
  foreign key (to_agent) references agents(id) on delete set null;
alter table notifications drop constraint if exists notifications_source_agent_id_fkey;
alter table notifications
  add constraint notifications_source_agent_id_fkey
  foreign key (source_agent_id) references agents(id) on delete set null;
alter table autonomy_policies drop constraint if exists autonomy_policies_agent_id_fkey;
alter table autonomy_policies
  add constraint autonomy_policies_agent_id_fkey
  foreign key (agent_id) references agents(id) on delete cascade;

-- ── What changed, and when ───────────────────────────────────────────────────
create table agent_revisions (
  id uuid primary key default gen_random_uuid(),
  agent_id uuid not null references agents(id) on delete cascade,
  revision int not null,
  name varchar not null,
  description varchar(300),
  system_prompt text not null,
  model_tier varchar not null,
  changed_by_user_id uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  unique (agent_id, revision)
);

-- ── Organization agent templates ─────────────────────────────────────────────
-- An organization shares definitions, not live agents: a member adds a copy to their own team.
-- The definition holds one agent or a whole team, with tools and a guardrail profile.
create table agent_templates (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspaces(id) on delete cascade,
  name varchar(80) not null,
  description varchar(300),
  definition jsonb not null,
  status varchar not null default 'draft'
    check (status in ('draft','published','unpublished')),
  version int not null default 1,
  created_by_user_id uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index agent_templates_workspace_idx on agent_templates (workspace_id);

alter table agents
  add constraint agents_origin_template_fkey
  foreign key (origin_template_id) references agent_templates(id) on delete set null;
create index agents_origin_template_idx on agents (origin_template_id)
  where origin_template_id is not null;
