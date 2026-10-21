-- Fix: atlas_sdk_authorize_and_claim's RETURNS TABLE declared an out-parameter literally named
-- `app_id`, which PL/pgSQL turns into an in-scope variable with that name — colliding with the
-- `app_id` column reference inside `on conflict (app_id, end_user_id, operation)` ("column
-- reference app_id is ambiguous", caught live via a direct curl/RPC test right after deploying).
-- Renaming the out-parameter to `matched_app_id` removes the collision without changing any
-- table or column name. atlas-sdk/index.ts reads `row.matched_app_id` after this.
-- A DROP is required (not just CREATE OR REPLACE) because changing the OUT-parameter row type
-- is not allowed in-place (SQLSTATE 42P13).
drop function if exists public.atlas_sdk_authorize_and_claim(text, text, text);

create function public.atlas_sdk_authorize_and_claim(
    p_key_hash text,
    p_end_user_id text,
    p_operation text
)
returns table(allowed boolean, matched_app_id text)
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
