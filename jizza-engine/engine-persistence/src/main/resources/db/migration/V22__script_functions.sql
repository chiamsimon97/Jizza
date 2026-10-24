-- Functions: a few lines of Jizza script kept under their own page, with named, typed inputs and a
-- return type. Workflows run them from a Script step, and functions call each other by name
-- (fn.<name>(…)), so a name is unique per person. return_fields lists a map's keys, nested ones
-- dotted ([{path, type}]), for the steps after it to map.
create table script_functions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  name varchar(64) not null,
  description text,
  inputs jsonb not null default '[]',
  script text not null,
  return_type varchar not null default 'any'
    check (return_type in ('text','number','boolean','date','list','map','any')),
  return_fields jsonb not null default '[]',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, name)
);
