-- Per-agent memory files: documents, PDFs and images the user gives one agent to know about them.
-- A file belongs to exactly one agent's namespace, like a knowledge entry. When its text can be
-- read, that text is indexed as a knowledge entry in the same namespace and linked back here.
alter table files add column agent_id uuid references agents(id) on delete cascade;
alter table files add column knowledge_entry_id uuid references knowledge_entries(id) on delete set null;
create index on files (agent_id, created_at desc);
