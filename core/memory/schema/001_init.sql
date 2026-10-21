-- Atlas cognitive memory — initial schema.
--
-- Twelve focused tables instead of one polymorphic "memories" table, per the memory
-- architecture design: each memory kind has its own shape, but they all share a common set of
-- lifecycle/metadata columns (confidence, importance, heat, validity, timestamps) so a single
-- mental model ("how trustworthy is this, how important, when was it last used") applies
-- everywhere.
--
-- Security: access is limited to the authenticated owner (auth.uid()::text = user_id). The
-- anon/publishable key is public and must never be treated as authorization. These policies protect
-- row access; the values are still plaintext in Supabase until client-side envelope encryption is
-- integrated with the memory repository.

create extension if not exists pgcrypto;
create extension if not exists vector;

-- Shared metadata columns, spelled out on every table (Postgres has no clean "mixin" for columns,
-- and separate tables read far more clearly than one giant polymorphic table with a discriminator).
--   id                uuid primary key
--   user_id           text        — must equal the authenticated Supabase user's UUID
--   confidence        real        — 0..1, how sure we are this is still true
--   importance        real        — 0..1, how much this should weigh in future reasoning
--   heat              real        — 0..1, recency/frequency-decayed relevance score
--   created_at        timestamptz
--   updated_at        timestamptz
--   last_used_at       timestamptz — last time this record actually informed a response
--   expires_at        timestamptz — null = never expires
--   confirmed_by_user boolean     — explicitly confirmed vs. only inferred/observed
--   valid             boolean     — false once superseded (see memory_corrections)

create table if not exists memory_facts (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    tipo text not null default 'semantic', -- 'identity' | 'semantic' | 'capability' | ...
    chave text,                            -- e.g. 'nome', 'idioma', 'mora_em'
    conteudo jsonb not null,
    origem text not null default 'observed', -- 'user_explicit' | 'observed' | 'inferred'
    confidence real not null default 1.0,
    importance real not null default 0.5,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default false,
    valid boolean not null default true
);

create table if not exists memory_preferences (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    assunto text not null,      -- e.g. 'respostas', 'notificacoes', 'musica'
    preferencia text not null,  -- e.g. 'respostas diretas'
    origem text not null default 'observed',
    confidence real not null default 0.5,
    importance real not null default 0.5,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default false,
    valid boolean not null default true
);

-- Raw observed events — high volume, low individual importance. Feeds routine/pattern inference;
-- expected to be pruned aggressively (short default expiry) once patterns are extracted into
-- memory_routines.
create table if not exists memory_events (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    evento text not null,       -- e.g. 'app_aberto:com.whatsapp'
    payload jsonb,
    origem text not null default 'observed',
    confidence real not null default 1.0,
    importance real not null default 0.1,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz not null default (now() + interval '30 days'),
    confirmed_by_user boolean not null default false,
    valid boolean not null default true
);

create table if not exists memory_episodes (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    data timestamptz not null default now(),
    evento text not null,
    assunto text,
    acao text,
    resultado text,
    origem text not null default 'observed',
    confidence real not null default 1.0,
    importance real not null default 0.5,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default false,
    valid boolean not null default true
);

create table if not exists memory_routines (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    evento text not null,          -- e.g. 'abrir WhatsApp'
    horario_medio time,
    frequencia real not null default 0.0, -- 0..1
    dias smallint[] not null default '{}', -- ISO weekday numbers, 1=Monday..7=Sunday
    ultima_confirmacao timestamptz,
    origem text not null default 'inferred',
    confidence real not null default 0.5,
    importance real not null default 0.5,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default false,
    valid boolean not null default true
);

create table if not exists memory_people (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    nome text not null,
    relacao text,                 -- e.g. 'colega de trabalho', 'irmão'
    telefone text,                 -- digits only where possible; used by send_whatsapp_message (wa.me)
    importance real not null default 0.5,
    contexto text,
    assuntos_associados text[] not null default '{}',
    ultima_interacao timestamptz,
    origem text not null default 'observed',
    confidence real not null default 0.8,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default false,
    valid boolean not null default true
);

create table if not exists memory_projects (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    nome text not null,
    projeto_pai_id uuid references memory_projects(id) on delete set null,
    descricao text,
    aliases text[] not null default '{}', -- e.g. {'o launcher', 'atlas launcher'}
    -- Added for the memory architecture spec's Fase 2 "continuidade de projetos" (section 17):
    -- lets "vamos continuar aquele projeto" resolve to a real next step instead of just a name.
    status text not null default 'ativo', -- 'ativo' | 'pausado' | 'concluido'
    objetivo text,
    proxima_acao text,
    origem text not null default 'user_explicit',
    confidence real not null default 1.0,
    importance real not null default 0.5,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default true,
    valid boolean not null default true
);

-- Fase 2 of the memory architecture spec (section 27) — pending/in-progress work items, separate
-- from memory_episodes (which records what already happened) and memory_projects (the umbrella a
-- task can optionally belong to).
create table if not exists memory_tasks (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    titulo text not null,
    descricao text,
    projeto_id uuid references memory_projects(id) on delete set null,
    prioridade text not null default 'normal', -- 'baixa' | 'normal' | 'alta'
    status text not null default 'pending', -- 'pending' | 'in_progress' | 'blocked' | 'completed' | 'cancelled'
    prazo date,
    origem text not null default 'user_explicit',
    confidence real not null default 1.0,
    importance real not null default 0.5,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    completed_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default true,
    valid boolean not null default true
);

