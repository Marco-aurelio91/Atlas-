-- Atlas memory tables: remove the historical allow-all policies and enforce owner isolation.
-- Memory rows remain plaintext in this phase; this migration hardens authorization, not E2EE.
begin;

-- Do not silently strand the single-user rows written by older builds. Owner assignment requires
-- an administrator to identify and verify the intended Supabase Auth UUID first.
do $$
declare
    t text;
    has_legacy_default boolean;
    memory_tables text[] := array[
        'memory_facts', 'memory_preferences', 'memory_events', 'memory_episodes',
        'memory_routines', 'memory_people', 'memory_projects', 'memory_tasks', 'memory_notes',
        'memory_decisions', 'memory_corrections', 'memory_context', 'memory_embeddings',
        'memory_access_log', 'memory_places'
    ];
begin
    foreach t in array memory_tables loop
        if to_regclass(format('public.%I', t)) is not null then
            execute format('select exists (select 1 from public.%I where user_id = %L)', t, 'default')
                into has_legacy_default;
            if has_legacy_default then
                raise exception 'Atlas memory table % contains user_id=default; assign legacy rows to the verified Auth UUID before applying this migration', t;
            end if;
        end if;
    end loop;
end $$;

do $$
declare
    t text;
    existing_policy record;
    memory_tables text[] := array[
        'memory_facts', 'memory_preferences', 'memory_events', 'memory_episodes',
        'memory_routines', 'memory_people', 'memory_projects', 'memory_tasks', 'memory_notes',
        'memory_decisions', 'memory_corrections', 'memory_context', 'memory_embeddings',
        'memory_access_log', 'memory_places'
    ];
begin
    foreach t in array memory_tables loop
        if to_regclass(format('public.%I', t)) is not null then
            execute format('alter table public.%I enable row level security', t);

            -- Policies combine with OR, so remove every previous policy on these tables before
            -- installing the single owner policy. This also removes any leftover allow-all policy.
            for existing_policy in
                select policyname
                from pg_policies
                where schemaname = 'public' and tablename = t
            loop
                execute format('drop policy if exists %I on public.%I', existing_policy.policyname, t);
            end loop;

            execute format(
                'create policy atlas_user_isolation on public.%I for all to authenticated using (((select auth.uid())::text = user_id)) with check (((select auth.uid())::text = user_id))',
                t
            );
            execute format('revoke all on table public.%I from public, anon', t);
            execute format('grant select, insert, update, delete on table public.%I to authenticated', t);
            execute format('grant all on table public.%I to service_role', t);
        end if;
    end loop;
end $$;

-- The RPCs are invoker functions and also compare the requested owner to auth.uid(). Restrict
-- invocation to signed-in clients; service_role can still call them from trusted server code.
do $$
begin
    if to_regprocedure('public.match_memory_embeddings(text,text,integer,text[],real)') is not null then
        revoke all on function public.match_memory_embeddings(text, text, integer, text[], real) from public, anon;
        grant execute on function public.match_memory_embeddings(text, text, integer, text[], real) to authenticated, service_role;
    end if;

    if to_regprocedure('public.memory_access_counts(text,uuid[],text,text)') is not null then
        revoke all on function public.memory_access_counts(text, uuid[], text, text) from public, anon;
        grant execute on function public.memory_access_counts(text, uuid[], text, text) to authenticated, service_role;
    end if;
end $$;

commit;
