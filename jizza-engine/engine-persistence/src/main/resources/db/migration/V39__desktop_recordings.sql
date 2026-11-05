-- Recordings made in Windows apps (hotkey on the Jizza Windows agent) share the web recording
-- tables: same review, same save into a RECORDED workflow. A desktop recording has no start URL.
alter table web_recordings
  add column kind varchar(12) not null default 'web' check (kind in ('web','desktop')),
  add column app varchar(200),
  alter column start_url drop not null;
