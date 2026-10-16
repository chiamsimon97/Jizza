-- Hardened sign-in: hashed session tokens, two-factor that cannot be replayed or downgraded,
-- usable one-time recovery codes, and the devices a user has signed in from.

-- The session cookie now carries a random 256-bit token and the row keeps only its SHA-256, so a
-- copy of the database is not a set of live sessions. Rows from before this have no token and are
-- simply never matched again: everyone signs in once more after the upgrade.
alter table sessions add column token_hash text;
create unique index sessions_token_hash_idx on sessions (token_hash) where token_hash is not null;
-- Whether this session passed the second factor (or enrolled it). With two-factor required, a
-- session without it can only reach the enrolment endpoints.
alter table sessions add column mfa_satisfied boolean not null default false;
-- Consecutive wrong step-up attempts; too many and the session is revoked.
alter table sessions add column step_up_failures int not null default 0;

-- A new authenticator is staged here until a code from it is proven, so starting enrolment again
-- never switches off the factor that is already protecting the account.
alter table users add column mfa_pending_secret bytea;
-- The last TOTP time step accepted. A code is only good once, even inside its 30-second window.
alter table users add column mfa_last_step bigint;
alter table users add column mfa_enabled_at timestamptz;

-- One row per recovery code, each hashed on its own and spent on use. (users.recovery_hash held a
-- single hash of all codes joined, which could never verify an individual code.)
create table mfa_recovery_codes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  code_hash text not null,
  used_at timestamptz,
  created_at timestamptz not null default now()
);
create index mfa_recovery_codes_user_idx on mfa_recovery_codes (user_id);
update users set recovery_hash = null;

-- Devices that have signed in, so a sign-in from a new one can be flagged to the owner.
create table user_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  fingerprint text not null,
  label text,
  last_ip inet,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  unique (user_id, fingerprint)
);
