-- Kai Portfolio: statements Kai pulls from the person's email (part B). Each source is a mail
-- server added on Connectors (Gmail, say), the sender whose mails carry the statement, and where
-- the statement goes: a CAS for a person, or a bank statement for one bank account. Kai calls only
-- the server's read tools, and every statement found lands as an import the person reviews
-- before anything is saved. The PDF password is kept encrypted (envelope encryption) in a bytea
-- column, which the finance store never returns in a row.
create table fin_mail_source (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  server_id uuid not null references mcp_servers(id) on delete cascade,
  kind varchar(8) not null,
  person_id uuid references fin_person(id) on delete cascade,
  account_id uuid references fin_account(id) on delete cascade,
  sender varchar(200) not null,
  subject varchar(120),
  password_enc bytea,
  enabled boolean not null default true,
  last_checked_at timestamptz,
  last_status varchar(16),
  last_batch_id uuid references fin_import_batch(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_mail_source_kind_check check (
    (kind = 'cas' and person_id is not null and account_id is null)
    or (kind = 'bank' and account_id is not null)),
  constraint fin_mail_source_status_check check (last_status is null or last_status in
    ('to_review', 'nothing_new', 'needs_sign_in', 'needs_password', 'failed'))
);
create index fin_mail_source_user_idx on fin_mail_source (user_id);
