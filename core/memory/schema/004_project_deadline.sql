-- Goal Model (plan section 25), scoped to what's actually missing: memory_tasks already has
-- confidence (via the shared MemoryMeta columns), priority (prioridade) and a deadline (prazo) —
-- it was already most of the way to being a "Goal". memory_projects had everything else but no
-- deadline at all, so a long-running project goal had no way to ever become urgent. One nullable
-- column closes that gap; no new table, no data migration, nothing existing changes shape.
alter table memory_projects add column if not exists deadline date;
