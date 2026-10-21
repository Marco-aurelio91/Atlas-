-- Raw-ish provider readings, before they become facts/events (docs §12).
create table athena.world_observations (
    id uuid primary key default gen_random_uuid(),
    source_id uuid references athena.world_sources(id),
    entity_id uuid references athena.world_entities(id),
    observation_type text not null,
    payload jsonb not null,
    observed_at timestamptz not null,
    published_at timestamptz,
    expires_at timestamptz,
    created_at timestamptz not null default now()
);

create index idx_world_observations_entity on athena.world_observations(entity_id);

revoke all on athena.world_observations from anon, authenticated;
