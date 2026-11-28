-- Vita Health, Phase 1b part 4: reports from the person's mailbox (spec 4.2, 6). Each source is a
-- mail server added on Connectors, the sender (a lab) whose mails carry reports, and the person the
-- reports are for. Vita calls only the server's read tools; every PDF found is filed as a document
-- waiting for review, and nothing is saved from it until the user confirms. The PDF password is
-- kept encrypted in a bytea column, which the records store never returns in a row. `handled`
-- holds the fingerprints of PDFs already filed, so a mail is never handled twice even after its
-- document is removed.
create table vita_mail_source (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  server_id uuid not null references mcp_servers(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  sender varchar(200) not null,
  subject varchar(120),
  password_enc bytea,
  enabled boolean not null default true,
  last_checked_at timestamptz,
  last_status varchar(16),
  last_document_id uuid references vita_document(id) on delete set null,
  handled jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_mail_source_status_check check (last_status is null or last_status in
    ('to_review', 'nothing_new', 'needs_sign_in', 'needs_password', 'failed'))
);
create index vita_mail_source_user_idx on vita_mail_source (user_id);
