package com.unyx.atlas.feature.chat.agent

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.unyx.atlas.data.AppRepository
import com.unyx.atlas.data.ai.ClaudeTool
import com.unyx.atlas.data.ai.WEB_SEARCH_TOOL
import com.unyx.atlas.data.di.ApplicationScope
import com.unyx.atlas.data.smartspace.WeatherRepository
import com.unyx.atlas.feature.chat.NetworkKind
import com.unyx.atlas.feature.chat.captureDeviceContext
import com.unyx.atlas.feature.chat.factTipoFor
import com.unyx.atlas.feature.chat.automation.Automation
import com.unyx.atlas.feature.chat.automation.AutomationCondition
import com.unyx.atlas.feature.chat.automation.AutomationStore
import com.unyx.atlas.feature.chat.automation.AutomationTrigger
import com.unyx.atlas.feature.chat.automation.AutomationGeofenceManager
import com.unyx.atlas.feature.chat.automation.automationSummary
import com.unyx.atlas.feature.chat.automation.parseAutomationAction
import com.unyx.atlas.feature.chat.automation.parseAutomationTrigger
import com.unyx.atlas.memory.MemoryContext
import com.unyx.atlas.memory.MemoryDecision
import com.unyx.atlas.memory.MemoryFact
import com.unyx.atlas.memory.MemoryNote
import com.unyx.atlas.memory.MemoryPerson
import com.unyx.atlas.memory.MemoryPlace
import com.unyx.atlas.memory.MemoryPreference
import com.unyx.atlas.memory.MemoryProject
import com.unyx.atlas.memory.MemoryRepository
import com.unyx.atlas.memory.MemoryTask
import com.unyx.atlas.memory.NoteChecklistItem
import com.unyx.atlas.model.AppItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Tools Claude can call mid-conversation — the memory ones let it actually remember something
 * instead of the user always having to go add it manually via MemoryScreen (kept narrow to what a
 * single stated sentence can reasonably justify — no memory_routines tool, since a real routine
 * ("abre WhatsApp às 07:15, seg-sex") needs a pattern observed over time, not one message).
 * `open_app` is the first of a *general* execution engine — Atlas doing something in the world,
 * not just remembering — so it's kept separate in spirit even though it rides the same tool-use
 * mechanism; see MemoryRepository/AppRepository split in [AtlasToolExecutor.execute] below.
 *
 * `internal`, not `private`: both the full chat (ChatViewModel) and the lightweight Assistant
 * screen (AssistantViewModel) send this same list to Claude, so both offer the exact same tools. */
