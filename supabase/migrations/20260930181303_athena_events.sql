-- A detected change in the world (docs §11). Examples: ROAD_CLOSURE, WEATHER_ALERT, FLIGHT_DELAY,
-- SERVICE_OUTAGE, PRICE_CHANGE, PUBLIC_ALERT.
create table athena.world_events (
    id uuid primary key default gen_random_uuid(),
    event_type text not null,
    entity_ids uuid[] not null default '{}',
    status text not null,
    severity text,
    location jsonb,
    started_at timestamptz,
    ended_at timestamptz,
    detected_at timestamptz not null default now(),
    confidence numeric,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index idx_world_events_type on athena.world_events(event_type);
create index idx_world_events_started_at on athena.world_events(started_at desc);

revoke all on athena.world_events from anon, authenticated;
