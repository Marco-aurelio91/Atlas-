-- Vector similarity search over memory_embeddings, exposed to the app via PostgREST's RPC
-- endpoint (POST /rest/v1/rpc/match_memory_embeddings) — see MemoryRepository.semanticSearch.
--
-- query_embedding is declared `text`, not `vector(1536)`, and cast inside the function body.
-- Passing it as text (a JSON string shaped like pgvector's own literal format, "[0.1,0.2,...]")
-- avoids any ambiguity in how PostgREST would otherwise map a bare JSON array parameter onto an
-- exotic column/parameter type — see EmbeddingClient.toVectorLiteral()'s doc for the matching
-- choice on the insert side (memory_embeddings.embedding itself stays a real `vector` column;
-- only the wire format of this one RPC argument is text).
--
-- Filters by both the requested owner and auth.uid(); RLS on memory_embeddings provides a second
-- isolation boundary. A caller cannot use this RPC to search another user's vectors.
create or replace function match_memory_embeddings(
    query_embedding text,
    match_user_id text,
    match_limit int default 8,
    match_tables text[] default null,
    min_similarity real default 0.5
)
returns table (
    tabela_origem text,
    registro_id uuid,
    modelo text,
    similarity real
)
language sql
stable
security invoker
as $$
    select
        e.tabela_origem,
        e.registro_id,
        e.modelo,
        -- pgvector's <=> is cosine *distance* (0 = identical, 2 = opposite); similarity here is
        -- the more intuitive 1 - distance (1 = identical) that the rest of this codebase's scores
        -- (confidence, importance, weightedScore) already use a "higher is better, 0..1" convention for.
        1 - (e.embedding <=> query_embedding::vector(1536)) as similarity
    from memory_embeddings e
    where e.user_id = (select auth.uid())::text
        and e.user_id = match_user_id
        and (match_tables is null or e.tabela_origem = any(match_tables))
        and 1 - (e.embedding <=> query_embedding::vector(1536)) >= min_similarity
    order by e.embedding <=> query_embedding::vector(1536)
    limit match_limit
$$;

revoke all on function public.match_memory_embeddings(text, text, integer, text[], real) from public, anon;
grant execute on function public.match_memory_embeddings(text, text, integer, text[], real) to authenticated, service_role;
