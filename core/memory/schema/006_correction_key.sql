-- Reflection Engine (plan section 13) groundwork: its most tractable trigger ("comportamento
-- antigo contradiz o novo") already has data in memory_corrections, but there's no cheap way to
-- ask "has THIS fact/preference been corrected more than once recently" — registro_id changes on
-- every correction (a new row each time), so grouping needs the stable key, not the row id. One
-- nullable column, populated by the same save_fact/save_preference calls that already write a
-- correction row.
alter table memory_corrections add column if not exists chave text;

create index if not exists idx_memory_corrections_chave on memory_corrections(tabela_origem, chave, created_at desc) where chave is not null;