internal val ATLAS_TOOLS = listOf(
    ClaudeTool(
        name = "save_fact",
        description = "Salva um fato permanente e estável sobre o usuário (ex: onde mora, nome, idioma preferido). " +
            "Use apenas quando o usuário contar algo pessoal e duradouro sobre si mesmo, não para informações passageiras.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"chave":{"type":"string","description":"identificador curto, ex: mora_em"},"valor":{"type":"string"}},"required":["chave","valor"]}""",
        ),
    ),
    ClaudeTool(
        name = "save_preference",
        description = "Salva uma preferência sobre como o usuário gosta que as coisas sejam feitas " +
            "(ex: respostas curtas, não notificar à noite). Use quando o usuário expressar uma preferência clara.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"assunto":{"type":"string"},"preferencia":{"type":"string"}},"required":["assunto","preferencia"]}""",
        ),
    ),
    ClaudeTool(
        name = "save_person",
        description = "Salva alguém importante na vida do usuário (ex: colega, familiar, amigo) — não é a mesma coisa " +
            "que um contato do telefone. Use quando o usuário mencionar uma pessoa de forma que valha a pena lembrar quem ela é.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nome":{"type":"string"},"relacao":{"type":"string","description":"ex: colega de trabalho, irmão"},"telefone":{"type":"string","description":"opcional, com código do país se souber, ex: 5535912345678"},"contexto":{"type":"string"}},"required":["nome","relacao"]}""",
        ),
    ),
    ClaudeTool(
        name = "save_place",
        description = "Salva um lugar importante na vida do usuário (ex: trabalho, academia, casa de alguém) — " +
            "não é um endereço exato geocodificado, só o que o usuário disse. Use quando o usuário mencionar um lugar " +
            "de forma que valha a pena lembrar depois (ex: 'minha academia fica na Rua X').",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nome":{"type":"string","description":"ex: trabalho, academia"},"endereco":{"type":"string","description":"opcional, o que o usuário disse sobre onde fica"}},"required":["nome"]}""",
        ),
    ),
    ClaudeTool(
        name = "save_decision",
        description = "Salva uma regra de comportamento que o usuário pediu explicitamente para o Atlas seguir " +
            "(ex: 'não me notifique de noite'). Use só quando for um pedido claro de regra, não uma preferência geral.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"regra":{"type":"string"},"periodo":{"type":"string","description":"opcional, ex: horário de trabalho"}},"required":["regra"]}""",
        ),
    ),
    ClaudeTool(
        name = "open_app",
        description = "Abre um aplicativo instalado no celular do usuário, pelo nome (ex: WhatsApp, Instagram, Câmera). " +
            "Use quando o usuário pedir para abrir, iniciar ou ir para um app.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nome_app":{"type":"string","description":"nome do aplicativo, ex: whatsapp"}},"required":["nome_app"]}""",
        ),
    ),
    ClaudeTool(
        name = "get_weather",
        description = "Consulta o clima atual (temperatura e condição) na localização do usuário. " +
            "Use quando o usuário perguntar sobre o tempo, temperatura, ou se vai chover.",
        inputSchema = JSONObject("""{"type":"object","properties":{}}"""),
    ),
    ClaudeTool(
        name = "set_alarm",
        description = "Cria um alarme no relógio do celular do usuário, para um horário específico do dia (formato 24h). " +
            "Use quando o usuário pedir para acordá-lo, criar um alarme, ou lembrá-lo de algo em um horário do dia " +
            "(não para lembretes em uma data distante — isso é calendário, não alarme).",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"hora":{"type":"integer","description":"hora do alarme, 0-23"},"minuto":{"type":"integer","description":"minuto do alarme, 0-59"},"rotulo":{"type":"string","description":"opcional, o que o alarme é sobre"}},"required":["hora","minuto"]}""",
        ),
    ),
    ClaudeTool(
        name = "start_timer",
        description = "Inicia um temporizador no relógio do Android. Use para pedidos como timer de 5 minutos.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"duracao_minutos":{"type":"number","description":"duração do timer em minutos, pode ter casas decimais (ex: 1.5 para 90 segundos)"},"rotulo":{"type":"string","description":"opcional, o que o timer é sobre, ex: 'bolo no forno'"}},"required":["duracao_minutos"]}""",
        ),
    ),
    ClaudeTool(
        name = "set_volume",
        description = "Ajusta o volume do celular. Use quando o usuário pedir para aumentar, diminuir, ou definir o volume.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nivel":{"type":"integer","description":"volume desejado, 0 a 100"},"tipo":{"type":"string","description":"opcional: media (padrão), alarme, chamada, toque"}},"required":["nivel"]}""",
        ),
    ),
    ClaudeTool(
        name = "send_whatsapp_message",
        description = "Abre uma conversa do WhatsApp com uma pessoa já salva na memória (por nome), com uma " +
            "mensagem pré-escrita pronta para o usuário revisar e enviar — não envia sozinho, o usuário " +
            "precisa tocar em enviar. Use quando o usuário pedir para mandar uma mensagem de WhatsApp para alguém.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nome":{"type":"string","description":"nome da pessoa, já salva na memória"},"mensagem":{"type":"string"}},"required":["nome","mensagem"]}""",
        ),
    ),
    ClaudeTool(
        name = "create_reminder",
        description = "Cria um EVENTO real no calendário do usuário (aparece no app de calendário), em uma data " +
            "específica (formato YYYY-MM-DD), com horário opcional. Use só quando o usuário pedir algo que soa como " +
            "compromisso/evento de agenda (reunião, consulta, aniversário) — não para algo no mesmo dia (isso é o " +
            "alarme, use set_alarm) e não para uma tarefa genérica tipo 'preciso fazer/comprar/lembrar de X' sem " +
            "compromisso de horário — isso é create_task, mesmo que tenha uma data como 'amanhã'.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"titulo":{"type":"string"},"data":{"type":"string","description":"formato YYYY-MM-DD"},"hora":{"type":"integer","description":"opcional, 0-23"},"minuto":{"type":"integer","description":"opcional, 0-59"},"descricao":{"type":"string","description":"opcional"}},"required":["titulo","data"]}""",
        ),
    ),
    ClaudeTool(
        name = "set_brightness",
        description = "Ajusta o brilho da tela do celular. Use quando o usuário pedir para aumentar, diminuir, ou definir o brilho.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nivel":{"type":"integer","description":"brilho desejado, 0 a 100"}},"required":["nivel"]}""",
        ),
    ),
    ClaudeTool(
        name = "create_task",
        description = "Cria uma tarefa pendente — trabalho em aberto (comprar algo, terminar algo, lembrar de fazer " +
            "algo), não um evento de calendário com hora marcada. Esta é a ferramenta padrão para 'preciso fazer X', " +
            "'lembra de X', 'tenho que X' — use mesmo quando houver uma data como 'amanhã' ou 'essa semana', a menos " +
            "que o usuário claramente queira um compromisso de agenda com horário (aí use create_reminder).",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"titulo":{"type":"string"},"descricao":{"type":"string","description":"opcional"},"prazo":{"type":"string","description":"opcional, formato YYYY-MM-DD"},"prioridade":{"type":"string","description":"opcional: baixa, normal (padrão) ou alta"},"projeto":{"type":"string","description":"opcional, nome de um projeto já salvo na memória para associar a tarefa"}},"required":["titulo"]}""",
        ),
    ),
    ClaudeTool(
        name = "complete_task",
        description = "Marca uma tarefa já salva como concluída, pelo título. Use quando o usuário disser que terminou algo que estava pendente.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"titulo":{"type":"string","description":"título da tarefa, já salva na memória"}},"required":["titulo"]}""",
        ),
    ),
    ClaudeTool(
        name = "list_tasks",
        description = "Lista as tarefas pendentes já salvas (criadas com create_task), ordenadas por prazo. Use " +
            "para 'quais são minhas tarefas', 'o que eu tenho pra hoje/pendente', 'o que falta fazer' — sempre " +
            "chame esta ferramenta antes de responder esse tipo de pergunta; nunca diga que não tem como ver as " +
            "tarefas sem antes ter chamado isto.",
        inputSchema = JSONObject("""{"type":"object","properties":{}}"""),
    ),
    ClaudeTool(
        name = "create_note",
        description = "Salva uma nota de texto livre OU uma checklist — rascunhos, ideias soltas, listas de compras, " +
            "coisas para guardar do jeito que a pessoa escreveu, sem reformatar. Diferente de save_fact (um fato " +
            "permanente e estável sobre o usuário) e create_task (uma tarefa com status/prazo próprio): use " +
            "create_note quando o usuário pedir para 'anotar'/'guardar uma nota sobre' algo que não se encaixa nos " +
            "outros dois. Quando o usuário pedir uma 'lista'/'checklist' (ex: lista de compras), use `itens` — cada " +
            "item vira um checkbox de verdade na tela de Notas, não texto solto; não simule checklist escrevendo " +
            "\"- [ ] algo\" dentro de `conteudo`.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"titulo":{"type":"string","description":"opcional"},"conteudo":{"type":"string","description":"corpo de texto livre — omita quando for uma checklist, use itens"},"itens":{"type":"array","items":{"type":"string"},"description":"opcional — presença deste campo cria uma checklist em vez de uma nota de texto; cada string vira um item marcável"},"tags":{"type":"array","items":{"type":"string"},"description":"opcional"}}}""",
        ),
    ),
    ClaudeTool(
        name = "search_notes",
        description = "Busca nas notas do usuário por título ou conteúdo. Use para perguntas como 'o que eu anotei " +
            "sobre X', 'o que eu tenho para comprar', 'encontre minha nota sobre Y'.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"consulta":{"type":"string"}},"required":["consulta"]}""",
        ),
    ),
    ClaudeTool(
        name = "update_note",
        description = "Atualiza uma nota já existente, pelo título. Para uma nota de texto livre: substitui o " +
            "conteúdo ou anexa algo ao final (ex: 'adicione X na nota Y'), via `texto`. Para uma checklist " +
            "(criada com `itens` em create_note): use `itens` aqui para acrescentar novos itens marcáveis à " +
            "lista já existente (ex: 'também preciso comprar Y') — passar o novo texto em `texto` NÃO adiciona " +
            "itens a uma checklist, só mexe no campo de texto livre dela, que normalmente fica vazio e não " +
            "aparece na tela. Use search_notes antes se não tiver certeza do título exato.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"titulo_da_nota":{"type":"string"},"texto":{"type":"string","description":"opcional — para notas de texto livre"},"substituir":{"type":"boolean","description":"opcional, padrão false (anexa ao final em vez de substituir), só vale para `texto`"},"itens":{"type":"array","items":{"type":"string"},"description":"opcional — novos itens para acrescentar a uma checklist já existente"}},"required":["titulo_da_nota"]}""",
        ),
    ),
    ClaudeTool(
        name = "create_project",
        description = "Salva um novo projeto que o usuário mencionou estar trabalhando, ainda não salvo na memória — " +
            "diferente de update_project, que exige que o projeto já exista. Use na primeira vez que um projeto for " +
            "mencionado; se ele já existir, isso apenas atualiza a descrição/objetivo em vez de duplicar.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nome":{"type":"string"},"descricao":{"type":"string","description":"opcional"},"objetivo":{"type":"string","description":"opcional"}},"required":["nome"]}""",
        ),
    ),
    ClaudeTool(
        name = "update_project",
        description = "Atualiza o status, objetivo ou próxima ação de um projeto já salvo na memória (por nome). Use quando " +
            "o usuário falar sobre o andamento de um projeto, para que uma pergunta futura como 'como está aquele projeto?' " +
            "tenha uma resposta real em vez de genérica.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nome":{"type":"string","description":"nome do projeto, já salvo na memória"},"status":{"type":"string","description":"opcional: ativo, pausado ou concluido"},"objetivo":{"type":"string","description":"opcional"},"proxima_acao":{"type":"string","description":"opcional, o que fazer a seguir nesse projeto"},"prazo":{"type":"string","description":"opcional, data limite no formato YYYY-MM-DD, só quando o usuário der um prazo real pro projeto"}},"required":["nome"]}""",
        ),
    ),
    ClaudeTool(
        name = "update_working_memory",
        description = "Registra em que o usuário está trabalhando agora (o objetivo atual da conversa/tarefa em andamento) " +
            "e o próximo passo — para retomar de onde parou se a conversa for interrompida e continuada depois. Use apenas " +
            "para tarefas que se estendem por várias mensagens ou sessões, não para perguntas pontuais.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"objetivo":{"type":"string"},"proximo_passo":{"type":"string","description":"opcional"}},"required":["objetivo"]}""",
        ),
    ),
    ClaudeTool(
        name = "link_person",
        description = "Associa uma pessoa já salva na memória a um projeto, assunto ou outra pessoa (ex: 'trabalha no " +
            "Fiscal Assistant', 'é casada com o Carlos', 'conhece através do trabalho'). Use quando o usuário mencionar " +
            "como duas coisas/pessoas já salvas se conectam — não cria a pessoa (use save_person primeiro se ela ainda " +
            "não existir).",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"pessoa":{"type":"string","description":"nome da pessoa, já salva na memória"},"relacionado_a":{"type":"string","description":"nome do projeto, assunto ou outra pessoa"}},"required":["pessoa","relacionado_a"]}""",
        ),
    ),
    ClaudeTool(
        name = "call_person",
        description = "Abre o discador do telefone já preenchido com o número de uma pessoa salva na memória (por " +
            "nome) — não liga sozinho, o usuário precisa tocar em ligar. Use quando o usuário pedir para ligar, " +
            "telefonar ou dar uma ligada para alguém.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"nome":{"type":"string","description":"nome da pessoa, já salva na memória"}},"required":["nome"]}""",
        ),
    ),
    ClaudeTool(
        name = "find_related_people",
        description = "Busca pessoas associadas a um projeto, assunto ou outra pessoa — o que foi salvo com link_person. " +
            "Use para responder perguntas como 'quem está envolvido em X' ou 'quem eu conheço relacionado a Y'.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"relacionado_a":{"type":"string"}},"required":["relacionado_a"]}""",
        ),
    ),
    ClaudeTool(
        name = "create_automation",
        description = "Cria uma automação local que roda sozinha, sem o usuário pedir de novo (ex: 'sempre que eu " +
            "conectar o carregador depois das 22h, silencia o volume e cria um alarme para as 6:30' vira DUAS " +
            "automações: uma por gatilho). Executa 100% no aparelho, sem internet. Use só quando o usuário pedir " +
            "algo claramente recorrente/automático ('sempre que', 'toda vez que', 'todo dia às', 'de segunda a sexta') — " +
            "não para uma ação pontual de agora (aí use a ferramenta direta, ex: open_app). Para horários, use dias_semana " +
            "ou inclua os dias em gatilho; bateria baixa usa o limite do próprio Android. Rotinas por localização devem ser " +
            "criadas na tela Automações, onde a pessoa salva o local e concede a permissão do Android.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{""" +
                """"gatilho":{"type":"string","description":"carregador_conectado | carregador_desconectado | fone_conectado | fone_desconectado | bateria_baixa | horario:HH:MM[:seg,ter,qua,qui,sex,sab,dom] (ex: horario:06:30)"},""" +
                """"acao":{"type":"string","description":"abrir_app:<nome> | volume:<0-100>[:tipo] (tipo opcional: media/alarme/chamada/toque) | brilho:<0-100> | notificar:<mensagem>"},""" +
                """"dias_semana":{"type":"array","items":{"type":"string","enum":["seg","ter","qua","qui","sex","sab","dom"]},"description":"opcional, apenas para horário; dias em que a rotina deve executar"},""" +
                """"depois_das":{"type":"integer","description":"opcional, condição: só executa depois dessa hora (0-23)"},""" +
                """"antes_das":{"type":"integer","description":"opcional, condição: só executa antes dessa hora (0-23)"},""" +
                """"label":{"type":"string","description":"opcional, nome amigável da rotina para exibir depois"}""" +
                """},"required":["gatilho","acao"]}""",
        ),
    ),
    ClaudeTool(
        name = "list_automations",
        description = "Lista as automações locais já criadas, com seus gatilhos e ações. Use quando o usuário " +
            "perguntar o que já está configurado ('quais rotinas eu tenho', 'o que você automatizou pra mim').",
        inputSchema = JSONObject("""{"type":"object","properties":{}}"""),
    ),
    ClaudeTool(
        name = "delete_automation",
        description = "Remove uma automação local já criada, pelo nome (label) ou por uma descrição do que ela faz. " +
            "Use quando o usuário pedir para parar/cancelar/desativar uma rotina automática.",
        inputSchema = JSONObject(
            """{"type":"object","properties":{"descricao":{"type":"string","description":"label ou descrição da automação a remover"}},"required":["descricao"]}""",
        ),
    ),
    ClaudeTool(
        name = "get_capabilities",
        description = "Informa com precisão o que o ATLAS consegue fazer neste app e os limites atuais. Use ao responder 'o que você pode fazer', 'quais automações existem' ou quando um pedido envolver um recurso sem suporte. Não invente capacidades.",
        inputSchema = JSONObject("""{"type":"object","properties":{}}"""),
    ),
    ClaudeTool(
        name = "open_internet_panel",
        description = "Abre o painel oficial de Internet do Android para a pessoa escolher Wi-Fi ou dados móveis. Use quando a pessoa pedir para abrir controles de rede. Isso não altera rede automaticamente.",
        inputSchema = JSONObject("""{"type":"object","properties":{}}"""),
    ),
    // Anthropic-native server tool, not one of ours — see WEB_SEARCH_TOOL's own doc. Appended here
    // (rather than kept separate) so both ChatViewModel and AssistantViewModel, which already just
    // pass `tools = ATLAS_TOOLS` verbatim, get real-time web search for free.
    WEB_SEARCH_TOOL,
)

