package com.unyx.atlas.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * Manual JSON (de)serialization for each memory type — matches the org.json + manual-mapping
 * style already used elsewhere in this codebase (DriveRestClient etc.) rather than adding
 * kotlinx-serialization for a dozen fairly small, stable shapes.
 *
 * jsonb columns (conteudo/payload/valor) are assumed to hold a JSON *object* — e.g.
 * {"nome": "Marco Aurélio", "idioma": "pt-BR"} — and are carried in the Kotlin model as raw JSON
 * text rather than a typed structure, since what belongs inside varies per record and there's no
 * concrete consumer yet to design a typed shape against.
 */

private fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }
private fun JSONArray.toIntList(): List<Int> = List(length()) { getInt(it) }

// ---- MemoryFact ----

fun MemoryFact.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("tipo", tipo)
    chave?.let { put("chave", it) }
    put("conteudo", JSONObject(conteudo))
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryFact() = MemoryFact(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    tipo = getString("tipo"),
    chave = stringOrNull("chave"),
    conteudo = getJSONObject("conteudo").toString(),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryPreference ----

fun MemoryPreference.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("assunto", assunto)
    put("preferencia", preferencia)
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryPreference() = MemoryPreference(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    assunto = getString("assunto"),
    preferencia = getString("preferencia"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryEvent ----

fun MemoryEvent.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("evento", evento)
    payload?.let { put("payload", JSONObject(it)) }
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryEvent() = MemoryEvent(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    evento = getString("evento"),
    payload = if (has("payload") && !isNull("payload")) getJSONObject("payload").toString() else null,
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryEpisode ----

fun MemoryEpisode.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    data?.let { put("data", it) }
    put("evento", evento)
    assunto?.let { put("assunto", it) }
    acao?.let { put("acao", it) }
    resultado?.let { put("resultado", it) }
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryEpisode() = MemoryEpisode(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    data = stringOrNull("data"),
    evento = getString("evento"),
    assunto = stringOrNull("assunto"),
    acao = stringOrNull("acao"),
    resultado = stringOrNull("resultado"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryRoutine ----

fun MemoryRoutine.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("evento", evento)
    horarioMedio?.let { put("horario_medio", it) }
    put("frequencia", frequencia)
    put("dias", JSONArray(dias))
    ultimaConfirmacao?.let { put("ultima_confirmacao", it) }
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryRoutine() = MemoryRoutine(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    evento = getString("evento"),
    horarioMedio = stringOrNull("horario_medio"),
    frequencia = getDouble("frequencia").toFloat(),
    dias = optJSONArray("dias")?.toIntList() ?: emptyList(),
    ultimaConfirmacao = stringOrNull("ultima_confirmacao"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryPlace ----

fun MemoryPlace.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("nome", nome)
    endereco?.let { put("endereco", it) }
    put("aliases", JSONArray(aliases))
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryPlace() = MemoryPlace(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    nome = getString("nome"),
    endereco = stringOrNull("endereco"),
    aliases = optJSONArray("aliases")?.toStringList() ?: emptyList(),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryPerson ----

fun MemoryPerson.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("nome", nome)
    relacao?.let { put("relacao", it) }
    telefone?.let { put("telefone", it) }
    contexto?.let { put("contexto", it) }
    put("assuntos_associados", JSONArray(assuntosAssociados))
    ultimaInteracao?.let { put("ultima_interacao", it) }
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryPerson() = MemoryPerson(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    nome = getString("nome"),
    relacao = stringOrNull("relacao"),
    telefone = stringOrNull("telefone"),
    contexto = stringOrNull("contexto"),
    assuntosAssociados = optJSONArray("assuntos_associados")?.toStringList() ?: emptyList(),
    ultimaInteracao = stringOrNull("ultima_interacao"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryProject ----

fun MemoryProject.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("nome", nome)
    projetoPaiId?.let { put("projeto_pai_id", it) }
    descricao?.let { put("descricao", it) }
    put("aliases", JSONArray(aliases))
    put("status", status)
    objetivo?.let { put("objetivo", it) }
    proximaAcao?.let { put("proxima_acao", it) }
    deadline?.let { put("deadline", it) }
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryProject() = MemoryProject(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    nome = getString("nome"),
    projetoPaiId = stringOrNull("projeto_pai_id"),
    descricao = stringOrNull("descricao"),
    aliases = optJSONArray("aliases")?.toStringList() ?: emptyList(),
    status = optString("status").ifBlank { "ativo" },
    objetivo = stringOrNull("objetivo"),
    proximaAcao = stringOrNull("proxima_acao"),
    deadline = stringOrNull("deadline"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryTask ----

fun MemoryTask.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("titulo", titulo)
    descricao?.let { put("descricao", it) }
    projetoId?.let { put("projeto_id", it) }
    put("prioridade", prioridade)
    put("status", status)
    prazo?.let { put("prazo", it) }
    completedAt?.let { put("completed_at", it) }
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryTask() = MemoryTask(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    titulo = getString("titulo"),
    descricao = stringOrNull("descricao"),
    projetoId = stringOrNull("projeto_id"),
    prioridade = optString("prioridade").ifBlank { "normal" },
    status = optString("status").ifBlank { "pending" },
    prazo = stringOrNull("prazo"),
    completedAt = stringOrNull("completed_at"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryNote ----

private fun NoteChecklistItem.toJson(): JSONObject = JSONObject()
    .put("texto", texto)
    .put("concluido", concluido)
    .put("ordem", ordem)

private fun JSONObject.toNoteChecklistItem() = NoteChecklistItem(
    texto = getString("texto"),
    concluido = optBoolean("concluido", false),
    ordem = optInt("ordem", 0),
)

private fun JSONArray.toChecklist(): List<NoteChecklistItem> = List(length()) { getJSONObject(it).toNoteChecklistItem() }

fun MemoryNote.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("tipo", tipo)
    titulo?.let { put("titulo", it) }
    conteudo?.let { put("conteudo", it) }
    put("checklist", JSONArray(checklist.map { it.toJson() }))
    put("tags", JSONArray(tags))
    cor?.let { put("cor", it) }
    put("fixada", fixada)
    put("arquivada", arquivada)
    trashedAt?.let { put("trashed_at", it) }
    lembreteEm?.let { put("lembrete_em", it) }
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryNote() = MemoryNote(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    tipo = optString("tipo").ifBlank { "nota" },
    titulo = stringOrNull("titulo"),
    conteudo = stringOrNull("conteudo"),
    checklist = optJSONArray("checklist")?.toChecklist() ?: emptyList(),
    tags = optJSONArray("tags")?.toStringList() ?: emptyList(),
    cor = stringOrNull("cor"),
    fixada = optBoolean("fixada", false),
    arquivada = optBoolean("arquivada", false),
    trashedAt = stringOrNull("trashed_at"),
    lembreteEm = stringOrNull("lembrete_em"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryDecision ----

fun MemoryDecision.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("regra", regra)
    periodo?.let { put("periodo", it) }
    put("confirmada", confirmada)
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryDecision() = MemoryDecision(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    regra = getString("regra"),
    periodo = stringOrNull("periodo"),
    confirmada = getBoolean("confirmada"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryCorrection ----

fun MemoryCorrection.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("tabela_origem", tabelaOrigem)
    put("registro_id", registroId)
    conhecimentoAntigo?.let { put("conhecimento_antigo", it) }
    conhecimentoNovo?.let { put("conhecimento_novo", it) }
    motivo?.let { put("motivo", it) }
    chave?.let { put("chave", it) }
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryCorrection() = MemoryCorrection(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    tabelaOrigem = getString("tabela_origem"),
    registroId = getString("registro_id"),
    conhecimentoAntigo = stringOrNull("conhecimento_antigo"),
    conhecimentoNovo = stringOrNull("conhecimento_novo"),
    motivo = stringOrNull("motivo"),
    chave = stringOrNull("chave"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryContext ----

fun MemoryContext.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("chave", chave)
    put("valor", JSONObject(valor))
    put("origem", origem)
    put("confidence", confidence)
    put("importance", importance)
    put("confirmed_by_user", confirmedByUser)
}

fun JSONObject.toMemoryContext() = MemoryContext(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    chave = getString("chave"),
    valor = getJSONObject("valor").toString(),
    capturadoEm = stringOrNull("capturado_em"),
    origem = getString("origem"),
    confidence = getDouble("confidence").toFloat(),
    importance = getDouble("importance").toFloat(),
    heat = getDouble("heat").toFloat(),
    createdAt = stringOrNull("created_at"),
    updatedAt = stringOrNull("updated_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = stringOrNull("expires_at"),
    confirmedByUser = getBoolean("confirmed_by_user"),
    valid = getBoolean("valid"),
)

// ---- MemoryEmbedding ----

fun MemoryEmbedding.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("tabela_origem", tabelaOrigem)
    put("registro_id", registroId)
    put("modelo", modelo)
    // A string in pgvector's own text format, not a JSON array — see toVectorLiteral()'s doc.
    put("embedding", embedding.toVectorLiteral())
}

fun JSONObject.toMemoryEmbedding() = MemoryEmbedding(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    tabelaOrigem = getString("tabela_origem"),
    registroId = getString("registro_id"),
    modelo = getString("modelo"),
    embedding = optJSONArray("embedding")?.let { arr -> List(arr.length()) { arr.getDouble(it).toFloat() } } ?: emptyList(),
    createdAt = stringOrNull("created_at"),
)

// ---- MemoryAccessLogEntry ----

fun MemoryAccessLogEntry.toInsertJson(): JSONObject = JSONObject().apply {
    put("user_id", userId)
    put("tabela_origem", tabelaOrigem)
    put("registro_id", registroId)
    put("acao", acao)
}

fun JSONObject.toMemoryAccessLogEntry() = MemoryAccessLogEntry(
    id = stringOrNull("id"),
    userId = getString("user_id"),
    tabelaOrigem = getString("tabela_origem"),
    registroId = getString("registro_id"),
    acao = getString("acao"),
    acessadoEm = stringOrNull("acessado_em"),
)
