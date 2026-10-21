-- A standing "watch this" request from ATLAS (docs §15, §44). `delivery_ref` is a technical
-- pointer, never a full personal profile — ATHENA doesn't need to know why the watch matters.
create table athena.subscriptions (
    id uuid primary key default gen_random_uuid(),
    subscription_type text not null,
    entity_ref jsonb not null,
    filter jsonb not null default '{}'::jsonb,
    delivery_ref text,
    active boolean not null default true,
    expires_at timestamptz,
    last_checked_at timestamptz,
    created_at timestamptz not null default now()
);

create index idx_subscriptions_active on athena.subscriptions(active) where active = true;

revoke all on athena.subscriptions from anon, authenticated;
