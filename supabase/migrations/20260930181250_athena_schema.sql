-- ATHENA schema — isolated from the ATLAS personal domain (memories/preferences/experiences) in
-- the same Supabase project. See docs/ATHENA_PLANO_CRIACAO_BACKEND_SUPABASE.md §5-6.
-- Rule: no table in this schema may become a personal-memory dumping ground.
create schema if not exists athena;

-- Not exposed to PostgREST directly — the client (ATLAS Android) never runs SELECT/INSERT against
-- athena.* itself; only the athena-query Edge Function (service_role) does. See §6, §34.
revoke all on schema athena from anon, authenticated;
