-- Per-provider health, backing the simplified circuit breaker (docs §16, §38).
create table athena.provider_status (
    provider_code text primary key,
    enabled boolean not null default true,
    last_success_at timestamptz,
    last_failure_at timestamptz,
    consecutive_failures integer not null default 0,
    average_latency_ms integer,
    metadata jsonb not null default '{}'::jsonb
);

revoke all on athena.provider_status from anon, authenticated;