/** Claude has no innate sense of "now" — without this, a question like "que horas são" or "que
 * dia é hoje" gets guessed from training data instead of answered from the device's real clock.
 * `internal`, not private to `ChatViewModel`: the lightweight Assistant screen builds its system
 * prompt the same way, so it needs this too. Computed fresh per request; cheap enough (one short
 * line) that this costs nothing worth optimizing away. */
internal fun currentDateTimeContext(): String {
    val now = java.time.LocalDateTime.now()
    val formatter = java.time.format.DateTimeFormatter.ofPattern(
        "EEEE, d 'de' MMMM 'de' yyyy, HH:mm",
        java.util.Locale("pt", "BR"),
    )
    return "Data e hora atual: ${now.format(formatter)}."
}

/** Short device-state line appended to every request's system prompt — the same battery/network
 * signals ContextScreen shows, but fed straight into the prompt so a question like "estou com
 * wifi?" or "minha bateria tá baixa?" gets a real answer. Computed fresh per request, same
 * reasoning as [currentDateTimeContext] — only valid for this instant, cheap enough not to cache. */
internal fun currentDeviceContext(appContext: Context): String {
    val snapshot = captureDeviceContext(appContext)
    val battery = snapshot.batteryPercent?.let { percent ->
        "$percent%" + if (snapshot.batteryCharging == true) " (carregando)" else ""
    } ?: "desconhecida"
    val network = when (snapshot.network) {
        NetworkKind.WIFI -> "Wi-Fi"
        NetworkKind.MOBILE_DATA -> "dados móveis"
        NetworkKind.OTHER -> "conectado"
        NetworkKind.NONE -> "sem conexão"
    }
    return "Estado do aparelho agora: bateria $battery, rede: $network."
}

