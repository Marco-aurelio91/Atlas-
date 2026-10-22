-- Plans and plan-aware AI message limits.
--
-- Free: 15 chat messages/day. Pro ("paid"): 60/day. The per-minute ceiling (20) and the embedding
-- limits are unchanged. A user without a row is on the free plan, so nothing has to be backfilled.
-- Plans are written only by the service role (billing / support); clients can read their own plan.
create table if not exists public.atlas_user_plans (
    user_id uuid primary key references auth.users(id) on delete cascade,
    plan text not null default 'free' check (plan in ('free', 'paid')),
    updated_at timestamptz not null default now()
);

alter table public.atlas_user_plans enable row level security;

drop policy if exists atlas_user_plans_read_owner on public.atlas_user_plans;
create policy atlas_user_plans_read_owner
    on public.atlas_user_plans
    for select to authenticated
    using (auth.uid() = user_id);

revoke all on public.atlas_user_plans from public, anon;
grant select on public.atlas_user_plans to authenticated;
grant all on public.atlas_user_plans to service_role;

create or replace function public.atlas_claim_ai_quota(
    p_user_id uuid,
    p_operation text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_now timestamptz := pg_catalog.clock_timestamp();
    v_today date := (v_now at time zone 'UTC')::date;
    v_minute timestamptz := pg_catalog.date_trunc('minute', v_now);
    v_today_count integer;
    v_minute_count integer;
    v_max_today integer;
    v_max_minute integer;
    v_plan text;
begin
    if p_user_id is null or p_operation is null or p_operation not in ('chat', 'embedding') then
        return false;
    end if;

    insert into public.atlas_ai_usage as current_usage (
        user_id,
        operation,
        usage_date,
        requests_today,
        minute_started_at,
        requests_this_minute,
        updated_at
    ) values (
        p_user_id,
        p_operation,
        v_today,
        1,
        v_minute,
        1,
        v_now
    )
    on conflict (user_id, operation) do update set
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

    select plan into v_plan from public.atlas_user_plans where user_id = p_user_id;

    if p_operation = 'chat' then
        v_max_today := case when v_plan = 'paid' then 60 else 15 end;
        v_max_minute := 20;
    else
        v_max_today := 1000;
        v_max_minute := 60;
    end if;

    return v_today_count <= v_max_today and v_minute_count <= v_max_minute;
end;
$$;

revoke all on function public.atlas_claim_ai_quota(uuid, text) from public, anon, authenticated;
grant execute on function public.atlas_claim_ai_quota(uuid, text) to service_role;
