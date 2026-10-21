-- How many times each memory record was actually used in a response, read back from
-- memory_access_log (already written on every call — see MemoryRepository.markUsed — but never
-- queried before this) — the real "observationCount" signal MemoryRanking.weightedScore() was
-- missing, no new column needed. One RPC call batches the count for every id a ranking pass needs
-- instead of one query per record (same reasoning as match_memory_embeddings in
-- 002_vector_search.sql: PostgREST can't easily express "group by, filtered to this id set" as a
-- plain resource query, so it's a stored function).
create or replace function memory_access_counts(
    p_tabela text,
    p_ids uuid[],
    p_user_id text,
    p_acao text default 'used_in_response'
)
returns table (
    registro_id uuid,
    cnt bigint
)
language sql
stable
security invoker
as $$
    select
        a.registro_id,
        count(*) as cnt
    from memory_access_log a
    where a.tabela_origem = p_tabela
        and a.registro_id = any(p_ids)
        and a.user_id = (select auth.uid())::text
        and a.user_id = p_user_id
        and a.acao = p_acao
    group by a.registro_id
$$;

revoke all on function public.memory_access_counts(text, uuid[], text, text) from public, anon;
grant execute on function public.memory_access_counts(text, uuid[], text, text) to authenticated, service_role;
