-- Provenance for a fact or event — the answer to "como sabemos disso?" (docs §13, §30). A fact or
-- event with no evidence row is a modeling bug, not a valid published state.
create table athena.world_evidence (
    id uuid primary key default gen_random_uuid(),
    fact_id uuid,
    event_id uuid,
    source_id uuid references athena.world_sources(id),
    url text,
    external_record_id text,
    retrieved_at timestamptz not null default now(),
    published_at timestamptz,
    metadata jsonb not null default '{}'::jsonb
);

revoke all on athena.world_evidence from anon, authenticated;
