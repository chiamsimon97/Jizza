-- A doctor's target and the person's own range for the same measure are kept side by side
-- (spec 2.4: the doctor's target applies, the own range stays for when it is removed). The
-- one-live-row rule is per setter, as the service already replaces only the same setter's row.
drop index vita_usual_range_one_live;
create unique index vita_usual_range_one_live on vita_usual_range
  (user_id, person_id, coalesce(kind, ''), coalesce(lab_test_code, ''), coalesce(context, ''), coalesce(setting, ''), set_by)
  where deleted_at is null;
