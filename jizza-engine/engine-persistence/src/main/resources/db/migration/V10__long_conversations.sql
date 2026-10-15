-- Long conversations: a rolling summary of what has fallen out of the model's window, and titles
-- written by the model after the first exchange ('model'), which a user rename still overrides.
alter table conversations add column summary text;
alter table conversations add column summary_through timestamptz;

alter table conversations drop constraint conversations_title_source_check;
alter table conversations add constraint conversations_title_source_check
  check (title_source in ('auto','model','user'));
