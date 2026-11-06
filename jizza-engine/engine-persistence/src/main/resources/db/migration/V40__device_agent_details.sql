-- Settings › Devices shows each agent like a list of installed agents: the machine's host name
-- (sent by the app when it enrolls; older apps leave it empty) and whether the owner wants the
-- agent app to update itself. "if not exists" because a database that ran this branch before it
-- was renumbered (it was V32) already has both columns.
alter table devices add column if not exists hostname varchar;
alter table devices add column if not exists auto_update boolean not null default true;
