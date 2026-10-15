-- Facts found in what a connected service returned (an API call during a chat job or a workflow).
-- They are stored unconfirmed and never outrank what the user said.
alter table memories drop constraint memories_source_check;
alter table memories add constraint memories_source_check
  check (source in ('user','agent','conversation','import','tool'));
