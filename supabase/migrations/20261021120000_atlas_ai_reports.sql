-- In-app reports of AI responses (Google Play "AI-generated content" policy: users must be able
-- to flag offensive or harmful output from inside the app).
--
-- Insert-only for the signed-in owner. The reported text is sent only when the user taps "Enviar"
-- in the report dialog, which says exactly what is sent. Rows are deleted with the account.
create table if not exists public.atlas_ai_reports (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    reason text not null check (reason in ('harmful', 'false_info', 'privacy', 'other')),
    response_excerpt text not null check (char_length(response_excerpt) between 1 and 4000),
    created_at timestamptz not null default now()
);

alter table public.atlas_ai_reports enable row level security;

drop policy if exists atlas_ai_reports_insert_owner on public.atlas_ai_reports;
drop policy if exists atlas_ai_reports_read_owner on public.atlas_ai_reports;
create policy atlas_ai_reports_insert_owner
    on public.atlas_ai_reports
    for insert to authenticated
    with check (auth.uid() = user_id);
-- The app inserts with Prefer: return=representation, which needs read access to the row it just
-- created; limiting it to the owner's own reports.
create policy atlas_ai_reports_read_owner
    on public.atlas_ai_reports
    for select to authenticated
    using (auth.uid() = user_id);

revoke all on public.atlas_ai_reports from public, anon;
grant select, insert on public.atlas_ai_reports to authenticated;
grant all on public.atlas_ai_reports to service_role;