/**
 * The actual body of every Atlas tool call — extracted out of `ChatViewModel` (2026-09-30) so the
 * lightweight Assistant screen (invoked by the OS assist gesture) can run the exact same tools as
 * the full chat, without duplicating this ~700-line dispatcher. [AgentEventBus.kt]'s own doc
 * previously argued against this split as "low-value/pure-reorg" — that was true when nothing else
 * needed to call these tools; now something does, verified live on-device, so it's no longer a
 * reorg for its own sake. Pure extraction: no tool's logic changed, only where it lives.
 *
 * Publishes to [AgentEventBus] exactly like before ("publish, don't call directly") — episode
 * logging and routine inference stay wired through `ChatViewModel.subscribeToAgentEvents()` and
 * are unaffected by which screen actually triggered the tool call.
 */
@Singleton
class AtlasToolExecutor @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val appRepository: AppRepository,
    private val weatherRepository: WeatherRepository,
    private val automationStore: AutomationStore,
    private val automationGeofenceManager: AutomationGeofenceManager,
    private val agentEventBus: AgentEventBus,
    private val reflectionEngine: ReflectionEngine,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @ApplicationContext private val appContext: Context,
) {
    suspend fun execute(name: String, input: JSONObject): String {
        val result = when (name) {
        "save_fact" -> {
            val chave = input.optString("chave").ifBlank { "info" }
            val valor = input.optString("valor")
            runCatching {
                // Same duplicate-avoidance the person-upsert fix already needed: without looking up
                // an existing row by key first, restating a fact (even just rephrasing it) creates a
                // second, contradictory row instead of correcting the first — see MemoryRepository's
                // findFactByChave doc and the design spec's "correções" section (21/22).
                val existing = runCatching { memoryRepository.findFactByChave(chave) }.getOrNull()
                val existingId = existing?.id
                val oldValor = existing?.let { runCatching { JSONObject(it.conteudo).optString("valor") }.getOrDefault(it.conteudo) }
                // Restating the SAME value is reinforcement, not a contradiction — the old code
                // treated both identically (always invalidate + reset to the fixed base confidence),
                // which meant saying "moro em BH" twice in a row never actually became more trusted
                // than saying it once. Only a genuinely different value resets confidence; a repeat
                // nudges it up instead (see MemoryRanking's weightedScore confidence term).
                val isReinforcement = existing != null && oldValor.equals(valor, ignoreCase = true)
                val newConfidence = if (isReinforcement) ((existing?.confidence ?: 0.9f) + 0.05f).coerceAtMost(1f) else 0.9f
                val needsContradictionCheck = existingId != null && !isReinforcement
                if (existingId != null) {
                    memoryRepository.correct(
                        tabelaOrigem = "memory_facts",
                        registroId = existingId,
                        conhecimentoAntigo = "$chave = $oldValor",
                        conhecimentoNovo = "$chave = $valor",
                        motivo = if (isReinforcement) "Reafirmado pelo usuário" else "Atualizado com nova informação",
                        chave = chave,
                    )
                }
                memoryRepository.addFact(
                    MemoryFact(
                        tipo = factTipoFor(chave),
                        chave = chave,
                        conteudo = JSONObject().put("valor", valor).toString(),
                        origem = "inferred",
                        confidence = newConfidence,
                        confirmedByUser = false,
                    ),
                )
                if (needsContradictionCheck) {
                    applicationScope.launch {
                        reflectionEngine.checkContradiction("memory_facts", chave)
                    }
                }
            }.fold(
                onSuccess = { "Salvei: $chave = $valor" },
                onFailure = { "Não consegui salvar ($chave): ${it.message}" },
            )
        }
        "save_preference" -> {
            val assunto = input.optString("assunto").ifBlank { "geral" }
            val preferencia = input.optString("preferencia")
            runCatching {
                val existing = runCatching { memoryRepository.findPreferenceByAssunto(assunto) }.getOrNull()
                val existingId = existing?.id
                // Same reinforcement-vs-contradiction distinction as save_fact above.
                val isReinforcement = existing != null && existing.preferencia.equals(preferencia, ignoreCase = true)
                val newConfidence = if (isReinforcement) ((existing?.confidence ?: 0.8f) + 0.05f).coerceAtMost(1f) else 0.8f
                val needsContradictionCheck = existingId != null && !isReinforcement
                if (existingId != null) {
                    memoryRepository.correct(
                        tabelaOrigem = "memory_preferences",
                        registroId = existingId,
                        conhecimentoAntigo = "${existing?.assunto}: ${existing?.preferencia}",
                        conhecimentoNovo = "$assunto: $preferencia",
                        motivo = if (isReinforcement) "Reafirmado pelo usuário" else "Atualizado com nova informação",
                        chave = assunto,
                    )
                }
                memoryRepository.addPreference(
                    MemoryPreference(
                        assunto = assunto,
                        preferencia = preferencia,
                        origem = "inferred",
                        confidence = newConfidence,
                        confirmedByUser = false,
                    ),
                )
                if (needsContradictionCheck) {
                    applicationScope.launch {
                        reflectionEngine.checkContradiction("memory_preferences", assunto)
                    }
                }
            }.fold(
                onSuccess = { "Salvei preferência ($assunto): $preferencia" },
                onFailure = { "Não consegui salvar a preferência: ${it.message}" },
            )
        }
        "save_person" -> {
            val nome = input.optString("nome").ifBlank { "alguém" }
            val relacao = input.optString("relacao").ifBlank { null }
            val telefone = input.optString("telefone").ifBlank { null }
            val contexto = input.optString("contexto").ifBlank { null }
            runCatching {
                // save_person has no "update", only "add" — without this, asking Atlas to add one
                // more detail to someone already saved (e.g. a phone number) creates a *second* row
                // for the same name, and a later by-name lookup (send_whatsapp_message) can pick
                // whichever one happens to sort first, silently missing the new detail. Soft-
                // invalidate any existing row for this name first, carrying its fields forward so a
                // narrow update (just a phone number) doesn't blank out what was already known.
                val existing = runCatching { memoryRepository.findPersonByName(nome) }.getOrNull()
                val existingId = existing?.id
                if (existingId != null) {
                    memoryRepository.correct(
                        tabelaOrigem = "memory_people",
                        registroId = existingId,
                        conhecimentoAntigo = null,
                        conhecimentoNovo = null,
                        motivo = "Atualizado com nova informação",
                    )
                }
                memoryRepository.addPerson(
                    MemoryPerson(
                        nome = nome,
                        relacao = relacao ?: existing?.relacao,
                        telefone = telefone ?: existing?.telefone,
                        contexto = contexto ?: existing?.contexto,
                        origem = "inferred",
                        confidence = 0.8f,
                        confirmedByUser = false,
                    ),
                )
            }.fold(
                onSuccess = { "Salvei pessoa: $nome (${relacao ?: "sem relação definida"})" },
                onFailure = { "Não consegui salvar a pessoa ($nome): ${it.message}" },
            )
        }
        "save_place" -> {
            val nome = input.optString("nome").ifBlank { "um lugar" }
            val endereco = input.optString("endereco").ifBlank { null }
            runCatching {
                // Same "find, soft-invalidate, re-add carrying unset fields forward" shape as
                // save_person — a second mention (just an address, say) shouldn't create a twin row.
                val existing = runCatching { memoryRepository.findPlaceByName(nome) }.getOrNull()
                val existingId = existing?.id
                if (existingId != null) {
                    memoryRepository.correct(
                        tabelaOrigem = "memory_places",
                        registroId = existingId,
                        conhecimentoAntigo = null,
                        conhecimentoNovo = null,
                        motivo = "Atualizado com nova informação",
                        chave = nome,
                    )
                }
                memoryRepository.addPlace(
                    MemoryPlace(
                        nome = nome,
                        endereco = endereco ?: existing?.endereco,
                        origem = "inferred",
                        confidence = 0.8f,
                        confirmedByUser = false,
                    ),
                )
            }.fold(
                onSuccess = { "Salvei o lugar: $nome${endereco?.let { " ($it)" } ?: ""}" },
                onFailure = { "Não consegui salvar o lugar ($nome): ${it.message}" },
            )
        }
        "save_decision" -> {
            val regra = input.optString("regra")
            val periodo = input.optString("periodo").ifBlank { null }
            runCatching {
                val existing = runCatching { memoryRepository.findDecisionByRegra(regra) }.getOrNull()
                val existingId = existing?.id
                if (existingId != null) {
                    memoryRepository.correct(
                        tabelaOrigem = "memory_decisions",
                        registroId = existingId,
                        conhecimentoAntigo = "${existing.regra}${existing.periodo?.let { " ($it)" } ?: ""}",
                        conhecimentoNovo = "$regra${periodo?.let { " ($it)" } ?: ""}",
                        motivo = "Atualizado com nova informação",
                        chave = regra,
                    )
                }
                memoryRepository.addDecision(
                    MemoryDecision(
                        regra = regra,
                        periodo = periodo,
                        confirmada = true,
                        origem = "inferred",
                        confidence = 0.9f,
                        confirmedByUser = false,
                    ),
                )
                if (existingId != null) {
                    applicationScope.launch {
                        reflectionEngine.checkContradiction("memory_decisions", regra)
                    }
                }
            }.fold(
                onSuccess = { "Salvei regra: $regra${periodo?.let { " ($it)" } ?: ""}" },
                onFailure = { "Não consegui salvar a regra: ${it.message}" },
            )
        }
        "create_task" -> {
            val titulo = input.optString("titulo")
            val descricao = input.optString("descricao").ifBlank { null }
            val prazo = input.optString("prazo").ifBlank { null }
            val prioridade = input.optString("prioridade").ifBlank { "normal" }
            val projetoNome = input.optString("projeto").ifBlank { null }
            if (titulo.isBlank()) {
                "Não consegui criar a tarefa: título ausente"
            } else {
                runCatching {
                    val projetoId = projetoNome?.let { runCatching { memoryRepository.findProjectByName(it) }.getOrNull()?.id }
                    memoryRepository.addTask(
                        MemoryTask(
                            titulo = titulo,
                            descricao = descricao,
                            projetoId = projetoId,
                            prioridade = prioridade,
                            prazo = prazo,
                            origem = "inferred",
                            confidence = 0.9f,
                            confirmedByUser = false,
                        ),
                    )
                }.fold(
                    onSuccess = { "Criei a tarefa: $titulo${prazo?.let { " (prazo: $it)" } ?: ""}" },
                    onFailure = { "Não consegui criar a tarefa: ${it.message}" },
                )
            }
        }
        "complete_task" -> {
            val titulo = input.optString("titulo")
            if (titulo.isBlank()) {
                "Não consegui concluir a tarefa: título ausente"
            } else {
                runCatching {
                    val existing = memoryRepository.findTaskByTitle(titulo) ?: error("Tarefa não encontrada")
                    val id = existing.id ?: error("Tarefa sem id")
                    memoryRepository.updateTask(
                        id,
                        JSONObject().put("status", "completed").put("completed_at", java.time.Instant.now().toString()),
                    )
                }.fold(
                    onSuccess = { "Marquei '$titulo' como concluída." },
                    onFailure = { "Não consegui concluir a tarefa ($titulo): ${it.message}" },
                )
            }
        }
        "list_tasks" -> {
            runCatching { memoryRepository.openTasks() }.fold(
                onSuccess = { tasks ->
                    if (tasks.isEmpty()) {
                        "Nenhuma tarefa pendente"
                    } else {
                        tasks.joinToString("; ") { task ->
                            buildString {
                                append(task.titulo)
                                task.prazo?.let { append(" (prazo: $it)") }
                                if (task.prioridade != "normal") append(" [${task.prioridade}]")
                            }
                        }
                    }
                },
                onFailure = { "Não consegui listar as tarefas: ${it.message}" },
            )
        }
        "create_note" -> {
            val titulo = input.optString("titulo").ifBlank { null }
            val conteudo = input.optString("conteudo").ifBlank { null }
            val itens = input.optJSONArray("itens")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
            val tags = input.optJSONArray("tags")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
            if (conteudo == null && itens.isEmpty()) {
                "Não consegui criar a nota: conteúdo ausente"
            } else {
                runCatching {
                    memoryRepository.addNote(
                        MemoryNote(
                            tipo = if (itens.isNotEmpty()) "checklist" else "nota",
                            titulo = titulo,
                            conteudo = conteudo,
                            checklist = itens.mapIndexed { index, texto -> NoteChecklistItem(texto = texto, ordem = index) },
                            tags = tags,
                            origem = "inferred",
                            confirmedByUser = false,
                        ),
                    )
                }.fold(
                    onSuccess = { "Anotei${titulo?.let { ": $it" } ?: ""}" },
                    onFailure = { "Não consegui criar a nota: ${it.message}" },
                )
            }
        }
        "search_notes" -> {
            val consulta = input.optString("consulta")
            if (consulta.isBlank()) {
                "Não consegui buscar: consulta ausente"
            } else {
                runCatching { memoryRepository.searchNotes(consulta) }.fold(
                    onSuccess = { results ->
                        if (results.isEmpty()) {
                            "Nenhuma nota encontrada para \"$consulta\""
                        } else {
                            results.joinToString("\n") { note ->
                                val trecho = note.conteudo?.take(200) ?: note.checklist.joinToString(", ") { it.texto }
                                "- ${note.titulo ?: "(sem título)"}: $trecho"
                            }
                        }
                    },
                    onFailure = { "Não consegui buscar as notas: ${it.message}" },
                )
            }
        }
        "update_note" -> {
            val tituloDaNota = input.optString("titulo_da_nota")
            val texto = input.optString("texto").ifBlank { null }
            val substituir = input.optBoolean("substituir", false)
            val novosItens = input.optJSONArray("itens")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
            if (tituloDaNota.isBlank() || (texto == null && novosItens.isEmpty())) {
                "Não consegui atualizar a nota: título ausente, ou nem texto nem itens foram informados"
            } else {
                runCatching {
                    val existing = memoryRepository.findNoteByTitle(tituloDaNota) ?: error("Nota não encontrada")
                    val id = existing.id ?: error("Nota sem id")
                    val fields = JSONObject()
                    if (texto != null) {
                        val novoConteudo = if (substituir || existing.conteudo.isNullOrBlank()) texto else "${existing.conteudo}\n$texto"
                        fields.put("conteudo", novoConteudo)
                    }
                    if (novosItens.isNotEmpty()) {
                        // A checklist's items are a single jsonb array written whole (see
                        // MemoryNote.checklist's own doc) — "atualizar" means "append to the
                        // existing array locally, then write the full array back", not a partial
                        // update. Missing this is exactly the bug this branch used to have:
                        // update_note only ever wrote `conteudo`, so adding items to a checklist
                        // note silently landed in a field the Notas screen doesn't render for a
                        // checklist, making the update invisible even though it "succeeded".
                        val baseOrdem = existing.checklist.size
                        val checklistJson = JSONArray()
                        existing.checklist.forEach { item ->
                            checklistJson.put(JSONObject().put("texto", item.texto).put("concluido", item.concluido).put("ordem", item.ordem))
                        }
                        novosItens.forEachIndexed { index, texto ->
                            checklistJson.put(JSONObject().put("texto", texto).put("concluido", false).put("ordem", baseOrdem + index))
                        }
                        fields.put("checklist", checklistJson)
                    }
                    memoryRepository.updateNote(id, fields)
                }.fold(
                    onSuccess = { "Atualizei a nota '$tituloDaNota'" },
                    onFailure = { "Não consegui atualizar a nota ($tituloDaNota): ${it.message}" },
                )
            }
        }
        "create_project" -> {
            val nome = input.optString("nome")
            val descricao = input.optString("descricao").ifBlank { null }
            val objetivo = input.optString("objetivo").ifBlank { null }
            if (nome.isBlank()) {
                "Não consegui salvar o projeto: nome ausente"
            } else {
                runCatching {
                    // Same "find first, patch instead of duplicate" shape as save_fact/save_person —
                    // a project mentioned a second time should update the one row, not create a twin.
                    val existing = runCatching { memoryRepository.findProjectByName(nome) }.getOrNull()
                    val existingId = existing?.id
                    if (existingId != null) {
                        val fields = JSONObject()
                        descricao?.let { fields.put("descricao", it) }
                        objetivo?.let { fields.put("objetivo", it) }
                        if (fields.length() > 0) memoryRepository.updateProject(existingId, fields)
                    } else {
                        memoryRepository.addProject(
                            MemoryProject(
                                nome = nome,
                                descricao = descricao,
                                objetivo = objetivo,
                                origem = "inferred",
                                confidence = 0.9f,
                                confirmedByUser = false,
                            ),
                        )
                    }
                }.fold(
                    onSuccess = { "Salvei o projeto: $nome" },
                    onFailure = { "Não consegui salvar o projeto ($nome): ${it.message}" },
                )
            }
        }
        "update_project" -> {
            val nome = input.optString("nome")
            val status = input.optString("status").ifBlank { null }
            val objetivo = input.optString("objetivo").ifBlank { null }
            val proximaAcao = input.optString("proxima_acao").ifBlank { null }
            val prazo = input.optString("prazo").ifBlank { null }
            if (nome.isBlank()) {
                "Não consegui atualizar o projeto: nome ausente"
            } else {
                runCatching {
                    val existing = memoryRepository.findProjectByName(nome) ?: error("Projeto '$nome' não encontrado")
                    val id = existing.id ?: error("Projeto sem id")
                    val fields = JSONObject()
                    status?.let { fields.put("status", it) }
                    objetivo?.let { fields.put("objetivo", it) }
                    proximaAcao?.let { fields.put("proxima_acao", it) }
                    prazo?.let { fields.put("deadline", it) }
                    if (fields.length() > 0) memoryRepository.updateProject(id, fields)
                }.fold(
                    onSuccess = { "Atualizei o projeto $nome." },
                    onFailure = { "Não consegui atualizar o projeto ($nome): ${it.message}" },
                )
            }
        }
        "update_working_memory" -> {
            val objetivo = input.optString("objetivo")
            val proximoPasso = input.optString("proximo_passo").ifBlank { null }
            if (objetivo.isBlank()) {
                "Não consegui salvar o contexto de trabalho: objetivo ausente"
            } else {
                runCatching {
                    val valor = JSONObject().put("objetivo", objetivo)
                    proximoPasso?.let { valor.put("proximo_passo", it) }
                    memoryRepository.setContext(MemoryContext(chave = "working_memory", valor = valor.toString(), importance = 0.6f))
                }.fold(
                    onSuccess = { "Anotado: $objetivo${proximoPasso?.let { " → $it" } ?: ""}" },
                    onFailure = { "Não consegui salvar o contexto de trabalho: ${it.message}" },
                )
            }
        }
        "link_person" -> {
            val pessoa = input.optString("pessoa").ifBlank { null }
            val relacionadoA = input.optString("relacionado_a").ifBlank { null }
            if (pessoa == null || relacionadoA == null) {
                "Não consegui associar: nome da pessoa ou relação ausente"
            } else {
                runCatching {
                    val existing = memoryRepository.findPersonByName(pessoa)
                        ?: error("pessoa não encontrada na memória — salve com save_person primeiro")
                    val existingId = existing.id ?: error("pessoa sem id")
                    if (relacionadoA !in existing.assuntosAssociados) {
                        memoryRepository.correct(
                            tabelaOrigem = "memory_people",
                            registroId = existingId,
                            conhecimentoAntigo = existing.assuntosAssociados.joinToString().ifBlank { null },
                            conhecimentoNovo = (existing.assuntosAssociados + relacionadoA).joinToString(),
                            motivo = "Nova associação adicionada",
                        )
                        memoryRepository.addPerson(
                            existing.copy(
                                id = null,
                                assuntosAssociados = existing.assuntosAssociados + relacionadoA,
                                origem = "inferred",
                                confidence = 0.8f,
                                confirmedByUser = false,
                            ),
                        )
                    }
                }.fold(
                    onSuccess = { "Associei $pessoa a $relacionadoA" },
                    onFailure = { "Não consegui associar: ${it.message}" },
                )
            }
        }
        "find_related_people" -> {
            val tag = input.optString("relacionado_a").ifBlank { null }
            if (tag == null) {
                "Não consegui buscar: termo ausente"
            } else {
                runCatching { memoryRepository.peopleAssociatedWith(tag) }.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) {
                            "Nenhuma pessoa associada a '$tag' encontrada"
                        } else {
                            "Pessoas associadas a '$tag': " + list.joinToString { p -> p.nome + (p.relacao?.let { " ($it)" } ?: "") }
                        }
                    },
                    onFailure = { "Não consegui buscar: ${it.message}" },
                )
            }
        }
        "create_automation" -> {
            val gatilhoStr = input.optString("gatilho").ifBlank { null }
            val acaoStr = input.optString("acao").ifBlank { null }
            if (gatilhoStr == null || acaoStr == null) {
                "Não consegui criar a automação: gatilho ou ação ausente"
            } else {
                val trigger = parseAutomationTrigger(gatilhoStr)
                val weekdayValues = input.optJSONArray("dias_semana")
                val triggerWithDays = if (trigger is AutomationTrigger.DailyTime && weekdayValues != null) {
                    val days = buildSet {
                        for (index in 0 until weekdayValues.length()) {
                            com.unyx.atlas.feature.chat.automation.parseWeekday(weekdayValues.optString(index))?.let(::add)
                        }
                    }
                    if (days.size == weekdayValues.length()) trigger.copy(daysOfWeek = days) else null
                } else trigger
                val action = parseAutomationAction(acaoStr)
                if (triggerWithDays == null || action == null) {
                    if (triggerWithDays == null) {
                        "Não consegui criar a automação: gatilho ou dias da semana inválidos. Use carregador, fone, bateria baixa ou horário diário; para rotinas por local, configure em Automações e salve o local enquanto estiver nele."
                    } else {
                        "Não consegui criar a automação: essa ação não é compatível. As opções são abrir um app, ajustar volume ou brilho e mostrar uma notificação."
                    }
                } else {
                    val depoisDas = if (input.has("depois_das")) input.optInt("depois_das") else null
                    val antesDas = if (input.has("antes_das")) input.optInt("antes_das") else null
                    if ((depoisDas != null && depoisDas !in 0..23) || (antesDas != null && antesDas !in 0..23)) {
                        "Não consegui criar a automação: o horário da condição deve ficar entre 0 e 23."
                    } else {
                    val condition = if (depoisDas != null || antesDas != null) {
                        AutomationCondition(afterHour = depoisDas, beforeHour = antesDas)
                    } else {
                        null
                    }
                    val label = input.optString("label").ifBlank { "${gatilhoStr} → ${acaoStr}" }
                    val automation = Automation(
                        id = AutomationStore.newId(),
                        label = label,
                        trigger = triggerWithDays,
                        action = action,
                        condition = condition,
                    )
                    automationStore.add(automation)
                    automationGeofenceManager.sync()
                    "Automação criada. ${automationSummary(automation)}"
                    }
                }
            }
        }
        "list_automations" -> {
            val automations = automationStore.all()
            if (automations.isEmpty()) {
                "Nenhuma automação criada ainda"
            } else {
                automations.joinToString("; ") { "${it.label}: ${automationSummary(it)}${if (!it.enabled) " (desativada)" else ""}" }
            }
        }
        "delete_automation" -> {
            val descricao = input.optString("descricao").ifBlank { null }
            if (descricao == null) {
                "Não consegui remover: descrição ausente"
            } else {
                val query = descricao.lowercase()
                val match = automationStore.all().firstOrNull { it.label.lowercase().contains(query) }
                if (match == null) {
                    "Não encontrei nenhuma automação parecida com '$descricao'"
                } else {
                    automationStore.remove(match.id)
                    automationGeofenceManager.sync()
                    "Removi a automação: ${match.label}"
                }
            }
        }
        "get_capabilities" -> "As automações do Atlas podem reagir ao carregador, ao fone, à bateria baixa, a um horário em dias da semana ou à chegada/saída de locais salvos. Horários podem atrasar alguns minutos; localização precisa da permissão 'Permitir o tempo todo' do Android e pode ter alguns minutos de atraso. Ações: abrir um app, ajustar volume ou brilho e mostrar uma notificação. O Android não permite que o Atlas ligue/desligue Wi-Fi ou dados móveis diretamente; posso abrir o painel de Internet para você escolher."
        "open_internet_panel" -> {
            runCatching {
                val panelAction = "android.settings.panel.action.INTERNET_CONNECTIVITY"
                val panelIntent = Intent(panelAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    appContext.startActivity(panelIntent)
                } catch (_: Exception) {
                    appContext.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.fold(
                onSuccess = { "Abri o painel de Internet do Android. Escolha Wi-Fi ou dados móveis por lá." },
                onFailure = { "Não consegui abrir as configurações de Internet do Android." },
            )
        }
        "open_app" -> {
            val nomeApp = input.optString("nome_app")
            if (nomeApp.isBlank()) {
                "Não consegui abrir: nome do app não informado"
            } else {
                val app = resolveApp(nomeApp)
                if (app == null) {
                    "Não consegui abrir $nomeApp: nenhum app encontrado com esse nome"
                } else {
                    appRepository.launch(app).fold(
                        onSuccess = { "Abri ${app.label}" },
                        onFailure = { "Não consegui abrir ${app.label}: ${it.message}" },
                    )
                }
            }
        }
        "get_weather" -> {
            if (!weatherRepository.hasPermission()) {
                "Não consigo saber o clima: sem permissão de localização"
            } else {
                // Await this query's freshness-checked result, not an old non-null widget value.
                val current = withTimeoutOrNull(12_000) { weatherRepository.getFresh() }
                if (current == null) {
                    "Não consegui obter o clima agora"
                } else {
                    val local = current.city?.let { " em $it" } ?: ""
                    "Temperatura atual$local: ${current.temperatureC.roundToInt()}°C, ${weatherDescription(current.weatherCode)}"
                }
            }
        }
        "set_alarm" -> {
            val hora = input.optInt("hora", -1)
            val minuto = input.optInt("minuto", -1)
            val rotulo = input.optString("rotulo").ifBlank { null }
            if (hora !in 0..23 || minuto !in 0..59) {
                "Não consegui criar o alarme: horário inválido"
            } else {
                runCatching {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hora)
                        putExtra(AlarmClock.EXTRA_MINUTES, minuto)
                        rotulo?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                }.fold(
                    onSuccess = { "Criei o alarme para %02d:%02d".format(hora, minuto) },
                    onFailure = { "Não consegui criar o alarme: ${it.message}" },
                )
            }
        }
        "start_timer" -> {
            val minutos = input.optDouble("duracao_minutos", -1.0)
            val rotulo = input.optString("rotulo").ifBlank { "Timer" }
            if (!minutos.isFinite() || minutos <= 0.0) {
                "Não consegui iniciar o timer: duração inválida"
            } else {
                val seconds = (minutos * 60).coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt().coerceAtLeast(1)
                runCatching {
                    appContext.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, rotulo)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.fold(onSuccess = { "Temporizador solicitado no relógio: $rotulo" },
                    onFailure = { "Não foi possível abrir o temporizador do Android." })
            }
        }
        "set_volume" -> {
            val nivel = input.optInt("nivel", -1)
            if (nivel !in 0..100) {
                "Não consegui ajustar o volume: nível inválido"
            } else {
                val streamType = when (input.optString("tipo").lowercase()) {
                    "alarme", "alarm" -> AudioManager.STREAM_ALARM
                    "chamada", "call", "ligacao" -> AudioManager.STREAM_VOICE_CALL
                    "toque", "ring" -> AudioManager.STREAM_RING
                    else -> AudioManager.STREAM_MUSIC
                }
                runCatching {
                    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = audioManager.getStreamMaxVolume(streamType)
                    val target = (nivel / 100.0 * max).roundToInt().coerceIn(0, max)
                    audioManager.setStreamVolume(streamType, target, AudioManager.FLAG_SHOW_UI)
                    // setStreamVolume can silently no-op instead of throwing — e.g. "Não perturbe"
                    // active without notification-policy access blocks a ring/alarm-stream change
                    // with no exception at all — so a real user saw Atlas claim success while the
                    // volume never actually moved. Reading the level back catches that: a mismatch
                    // here means the OS ignored the call, which is reported as a real failure
                    // instead of a false "ajustei".
                    val actual = audioManager.getStreamVolume(streamType)
                    require(actual == target) {
                        "o sistema não aplicou a mudança — verifique se o modo Não perturbe está ativo"
                    }
                }.fold(
                    onSuccess = { "Ajustei o volume para $nivel%" },
                    onFailure = { "Não consegui ajustar o volume: ${it.message}" },
                )
            }
        }
        "call_person" -> {
            val nome = input.optString("nome").ifBlank { null }
            if (nome == null) {
                "Não consegui ligar: nome ausente"
            } else {
                val person = runCatching { memoryRepository.findPersonByName(nome) }.getOrNull()
                val telefone = person?.telefone?.filter { it.isDigit() }
                if (telefone.isNullOrBlank()) {
                    "Não consegui ligar: não tenho o telefone de $nome salvo. Peça para o usuário " +
                        "informar o número, depois use save_person de novo incluindo o telefone."
                } else {
                    runCatching {
                        val uri = Uri.parse("tel:$telefone")
                        appContext.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.fold(
                        onSuccess = { "Abri o discador para ${person.nome}" },
                        onFailure = { "Não consegui abrir o discador: ${it.message}" },
                    )
                }
            }
        }
        "send_whatsapp_message" -> {
            val nome = input.optString("nome")
            val mensagem = input.optString("mensagem")
            if (nome.isBlank() || mensagem.isBlank()) {
                "Não consegui enviar: nome ou mensagem ausente"
            } else {
                val person = runCatching { memoryRepository.findPersonByName(nome) }.getOrNull()
                val telefone = person?.telefone?.filter { it.isDigit() }
                if (telefone.isNullOrBlank()) {
                    "Não consegui enviar: não tenho o telefone de $nome salvo. Peça para o usuário " +
                        "informar o número, depois use save_person de novo incluindo o telefone."
                } else {
                    runCatching {
                        val uri = Uri.parse("https://wa.me/$telefone?text=${Uri.encode(mensagem)}")
                        appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.fold(
                        onSuccess = { "Abri o WhatsApp com a mensagem pronta para ${person.nome}" },
                        onFailure = { "Não consegui abrir o WhatsApp: ${it.message}" },
                    )
                }
            }
        }
        "create_reminder" -> {
            val titulo = input.optString("titulo").ifBlank { null }
            val dataStr = input.optString("data").ifBlank { null }
            if (titulo == null || dataStr == null) {
                "Não consegui criar o lembrete: título ou data ausente"
            } else if (appContext.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                "Não consegui criar o lembrete: preciso de uma permissão de calendário que ainda não foi concedida"
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val calendarId = findWritableCalendarId() ?: error("Nenhum calendário disponível no aparelho")
                        val parts = dataStr.split("-").map { it.toInt() }
                        val hora = input.optInt("hora", -1)
                        val minuto = input.optInt("minuto", 0)
                        val allDay = hora !in 0..23
                        val cal = java.util.Calendar.getInstance().apply {
                            set(parts[0], parts[1] - 1, parts[2], if (allDay) 0 else hora, if (allDay) 0 else minuto, 0)
                        }
                        val start = cal.timeInMillis
                        val values = ContentValues().apply {
                            put(CalendarContract.Events.CALENDAR_ID, calendarId)
                            put(CalendarContract.Events.TITLE, titulo)
                            input.optString("descricao").ifBlank { null }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                            put(CalendarContract.Events.DTSTART, start)
                            put(CalendarContract.Events.DTEND, start + if (allDay) 24 * 60 * 60 * 1000L else 60 * 60 * 1000L)
                            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                        }
                        appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("Falha ao inserir evento")
                    }.fold(
                        onSuccess = { "Criei o lembrete '$titulo' para $dataStr" },
                        onFailure = { "Não consegui criar o lembrete: ${it.message}" },
                    )
                }
            }
        }
        "set_brightness" -> {
            val nivel = input.optInt("nivel", -1)
            if (nivel !in 0..100) {
                "Não consegui ajustar o brilho: nível inválido"
            } else if (!Settings.System.canWrite(appContext)) {
                "Não consegui ajustar o brilho: preciso de uma permissão especial que ainda não foi concedida"
            } else {
                runCatching {
                    Settings.System.putInt(
                        appContext.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        (nivel / 100.0 * 255).roundToInt().coerceIn(0, 255),
                    )
                }.fold(
                    onSuccess = { "Ajustei o brilho para $nivel%" },
                    onFailure = { "Não consegui ajustar o brilho: ${it.message}" },
                )
            }
        }
        else -> "Ferramenta desconhecida: $name"
        }
        // Publish, don't call directly — see AgentEventBus's doc. ChatViewModel.subscribeToAgentEvents()
        // is what actually runs logEpisode/routine-tracking, regardless of which screen (full chat or
        // the lightweight Assistant) triggered this call.
        agentEventBus.publish(
            AgentEvent(
                agent = TOOL_AGENT[name] ?: Agent.DEVICE,
                toolName = name,
                input = input,
                result = result,
                success = !result.startsWith("Não consegui"),
            ),
        )
        return result
    }

    /** Resolves a spoken/typed app name (messier than an app-drawer search box — accents, spaces,
     * partial names) to an installed [AppItem]. Exact normalized-label match wins; otherwise the
     * shortest label containing (or contained by) the query, on the theory that "whatsapp" should
     * prefer "WhatsApp" over some unrelated app that merely mentions it in a longer name. */
    private suspend fun resolveApp(nomeApp: String): AppItem? {
        val query = nomeApp.normalizedForAppMatch()
        val apps = runCatching { appRepository.apps.first() }.getOrDefault(emptyList())
        apps.firstOrNull { it.label.normalizedForAppMatch() == query }?.let { return it }
        return apps
            .filter { it.label.normalizedForAppMatch().let { label -> label.contains(query) || query.contains(label) } }
            .minByOrNull { it.label.length }
    }

    private fun String.normalizedForAppMatch(): String = lowercase().filter { it.isLetterOrDigit() }

    /** Open-Meteo's WMO weather codes → short Portuguese phrase, for Claude to paraphrase — this
     * result never reaches the user verbatim (see actionConfirmationText), so being a bit terse/
     * technical here is fine. Not exhaustive, just the common buckets; anything unlisted falls back
     * to a vague-but-honest phrase rather than guessing a specific condition that might be wrong. */
    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "céu limpo"
        1, 2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "neblina"
        51, 53, 55, 56, 57 -> "garoa"
        61, 63, 65, 66, 67 -> "chuva"
        71, 73, 75, 77 -> "neve"
        80, 81, 82 -> "pancadas de chuva"
        85, 86 -> "pancadas de neve"
        95, 96, 99 -> "trovoada"
        else -> "tempo variável"
    }

    /** First calendar the device can actually write to (CAL_ACCESS_CONTRIBUTOR or better) — a
     * device may have several (personal, work, a read-only holidays calendar); picking the first
     * writable one avoids the more common failure of landing on a read-only calendar and getting a
     * silent/cryptic insert failure. */
    private fun findWritableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        appContext.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val accessCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (cursor.moveToNext()) {
                if (cursor.getInt(accessCol) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    return cursor.getLong(idCol)
                }
            }
        }
        return null
    }
}
