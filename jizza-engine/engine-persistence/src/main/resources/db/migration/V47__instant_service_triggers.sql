-- Instant triggers: a service calls Jizza at its own address when something happens, instead of
-- Jizza polling a list. A webhook trigger needs no API to poll, so the API becomes optional.
alter table service_triggers alter column api_id drop not null;
alter table service_triggers add column endpoint_token varchar unique;
-- Encrypted; when set, each delivery must carry an HMAC-SHA256 signature of its body.
alter table service_triggers add column signing_secret bytea;
alter table service_triggers add column signature_header varchar;
alter table service_triggers add column signature_prefix varchar;
alter table service_triggers add column last_received_at timestamptz;

-- A workflow can start whenever one of its owner's service triggers fires.
alter table workflows add column service_trigger_id uuid references service_triggers(id) on delete set null;
create index workflows_service_trigger_idx on workflows (service_trigger_id) where service_trigger_id is not null;