-- Freeform notes (grid/checklist screen) — deliberately separate from memory_facts (stable facts
-- about the user) and memory_tasks (status-tracked work items): a note is whatever the user wrote,
-- kept in their own words, editable directly, not reformatted/inferred content.
create table if not exists memory_notes (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    tipo text not null default 'nota',      -- 'nota' | 'checklist'
    titulo text,
    conteudo text,                          -- corpo livre; vazio para checklist puro
    checklist jsonb not null default '[]',  -- [{"texto":"...", "concluido":bool, "ordem":int}]
    tags text[] not null default '{}',
    cor text,                               -- token de cor (ex: 'amarelo'); null = padrão
    fixada boolean not null default false,
    arquivada boolean not null default false,
    trashed_at timestamptz,                 -- não-nulo = na lixeira
    lembrete_em timestamptz,
    origem text not null default 'user_explicit',
    confidence real not null default 1.0,
    importance real not null default 0.5,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default true,
    valid boolean not null default true
);

create table if not exists memory_decisions (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    regra text not null,          -- e.g. 'nao_notificar'
    periodo text,                 -- e.g. 'horario de trabalho'
    confirmada boolean not null default false,
    origem text not null default 'user_explicit',
    confidence real not null default 1.0,
    importance real not null default 0.7,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default true,
    valid boolean not null default true
);

-- A correction never deletes the old record — it flips memory_corrections.valid on that row (via
-- the app layer) and adds a row here documenting why, so the model of "what changed and when"
-- survives instead of quietly vanishing.
create table if not exists memory_corrections (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    tabela_origem text not null,   -- which memory_* table the corrected record lives in
    registro_id uuid not null,     -- id of the row being corrected (soft-invalidated there, not deleted)
    conhecimento_antigo text,
    conhecimento_novo text,
    motivo text,
    origem text not null default 'user_explicit',
    confidence real not null default 1.0,
    importance real not null default 0.6,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default true,
    valid boolean not null default true
);

-- Short-lived contextual snapshots (current app, battery, location...) — not raw events, already
-- one level more distilled. Short default expiry; this is "what's true right now", not history.
create table if not exists memory_context (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    chave text not null,          -- e.g. 'app_atual', 'localizacao', 'bateria'
    valor jsonb not null,
    capturado_em timestamptz not null default now(),
    origem text not null default 'observed',
    confidence real not null default 1.0,
    importance real not null default 0.2,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz not null default (now() + interval '2 days'),
    confirmed_by_user boolean not null default false,
    valid boolean not null default true
);

-- Vector embeddings, kept separate from the content tables (a record may or may not have one yet,
-- and an embedding model swap shouldn't touch the content tables at all).
create table if not exists memory_embeddings (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    tabela_origem text not null,
    registro_id uuid not null,
    modelo text not null,          -- which embedding model produced this vector
    embedding vector(1536),         -- matches OpenAI text-embedding-3-small; adjust if the model differs
    created_at timestamptz not null default now(),
    unique (tabela_origem, registro_id, modelo)
);

-- Every read/write touch on a memory record — lets "heat" (recency/frequency) be computed from
-- real usage instead of guessed, and gives a debuggable trail of what the assistant actually drew
-- on to produce a given response.
create table if not exists memory_access_log (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    tabela_origem text not null,
    registro_id uuid not null,
    acao text not null,            -- 'read' | 'write' | 'used_in_response'
    acessado_em timestamptz not null default now()
);

create index if not exists idx_memory_facts_user on memory_facts(user_id) where valid;
create index if not exists idx_memory_preferences_user on memory_preferences(user_id) where valid;
create index if not exists idx_memory_events_user_created on memory_events(user_id, created_at desc);
create index if not exists idx_memory_episodes_user_data on memory_episodes(user_id, data desc);
create index if not exists idx_memory_routines_user on memory_routines(user_id) where valid;
create index if not exists idx_memory_people_user on memory_people(user_id) where valid;
create index if not exists idx_memory_projects_user on memory_projects(user_id) where valid;
create index if not exists idx_memory_tasks_user_status on memory_tasks(user_id, status) where valid;
create index if not exists idx_memory_notes_user_active on memory_notes(user_id, updated_at desc) where valid and trashed_at is null;
create index if not exists idx_memory_decisions_user on memory_decisions(user_id) where valid;
create index if not exists idx_memory_corrections_origin on memory_corrections(tabela_origem, registro_id);
create index if not exists idx_memory_context_user_chave on memory_context(user_id, chave, capturado_em desc);
create index if not exists idx_memory_embeddings_origin on memory_embeddings(tabela_origem, registro_id);
create index if not exists idx_memory_access_log_origin on memory_access_log(tabela_origem, registro_id, acessado_em desc);

-- RLS: every memory row is restricted to the authenticated Supabase user.
do $$
declare
    t text;
    existing_policy record;
    has_legacy_default boolean;
begin
    for t in select unnest(array[
        'memory_facts', 'memory_preferences', 'memory_events', 'memory_episodes',
        'memory_routines', 'memory_people', 'memory_projects', 'memory_tasks', 'memory_notes',
        'memory_decisions', 'memory_corrections', 'memory_context', 'memory_embeddings', 'memory_access_log'
    ])
    loop
        execute format('select exists (select 1 from public.%I where user_id = %L)', t, 'default')
            into has_legacy_default;
        if has_legacy_default then
            raise exception 'Atlas memory table % still contains user_id=default; assign legacy rows to a verified Auth user before enabling owner RLS', t;
        end if;

        execute format('alter table %I enable row level security', t);
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
    end loop;
end $$;
