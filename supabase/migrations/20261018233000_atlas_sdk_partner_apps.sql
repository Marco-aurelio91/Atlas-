-- Atlas SDK (atlas-sdk function) — partner-app auth and quota, kept deliberately separate from
-- atlas_ai_usage (which is keyed to Atlas's own auth.users). A partner app (e.g. Unyx) has its
-- own, unrelated user base; end_user_id below is just an opaque per-app rate-limiting bucket, not
-- a reference to anything in this project.

create table if not exists public.atlas_sdk_apps (
    app_id text primary key,
    key_hash text not null unique,
    daily_limit integer not null default 50 check (daily_limit > 0),
    minute_limit integer not null default 10 check (minute_limit > 0),
    is_active boolean not null default true,
    created_at timestamptz not null default now()
);

alter table public.atlas_sdk_apps enable row level security;
revoke all on public.atlas_sdk_apps from public, anon, authenticated;
grant select on public.atlas_sdk_apps to service_role;

-- Compact per-(app, end user, operation) counters, same shape as atlas_ai_usage.
create table if not exists public.atlas_sdk_usage (
    app_id text not null references public.atlas_sdk_apps(app_id) on delete cascade,
    end_user_id text not null check (length(end_user_id) between 1 and 128),
    operation text not null check (operation in ('chat', 'embedding')),
    usage_date date not null,
    requests_today integer not null default 0 check (requests_today >= 0),
    minute_started_at timestamptz not null,
    requests_this_minute integer not null default 0 check (requests_this_minute >= 0),
    updated_at timestamptz not null default now(),
    primary key (app_id, end_user_id, operation)
);

alter table public.atlas_sdk_usage enable row level security;
revoke all on public.atlas_sdk_usage from public, anon, authenticated;
grant all on public.atlas_sdk_usage to service_role;

-- Looks up the key hash AND claims this request's quota atomically — no gap between "is this key
-- valid" and "did this push the app over its own daily/minute limit". Returns allowed=false for
-- EITHER an unknown/inactive key or a quota breach on purpose (see atlas-sdk/index.ts's own doc) —
-- the caller never learns which one happened.
create or replace function public.atlas_sdk_authorize_and_claim(
    p_key_hash text,
    p_end_user_id text,
    p_operation text
)
returns table(allowed boolean, app_id text)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_app record;
    v_now timestamptz := pg_catalog.clock_timestamp();
    v_today date := (v_now at time zone 'UTC')::date;
    v_minute timestamptz := pg_catalog.date_trunc('minute', v_now);
    v_today_count integer;
    v_minute_count integer;
begin
    if p_key_hash is null or p_end_user_id is null or length(p_end_user_id) = 0
       or length(p_end_user_id) > 128 or p_operation is null or p_operation not in ('chat', 'embedding') then
        return query select false, null::text;
        return;
    end if;

    select * into v_app from public.atlas_sdk_apps where key_hash = p_key_hash and is_active = true;
    if v_app is null then
        return query select false, null::text;
        return;
    end if;

    insert into public.atlas_sdk_usage as current_usage (
        app_id, end_user_id, operation, usage_date, requests_today, minute_started_at,
        requests_this_minute, updated_at
    ) values (
        v_app.app_id, p_end_user_id, p_operation, v_today, 1, v_minute, 1, v_now
    )
    on conflict (app_id, end_user_id, operation) do update set
        usage_date = v_today,
        requests_today = case
            when current_usage.usage_date = v_today then current_usage.requests_today + 1
            else 1
        end,
        minute_started_at = v_minute,
        requests_this_minute = case
            when current_usage.minute_started_at = v_minute then current_usage.requests_this_minute + 1
            else 1
        end,
        updated_at = v_now
    returning current_usage.requests_today, current_usage.requests_this_minute
    into v_today_count, v_minute_count;

    return query select
        (v_today_count <= v_app.daily_limit and v_minute_count <= v_app.minute_limit),
        v_app.app_id;
end;
$$;

revoke all on function public.atlas_sdk_authorize_and_claim(text, text, text) from public, anon, authenticated;
grant execute on function public.atlas_sdk_authorize_and_claim(text, text, text) to service_role;

-- Unyx's partner-app row. key_hash is SHA-256(plaintext key) — the plaintext itself was generated
-- once out-of-band and handed to the user for Unyx's local .env; it is never written to this repo.
insert into public.atlas_sdk_apps (app_id, key_hash, daily_limit, minute_limit)
values ('unyx', '7b01736f13e5bbc22fed39d2f86be8f1dd5d6923c4f625a2cb3be5914fea1ba9', 50, 10)
on conflict (app_id) do nothing;
