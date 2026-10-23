-- The Notifications page as an inbox: an item can be filed away once dealt with, put off until
-- later, and keeps the note the user gave when declining or asking for changes.
alter table notifications add column archived_at timestamptz;
alter table notifications add column snoozed_until timestamptz;
alter table notifications add column response_note text;

create index if not exists idx_notifications_user_created on notifications (user_id, created_at desc);
