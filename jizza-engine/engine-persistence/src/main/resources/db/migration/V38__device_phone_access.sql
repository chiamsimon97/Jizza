-- What the Jizza app on a person's own phone may do with their account: read only, read and
-- answer approvals, or full (also run workflows, cancel runs and resume after Pause all).
-- Shared machines enrolled with a token have no person behind them and never use it.
alter table devices add column phone_access text not null default 'approve'
    check (phone_access in ('read', 'approve', 'full'));
