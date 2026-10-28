-- Settings › Devices shows each agent like a list of installed agents: the machine's host name
-- (sent by the app when it enrolls; older apps leave it empty) and whether the owner wants the
-- agent app to update itself.
alter table devices add column hostname varchar;
alter table devices add column auto_update boolean not null default true;
