# Atlas Connect (chat humano-a-humano) — status da UI

**Nomenclatura final (2026-10-17, decidida pelo usuário):** o módulo chama-se **Atlas Connect**
(`feature:connect`, pacote `com.unyx.atlas.feature.connect`) — "feature:people" foi o nome
provisório da primeira versão, já renomeado. A tela principal dele chama-se **"Conexões"**. O
assistente de IA continua "Chat Atlas"/"Conversas" exatamente como já era — nada nisso mudou.

**Depende de:** `ATLAS_CHAT_Plano_Arquitetura_Layouts_e_Telas.md` (plano de produto/UI,
responsável Marco Aurélio, "código atual ainda não inspecionado" — este documento é o registro
do que foi de fato inspecionado e decidido depois). Também depende do backend já construído:
`docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md` (cripto+storage+pairing+remote, 54 testes, schema
Supabase aplicado em produção).

## 1. Decisão crítica tomada antes de qualquer código (2026-10-17)

O plano original propõe caminhos como `feature/chat/conversations/`, `feature/chat/pairing/`,
assumindo que `feature/chat` está livre. **Não está.** Inventário real (ver §2) confirmou:
`feature/chat` é inteiramente o assistente de IA (`ChatScreen.kt`, `ChatViewModel.kt`,
`AtlasChatActivity.kt`, overlay flutuante, captura de tela, automações — nada disso é chat
humano-a-humano). Construir ali colidiria `ChatScreen.kt`/`ChatViewModel.kt` com classes que já
existem para outra coisa.

**Decisão do usuário:** novo módulo Gradle, batizado **Atlas Connect** (`feature:connect`),
mantendo `feature:chat` intocado (só IA). As rotas lógicas do plano (`ChatHome`,
`ChatConversation`, etc.) continuam valendo como nomes de rota — só o módulo/pacote muda.

## 2. Inventário (seção 2 do plano) — preenchido por inspeção real do código

| Área | Situação | Evidência |
| --- | --- | --- |
| Conta Atlas | Existente | `LoginScreen.kt` (dentro de `feature/chat`, não reaproveitável isoladamente ainda) |
| Aparelho autorizado (chat) | Ausente (UI) | Backend existe (`ChatPairingRepository.ensureDeviceRegistered`), zero tela |
| Contatos | Ausente | Nenhuma tela/ViewModel; `core/ui`'s `ContactAvatar.kt` existe mas não tem nenhum call site |
| NFC / QR Code | Ausente | Zero `NfcAdapter`/`HostApduService`/lib de QR no projeto |
| Sessões E2EE | Backend existente, zero UI | `feature/chat` não referencia `ChatSession`/`ChatPairingRepository` — confirmado, 0 ocorrências |
| Mensagens e filas (humano) | Ausente | `ChatHistoryStore.kt` é sobre histórico com a IA, não mensagens entre usuários |
| Histórico protegido | Parcial | Padrão Keystore existe (`EncryptedFile`/`MasterKey` no histórico da IA), não aplicado a chat humano |
| Perfil e avatar | Parcial | `ContactAvatar.kt` pronto e correto, mas não usado em nenhum lugar — "morto" até agora |
| Notificações | Existente (infra genérica) | Canais/`NotificationManager` usados em overlay/updater/notes; nenhum canal dedicado a chat humano |
| Voz e coletores (gate de privacidade) | Parcial | `VoiceSessionGate` é gate de concorrência, não de privacidade; `AssistantActivity` inicia voz sem checar contexto privado |
| Navegação e tema | Parcial/Ausente (nav) / Existente (tema) | Sem `NavHost` real no projeto (cada feature é uma Activity separada); `androidx-navigation3` está no catálogo mas nunca usado. Tema: `core/designsystem`'s `LauncherTheme`/`Color.kt` tem exatamente os tons escuro+azul-lilás que o plano propõe (`#111318`≈`#101216`, `primary #B0C6FF`≈destaque `#B1C4FF`) — **não é um tema novo, é o que já existe**. |

## 3. Decisão de tema (LAY-02: "sem cores duplicadas dispersas")

O plano propõe uma tabela de tokens (§7) que, ao comparar com o código, já existe quase
idêntica em dois lugares:
- `core/designsystem`'s `LauncherTheme`/`DarkColorScheme` (Material3) — fundo, texto, destaque.
- `core/ui`'s `ExpressivePalette`/`ExpressiveTheme` — tem `surfaceHi` (superfície elevada) e
  `dim` (texto secundário), já usado em `AtlasSecurityContent.kt`.

**Decisão:** reusar `LauncherTheme`/`MaterialTheme.colorScheme` como base (via `core/designsystem`,
já é o padrão em todo o app) em vez de criar uma paleta nova. As únicas cores genuinamente novas
são as do balão de mensagem (`#303B60` enviado / `#262831` recebido, §7) — específicas de chat,
sem equivalente nos tokens gerais, mantidas localmente em `MessageBubble.kt` por ora. Se aparecer
um segundo lugar precisando dessas mesmas cores, promover para `core/designsystem` então — não
antes.

## 4. Progresso concreto

**Módulo `feature:connect` criado** (`feature/connect/build.gradle.kts`, plugin
`atlas.android.feature` — já dá Compose+Material3+Hilt+lifecycle-compose de graça). Depende de
`core:designsystem`, `core:ui` (pelo `ContactAvatar`) e dos quatro módulos de chat já prontos
(`core:chat-crypto/storage/pairing/remote`) para quando a hora de conectar dados reais chegar.

**Etapa 1 do plano ("Tokens e componentes base") — entrega parcial:**
- `ConversationRowModel`/`MessageBubbleModel`/`MessageDirection`/`MessageDeliveryState`
  (`model/ConnectUiModels.kt`) — modelos de apresentação puros, sem tipo de domínio/cripto
  cruzando a fronteira, por decisão explícita do plano §15/§16.
- `ConversationRow` — reusa `ContactAvatar` (não recria um "AtlasAvatar" novo). 3 previews
  (badge não-lido, nome longo, "verificação necessária").
- `MessageBubble` — recebida à esquerda/enviada à direita, 82% de largura máxima, estado de
  entrega em texto por extenso (nunca um ícone genérico — "Entregue" ≠ "Enviada" ≠ "Entrega não
  confirmada", por exigência explícita do plano §10). 3 previews (entregue, recebida, texto
  longo + falha).
- `./gradlew :feature:connect:compileDebugKotlin` e `:assembleRelease` compilam limpo.

**O que falta da Etapa 1:** `ChatTopBar`, `EmptyChatState`, `ChatEntryAction`,
`SecurityBanner`/`ConnectionBanner`, `SensitiveActionDialog` (§15) — esses fazem mais sentido
construir junto com as telas que os usam (Etapa 2), não isolados sem nenhuma tela pra montar.

## 5. Entrada a partir do Chat Atlas (2026-10-17)

Decisão do usuário: o ícone de menu lateral do cabeçalho do Chat Atlas (`ChatHeader`'s
`IconButton(onClick = onOpenConversations)`, em `ChatScreen.kt`) deixa de abrir direto o drawer
de histórico (`ModalNavigationDrawer`/`ConversationDrawerContent`, em `AtlasChatScreen.kt`) e
passa a abrir um `ModalBottomSheet` pequeno (`ChatEntryMenuSheet`, novo, no final de
`AtlasChatScreen.kt`) com três opções:

1. **"Conversa Atlas"** — mesmo fluxo de sempre (`viewModel.newConversation()`).
2. **"Histórico"** — abre exatamente o mesmo drawer/conteúdo de antes; nada na UI do histórico em
   si mudou, só como se chega até ela.
3. **"Atlas Connect"** — chama `onOpenAtlasConnect()`, um novo parâmetro de
   `AtlasChatScreen`/`ChatScreen`, ligado em `AtlasChatActivity.kt` a
   `startActivity(Intent(this, AtlasConnectActivity::class.java))` — mesmo padrão de
   `onOpenNotes`.

**`AtlasConnectActivity`** (novo, `app/src/main/kotlin/.../AtlasConnectActivity.kt`) — Activity
dedicada, independente de `AtlasChatActivity`, exatamente como `NotesActivity`: própria
`taskAffinity` (`${applicationId}.connect`), próprio ícone de app-drawer (`android:label
="@string/atlas_connect_launcher_label"` = "Atlas Connect", `strings.xml`), hospeda
`ConnectionsScreen` do novo módulo. Registrada no `AndroidManifest.xml` junto das outras
Activities de feature (Notes, Settings, etc.).

**`ConnectionsScreen`** (novo, `feature/connect/.../ConnectionsScreen.kt`) — tela "Conexões":
cabeçalho com voltar + título, corpo com um estado vazio honesto ("Nenhuma conexão ainda... o
pareamento por NFC ou QR ainda está sendo construído") em vez de fingir uma função que não existe
— decisão deliberada per plano §23 ("se a nova UI estiver pronta antes de uma função, ocultar sua
ação ou apresentar indisponibilidade explícita, sem simular sucesso"). Ainda não usa
`ConversationRow`/`MessageBubble` — não há nada real pra listar ainda.

Compilação confirmada: `:feature:chat:compileDebugKotlin`, `:feature:connect:compileDebugKotlin`,
`:app:compileDebugKotlin` todos limpos (zero erro, só warnings pré-existentes sem relação com
essa mudança); `:app:assembleRelease` em andamento de verificação.

## 6. Pareamento por QR Code implementado (2026-10-17)

O maior buraco do inventário (§2: "NFC/QR Code — Ausente") está fechado para QR (NFC continua
deliberadamente fora de escopo — ver `ConnectPairingViewModel`'s doc). Novas dependências
adicionadas (`gradle/libs.versions.toml`, com comentário explicando a categoria de licença de
cada uma):

- **CameraX** (`camera-core/camera2/lifecycle/view`, Apache-2.0) — preview da câmera.
- **ML Kit Barcode Scanning** (`com.google.mlkit:barcode-scanning`, mesma categoria já aceita
  pra `mlkit-text-recognition`, que já existe no projeto) — decodifica o QR a partir dos frames
  da câmera, roda no aparelho.
- **ZXing `core`** (Apache-2.0, Java puro, sem protocolo/cripto próprio) — usado só para
  *desenhar* nosso próprio QR a partir dos bytes do convite; ML Kit não tem encoder.

**Novo em `feature/connect`:**
- `qr/QrCodeGenerator.kt` — desenha o `PairingInvitation.serialize()` como bitmap QR (correção de
  erro nível H, pensando em "foto de uma tela fotografando outra tela", não etiqueta impressa).
- `qr/QrCodeAnalyzer.kt` — `ImageAnalysis.Analyzer` que decodifica via ML Kit e chama de volta na
  main thread (não confia na thread do Play Services Task).
- `pairing/ConnectPairingViewModel.kt` — a máquina de estado da UI sobre o `ChatPairingRepository`
  já pronto: lado A (criar convite → mostrar QR → fazer polling de `fetchClaim` → mostrar
  fingerprint → `confirmAndEstablish`) e lado B (escanear → `acceptInvite` → mostrar fingerprint
  → fazer polling de `waitForConfirmation`). Achado e corrigido durante a implementação: o
  `acceptInvite` do repositório não recebe nome de exibição nenhum — pedir um nome do lado B antes
  de escanear seria um campo que não faz nada; removido esse passo, só o lado A (que de fato usa
  o nome no convite) pede.
- `pairing/AddConnectionScreen.kt` — escolha de método (QR apresentar/ler; NFC mostrado como "não
  disponível ainda", nunca como botão fingindo funcionar), tela de nome, tela de apresentação (QR
  + aguardando), tela de leitura (câmera + fallback real pra "apresentar meu QR" se a permissão
  for negada, não só uma frase sugerindo isso — exigência explícita do plano §11), tela de
  verificação de fingerprint (idêntica pros dois lados, com "Confirmo"/"Cancelar"), tela de
  sucesso, tela de erro.
- `AtlasConnectScreen.kt` (novo, raiz) — alterna entre "Conexões" e "Adicionar conexão" por estado
  local, mesmo padrão de `NotesScreen`. `AtlasConnectActivity` agora hospeda esse root em vez de
  `ConnectionsScreen` direto.
- `ConnectionsScreen` ganhou um FAB "Adicionar conexão" de verdade (antes só dizia "ainda sendo
  construído" — agora funciona).

Permissão `android.permission.CAMERA` adicionada ao manifesto, pedida só na hora de usar a tela
de leitura (mesmo padrão de `RECORD_AUDIO`/calendário já usado no resto do app).

Compilação confirmada limpa: `:feature:connect:compileDebugKotlin`, `:app:compileDebugKotlin`,
`:app:assembleRelease` (release completo, R8 incluído).

## 7. `chat:sync` — transporte de mensagens implementado e aplicado (2026-10-17)

O item 1 da lista acima está fechado. Schema novo
(`supabase/migrations/20261017020000_atlas_chat_sync.sql`, cópia em
`core/memory/schema/011_chat_sync.sql`) **já aplicado em produção** (`supabase migration list`
confirma local=remote; `information_schema` confirma as 3 funções no banco):

- `atlas_chat_message_envelopes` — fila de envelopes em trânsito, sem grant nenhum pra
  `authenticated` (nem SELECT) — tudo passa pelas 3 RPCs abaixo, todas `security definer`, per
  plano §14.1 ("API restrita", não RLS com `auth.uid()` puro).
- `atlas_chat_send_envelope` — **implementa o T-06 de verdade**: compara o dispositivo ativo do
  remetente contra o `device_low_id`/`device_high_id` fixado em
  `atlas_chat_contact_relationships` no momento da confirmação; se não bater (identidade mudou
  desde o pareamento), a função rejeita o envio em vez de confiar silenciosamente. Idempotente
  por `(conversation_id, sender_device_id, sequence)` — reenviar o mesmo envelope é um no-op;
  ciphertext diferente pra mesma posição é rejeitado (RF-08/T-10).
- `atlas_chat_fetch_pending_envelopes` — retorna só o que está pendente pro dispositivo ativo de
  quem chama.
- `atlas_chat_ack_envelope` — **deleta** o envelope (não só marca) — "aceito pelo servidor",
  explicitamente **não** é o recibo E2EE do plano (essa camada — uma segunda mensagem cifrada
  provando que o outro lado decifrou — não foi construída; ver nota de escopo no próprio
  arquivo da migração). Isso significa que hoje só dá pra mostrar honestamente "Enviada", nunca
  "Entregue", na tabela de estados do plano §10.

**Novo em `core/chat-remote`:**
- `ChatMessageRemoteClient`/`SupabaseChatMessageRemoteClient` — chama as 3 RPCs via
  `SupabaseRestClient`.
- `ChatMessagingRepository` — `send()` (cifra com a sessão já estabelecida, persiste local antes
  de qualquer chamada de rede — T-10 — depois envia), `resendPending()` (reenvia só o que já
  está persistido localmente, nunca re-cifra — re-cifrar avançaria o ratchet e desincronizaria os
  dois lados), `fetchAndDecryptNew()` (busca, agrupa por conversa, decifra, persiste o lado
  recebedor antes do ack — T-11 — confirma; um envelope rejeitado por GCM também é confirmado,
  pra não entrar num loop de retry eterno sobre algo que nunca vai decifrar).
- Ligado ao Hilt em `ChatRemoteModule`.

**Bug pego nos próprios testes, não no código de produção:** o primeiro teste escrito
(`ChatMessagingRepositoryTest`) usava um único `ChatConversationStore` compartilhado entre
"alice" e "bob" — como as duas sessões são persistidas sob a mesma chave
(`session:$conversationId`), a segunda sobrescrevia a primeira, corrompendo o teste inteiro (4 de
9 testes falharam). Produção nunca teria esse problema (cada aparelho tem seu próprio
`AtlasSecurePreferences` local) — era puramente um erro de setup do teste, corrigido dando um
`ChatConversationStore`/`FakeSharedPreferences` separado pra cada lado, igual ao padrão já usado
em `ChatPairingRepositoryTest`. 9 testes, 0 falhas depois do fix.

Compilação confirmada: `:core:chat-remote:test` (9/9), `:app:compileDebugKotlin`, e um
`:app:assembleDebug` completo em verificação pra garantir que o grafo do Hilt com os novos
`@Provides` está coerente de ponta a ponta (não só compilação parcial).

## 8. Telas reais ligadas ao backend (2026-10-17)

O item 1 da lista acima está fechado — "Conexões" e a conversa individual (T03 do plano) agora
são reais, não mais vitrines vazias. Duas lacunas de dados foram descobertas e fechadas no
caminho, nenhuma delas óbvia até tentar construir a tela de verdade:

**1. Não existia como listar conversas nem saber o nome de quem está do outro lado.**
`ChatConversationStore` só permitia carregar uma sessão se você já soubesse o `conversationId` —
nada enumerava "quais conversas existem". E nada em `chat:pairing`/`chat:remote` persistia
localmente quem é a pessoa do outro lado depois que a tela de pareamento fechava. Resolvido com:
- `ChatConversationStore.listConversationIds()` (novo) — enumera toda sessão guardada localmente.
- `ChatContactStore` (novo, `core/chat-storage`) — guarda `displayName`/`remoteUserId`/
  `remoteDeviceId`/`pairedAtEpochSeconds` por `conversationId`. `ChatPairingRepository.
  confirmAndEstablish`/`waitForConfirmation` agora exigem um `contactDisplayName` e salvam esse
  registro no momento da confirmação — **não é um nome autodeclarado pela outra pessoa** (nada no
  protocolo atual carrega isso de volta), é o nome que CADA lado escolhe pra conexão, prático
  igual a "salvar um contato novo" em qualquer app. Do lado de quem escaneia o QR, o campo já vem
  pré-preenchido com o nome que a própria A colocou no convite (que B já tem, direto do cartão).

**2. Não existia nenhum histórico de mensagens local para exibir.**
`ChatConversationStore` só guarda estado do ratchet + outbox (envelopes cifrados ainda não
confirmados) — nunca um texto legível. `ChatMessageHistoryStore` (novo) guarda isso: lista de
`ChatMessageRecord` (sequência, direção, texto, horário) por conversa, idempotente por
`(direção, sequência)` já que as duas chains (envio/recebimento) numeram independentemente.

**3. Corrigido um contrato ruim em `ChatMessagingRepository.send()`.**
Antes, uma falha de rede fazia `send()` lançar exceção — mas isso significava que o chamador
NUNCA sabia qual sequência tinha sido cifrada quando a rede falhava, tornando impossível registrar
a mensagem no histórico local de forma consistente. Agora `send()` sempre completa a parte local
(cifra + persiste no outbox) e retorna o envelope de qualquer forma; só a tentativa de rede é
best-effort e silenciosa (o outbox já é a fonte de verdade de "o servidor recebeu ou não").
`DecryptedIncoming` (do lado de recebimento) ganhou `conversationId`/`sequence` pelo mesmo motivo
— faltavam pra registrar a mensagem recebida corretamente quando `fetchAndDecryptNew()` traz
mensagens de várias conversas numa única busca.

**Novo em `feature/connect`:**
- `ConnectionsViewModel` — monta a lista de "Conexões" de verdade a partir de
  `ChatContactStore`/`ChatMessageHistoryStore`, ordenada pela mensagem mais recente (ou pela data
  do pareamento, se ainda não há mensagens). Contagem de não lidas é sempre 0 — honesto, não
  inventado, já que nada rastreia leitura ainda.
- `ConversationViewModel`/`ConversationScreen` — conversa real: histórico local, campo de envio,
  `send()`/`retryPending()` via `ChatMessagingRepository`, e um loop de polling
  (`fetchAndDecryptNew()` a cada 4s) que persiste mensagens recebidas mesmo de conversas que não
  estão abertas na tela (só não atualiza a lista visível se não for a conversa aberta).
- `AtlasConnectScreen` ganhou um terceiro destino (`Conversation`), ainda via estado local, sem
  `NavHost`.

Compilação confirmada: `:core:chat-storage:test` (26 testes), `:core:chat-remote:test` (10
testes), `:feature:connect:compileDebugKotlin` — todos limpos. **72 testes no total agora** em
todo o `core/chat-*` (15+26+21+10), 0 falhas. `:app:assembleRelease` completo em verificação.

## 9. Bugs reais pegos no primeiro teste em aparelho físico (2026-10-17)

Três bugs encontrados ao testar de verdade pela primeira vez (dois aparelhos, release build) —
nenhum deles visível em compilação, testes com fake, ou revisão de código antes de rodar no
hardware. Registrados aqui com a causa raiz real, não só o sintoma.

**1. Botão/gesto de voltar não funcionava dentro do pareamento.** Causa raiz confirmada ao vivo
via ADB (toques nas coordenadas exatas do botão, via `uiautomator dump`, não mudavam nada):
nenhuma das três telas de `feature:connect` tinha `.statusBarsPadding()`. Como
`AtlasConnectActivity` usa `enableEdgeToEdge()`, o cabeçalho (com a seta de voltar) renderiza
*atrás* da barra de status — visível, mas o toque nunca chega ao botão. Toda outra tela do app já
usa `.statusBarsPadding()`/`.safeDrawingPadding()` no próprio cabeçalho; só essas três ficaram de
fora. Também faltava `BackHandler` nas telas (`AddConnectionScreen`, `ConversationScreen`) — sem
ele, o gesto/botão do SISTEMA caía no padrão do Android pra uma Activity raiz (`moveTaskToBack`,
visto no logcat), saindo do fluxo inteiro de uma vez em vez de voltar um passo. Corrigido nos
dois pontos, nas três telas.

**2. App fechava ao conceder permissão de câmera.** CameraX + ML Kit Barcode Scanning eram
dependências novas, nunca exercitadas no build de release (R8). Causa raiz: AGP 9 habilita o R8
"full mode", que remove os pontos de entrada reflexivos do ML Kit e os construtores que a
descoberta de componentes do Firebase precisa pra instanciar os registradores do ML Kit — o
`MlKitInitProvider` do scanner crashava antes de qualquer Activity abrir. Regras de ProGuard
adicionadas em `app/proguard-rules.pro` mantendo a superfície completa do ML Kit e os
construtores `ComponentRegistrar`.

**3. "Could not register this device" ao gerar QR (a partir da segunda tentativa).** Bug real na
função SQL `atlas_chat_register_device` (ver `supabase/migrations/20261017100000_atlas_chat_register_device_fix.sql`):
como `device_id` é determinístico (derivado da identidade local, nunca muda no mesmo aparelho) e
`ensureDeviceRegistered()` roda no início de TODO pareamento (não só uma vez), a função original
revogava a linha ativa existente e tentava inserir uma nova linha com o MESMO `device_id`
(chave primária da tabela) — violação de chave duplicada a partir da segunda chamada, sempre.
Corrigido trocando o INSERT simples por um upsert de verdade (`on conflict (device_id) do
update`) e excluindo o próprio `device_id` da varredura de revogação. **Confirmado funcionando
pelo usuário depois de aplicado.**

## 10. Confirmação ao vivo do fix de voltar (2026-10-17)

Reaberto o fluxo de pareamento no Moto G60 (release build) e testado via ADB
(`uiautomator dump` para achar os bounds reais do botão, já que o ícone em si não é clicável —
só o `IconButton` ao redor é): gerar um QR de verdade (confirmando também o fix #3 do registro de
dispositivo — nenhum erro "Could not register this device" dessa vez) e então voltar da tela do
QR. Dois toques de volta levaram exatamente de "Apresentar meu QR" → "Adicionar contato" →
"Conexões", um passo por vez, batendo com a lógica de `ConnectPairingViewModel.back()`
(`ENTERING_NAME`/fases intermediárias voltam um passo; `METHOD_CHOICE` sai do fluxo). **Fix #1
confirmado funcionando de verdade, não só compilando.** Fix #2 (crash de câmera) segue não
verificado ao vivo — não há câmera para apontar durante automação ADB remota.

## 11. Recibo E2EE implementado (2026-10-17, "pode continuar com a construcao")

Fechou a lacuna descrita no antigo §10: até aqui `ChatMessagingRepository.send()` só provava
"aceito pelo servidor", nunca "o outro lado realmente decifrou". Implementado exatamente como o
plano descreve em §12.3 — um recibo é só mais uma mensagem no mesmo canal cifrado, não um
mecanismo paralelo:

- **`ChatMessagePayload`** (novo, `core/chat-remote`) — framing de 1 byte dentro do plaintext já
  cifrado: `Text(String)` ou `DeliveryReceipt(forSequence: Long)`. `chat:crypto` continua sem
  saber o que carrega (só bytes); esta é a única camada acima dele que dá significado ao
  conteúdo.
- **`ChatMessagingRepository.fetchAndDecryptNew()`** — ao decifrar um `Text` com sucesso, envia
  automaticamente um `DeliveryReceipt` de volta pela MESMA sessão já em memória (sem re-restaurar
  do disco, evitando corrida com o `persistAfterReceive` que acabou de rodar). Ao decifrar um
  `DeliveryReceipt`, marca a sequência correspondente como entregue — e não gera outro recibo
  (plano: "Recibos não geram outros recibos E2EE, evitando ciclos"), nem aparece como mensagem
  visível.
- **`ChatConversationStore.markDelivered`/`deliveredSentSequences`** (novo) — mesmo padrão de
  chave-prefixo que `pendingOutbox` já usa; a existência da chave é o fato, sem precisar
  sincronizar um campo mutável em outro lugar.
- **`ConversationViewModel`** — `send()` agora chama `sendText()`; o estado de entrega de uma
  mensagem enviada passa a considerar `deliveredSentSequences` antes de cair em
  `SERVER_ACCEPTED`. O rótulo "Entregue" (que já existia pronto em `MessageBubble.kt`, nunca
  alimentado por dado real) agora aparece de verdade.

**10 novos testes** (`ChatMessagePayloadTest` ×7, mais 2 em `ChatMessagingRepositoryTest` — envio
dispara recibo automático; recibo não gera outro recibo — e 2 em `ChatConversationStoreTest`),
todos passando. `:feature:connect:compileDebugKotlin` e `:app:assembleRelease` limpos.
**Não testado em dois aparelhos físicos ainda** — só testes automatizados com remoto fake.

## 12. Tela "Detalhes da conexão" (2026-10-17, "pode continuar com a contrucao")

Plano §16 ("Detalhes do contato e segurança"), implementado como um subconjunto honesto do que o
plano descreve — sem telefone, recado ou avatar distribuído, porque essa infraestrutura
(distribuição de perfil, plano §5) não existe. O que é real e aparece na tela:

- Avatar por iniciais (`ContactAvatar`, já existia, sem foto — não há distribuição de avatar).
- Nome escolhido por este aparelho e data do pareamento.
- **Identidade verificada**: o código de segurança (`PairingFingerprint`) que os dois lados já
  comparavam presencialmente durante o pareamento (fase `VERIFY_AS_CREATOR`/`VERIFY_AS_CLAIMER`
  do `ConnectPairingViewModel`) — só que, até agora, esse valor nunca era persistido, só mostrado
  uma vez durante o fluxo. Agora `ChatContact` ganhou o campo `verifiedFingerprint`, preenchido em
  `ChatPairingRepository.confirmAndEstablish()`/`waitForConfirmation()` (o fingerprint já estava
  calculado ali, só não ia para o `ChatContact` salvo). Como a chave pública bruta do outro lado
  nunca é persistida (só as chaves de chain já derivadas por HKDF), este valor é a única prova
  durável do que foi realmente verificado — não é recalculável depois, é um registro histórico.
- **"Remover conexão"** — apaga contato + sessão + histórico local deste aparelho (com diálogo de
  confirmação); irreversível, exige novo pareamento para voltar a conversar.

Acesso: tocar no nome do contato no cabeçalho da conversa. Navegação adicionada como 4º destino
local em `AtlasConnectScreen` (sem NavHost, mesmo padrão dos outros três). Teste novo cobrindo a
simetria do fingerprint persistido nos dois lados (`ChatPairingRepositoryTest`). **84 testes no
total agora** em `core/chat-*`; `:feature:connect:compileDebugKotlin` e `:app:assembleRelease`
limpos.

## 13. Tela "Meu perfil" (2026-10-17, "pode continuar com a criação")

Plano §3, também um subconjunto honesto — sem avatar (pipeline do §4 não existe), sem ID Atlas
com cópia (seria o id do Supabase, de outra camada, `core/memory`), sem telefone (modelo de
compartilhamento próprio, fora de escopo). O que é real:

- `ChatProfileStore` (novo, `core/chat-storage`) — nome de exibição (até 60 caracteres, plano §2)
  e recado (até 140) persistidos localmente, arquivo Keystore-criptografado próprio
  (`atlas_chat_profile_secure`) — domínio de reset separado dos outros três (perder o perfil só
  significa digitar de novo, não é catastrófico como perder a identidade nem inútil sozinho como
  perder uma sessão).
- **Explicitamente só local por enquanto**: salvar aqui NÃO distribui a mudança para conexões já
  pareadas — isso exigiria um novo tipo de payload de controle (plano §5.1) que `ChatMessagePayload`
  ainda não tem (só `Text`/`DeliveryReceipt`). A tela avisa isso na própria UI, em vez de prometer
  uma sincronização que não acontece.
- **Conectado a algo real que já existia**: `ConnectPairingViewModel` agora pré-preenche o nome
  do convite com o nome salvo no perfil, em vez de pedir de novo a cada pareamento — a primeira
  vez que esse dado é efetivamente reaproveitado em vez de digitado do zero toda vez.
- Acesso: ícone de perfil no canto superior direito de "Conexões".

**4 novos testes** (`ChatProfileStoreTest`), **88 testes no total agora** em `core/chat-*`.
`:feature:connect:compileDebugKotlin` limpo.

## 14. Distribuição de perfil (2026-10-17, "pode continuar com a produção") — fecha o plano §5

Antes, salvar o perfil (§13) era só local — nenhuma conexão já pareada ficava sabendo. Fechado
reaproveitando exatamente a infraestrutura do recibo E2EE (§11):

- `ChatMessagePayload` ganhou um terceiro caso, `ProfileUpdate(displayName, statusMessage)` —
  mesmo canal cifrado de texto/recibo, framing com prefixo de 2 bytes pro tamanho do nome (os dois
  campos são de tamanho variável, ao contrário de `Text`, que é só um campo até o fim).
  Deliberadamente sem referência de avatar: o pipeline que produziria uma (plano §4) não existe.
- `ChatMessagingRepository.fetchAndDecryptNew()` — ao decodificar um `ProfileUpdate`, chama
  `ChatContactStore.applyReceivedProfile()` (novo) em vez de devolver como mensagem visível (plano
  §5.1: "Não são mensagens visíveis na linha do tempo") — e não dispara recibo E2EE (um recibo
  prova que uma mensagem de TEXTO foi vista; uma atualização de controle não precisa disso).
- `ChatContact` ganhou `receivedDisplayName`/`receivedStatusMessage` (nuláveis) — **nunca**
  sobrescrevem `displayName` (o rótulo que ESTE aparelho escolheu, documentado desde a criação de
  `ChatContactStore` como algo que nada autodeclarado pela outra pessoa deve tocar). Plano
  §16 já separava os dois conceitos ("Identidade verificada" vs. "Perfil recebido") — agora a tela
  de detalhes da conexão (§12) mostra os dois, lado a lado, textualmente deixando claro que um não
  prova nem desfaz o outro.
- `ProfileViewModel.save()` agora, depois de persistir localmente, percorre
  `ChatContactStore.listAll()` e envia o `ProfileUpdate` pra cada conexão (best-effort — falha de
  rede cai no mesmo mecanismo de `pendingOutbox`/retry que qualquer outro envelope já usa, nada
  novo precisou ser construído pra isso).

**13 novos testes** (3 round-trip de payload, 3 em `ChatMessagingRepositoryTest` — aplica no
contato, nunca gera recibo, no-op sem contato local —, 4 em `ChatContactStoreTest`), **101 testes
no total agora** em `core/chat-*`. `:feature:connect:compileDebugKotlin` e `:app:assembleRelease`
limpos.

## 15. Excluir mensagem do histórico local (2026-10-17, "pode continuar") — plano §12

Ação de pressionar e segurar uma mensagem, com confirmação ("Esta mensagem será removida apenas
deste aparelho.", texto exato do plano) e só a ação que o plano autoriza no MVP — nada de copiar,
compartilhar, encaminhar, editar ou apagar para todos.

- `ChatMessageHistoryStore.deleteOne(conversationId, direction, sequence)` (novo) — remove só o
  registro local. **Sem tombstone**: o plano pede um por causa de redelivery de rede, mas neste
  código uma redelivery genuína já é descartada antes de chegar à UI — o servidor para de
  devolver um envelope depois do ACK, e o próprio ratchet rejeita um replay como `Duplicate`. Não
  existe caminho, neste código, por onde apagar o registro local faria a mensagem reaparecer
  sozinha — um tombstone estaria guardando contra algo que não pode acontecer aqui.
- `MessageBubble` ganhou um parâmetro `onLongPress` — permanece "conteúdo inerte" (doc original
  do componente), só repassa o gesto pra cima; quem decide o que o toque longo significa é
  `ConversationScreen`.
- `ConversationViewModel.deleteMessage(messageId)` reaproveita exatamente a mesma chave composta
  (`"${direction}-${sequence}"`) que já monta cada `MessageBubbleModel.messageId`, sem precisar de
  um segundo esquema de identificação.

**3 novos testes** (`ChatMessageHistoryStoreTest`), **103 testes no total agora** em
`core/chat-*`. `:feature:connect:compileDebugKotlin` e `:app:assembleRelease` limpos.

## 16. Responder a uma mensagem (2026-10-17, "pode fazer") — plano §11

A parte mais delicada estava identificada desde a sessão anterior: a numeração de sequência é por
CHAIN, não por conversa, e "direção" é relativa a quem olha — a mesma mensagem é "SENT" pra quem
mandou e "RECEIVED" pra quem recebeu, com o MESMO número de sequência (as duas chains de uma
conversa são o mesmo espaço de sequência físico visto de lados opostos — ver doc de
`ChatSession.sendingChain`/`receivingChain`). Solução: a referência de resposta viaja na
addressing do REMETENTE (ex.: `"SENT-3"`) e só precisa ser invertida (SENT↔RECEIVED) no único
ponto onde isso importa — `ChatMessagingRepository.fetchAndDecryptNew()`, que já é a fronteira
entre "addressing de quem mandou" e "addressing de quem recebeu". Nenhum outro código (UI,
storage) precisa saber que essa inversão existe.

- `ChatMessagePayload.Text` ganhou `replyToMessageId: String?` — mesmo framing de prefixo de 2
  bytes já usado em `ProfileUpdate`.
- `ChatMessageRecord` (storage) e `DecryptedIncoming` (remote) ganharam o mesmo campo, já
  resolvido pra addressing local no momento em que chegam à UI.
- `MessageBubble` ganhou um bloco de citação (`ReplyQuote`) acima do texto — "Mensagem
  indisponível" quando o original foi excluído localmente (plano §12) ou nunca chegou; resolvido
  automaticamente por um lookup no histórico já carregado, sem código extra pra esse caso.
- Pressionar e segurar uma mensagem agora abre um menu de verdade (`DropdownMenu`) com
  "Responder" e "Excluir do meu histórico" — antes ia direto pro diálogo de exclusão.
- Banner "Respondendo a X" acima do campo de composição, com botão de cancelar que mantém o texto
  digitado (exigência exata do plano).

**7 novos testes** (3 `ChatMessagePayloadTest` — round-trip com resposta, sem resposta, id longo
demais —, 4 `ChatMessagingRepositoryTest` — incluindo os dois testes de inversão ponta a ponta nos
dois sentidos), **107 testes no total agora** em `core/chat-*` (confirmado por contagem direta dos
relatórios de teste, não estimado). `:feature:connect:compileDebugKotlin` e `:app:assembleRelease`
limpos.

## 17. Confirmado em teste ao vivo, dois aparelhos (2026-10-17)

Primeiro teste real de ponta a ponta — ambos os aparelhos reinstalados com a build mais recente,
pareados de novo (o `pm clear` anterior tinha apagado tudo). Confirmado funcionando:
pareamento QR, troca de mensagens, **recibo E2EE ("Entregue" apareceu nas duas mensagens
enviadas)**, detalhes da conexão (fingerprint completo renderizado), menu de pressionar e segurar
(abre com as duas opções), e responder a uma mensagem (banner + citação no balão, testado
enviando de verdade). Fix #1 (voltar) também reconfirmado. Only fix #2 (câmera) e teste dos dois
aparelhos SIMULTANEAMENTE online seguem sem confirmação.

## 18. Três itens menores do plano §13/§14 (2026-10-17, "pode fazer todas as partes menores")

Avatar real e NFC seguem fora de escopo (dependem de pipeline de imagem e hardware,
respectivamente); notificações/silenciamento também (não há nenhuma infra de push/worker em
background pra uma preferência de silêncio ter o que silenciar — construir o toggle sem isso
seria UI morta). Os três itens abaixo são genuinamente pequenos e autocontidos:

- **Bloquear contato** (plano §13/§16) — `ChatContact.isBlocked` (novo). Aplicado nos dois
  sentidos em `ChatMessagingRepository`: `send()`/`sendText()`/`sendProfileUpdate()` recusam
  qualquer envio pra um contato bloqueado (nem chega a cifrar localmente); `fetchAndDecryptNew()`
  pula a conversa inteira — não decifra, não confirma recebimento, não aplica atualização de
  perfil. Os envelopes ficam parados no servidor até o desbloqueio, não são descartados. Toggle
  reversível na tela de detalhes da conexão, com confirmação só ao bloquear (desbloquear não
  pergunta, mesmo padrão de "Cancelar" nunca confirmar usado em todo o módulo). A tela de
  conversa mostra um aviso e desabilita o campo quando a conexão está bloqueada.
- **Excluir histórico da conversa** (plano §13) — novo menu "⋮" no cabeçalho da conversa, distinto
  de "Remover conexão" (que também apaga o pareamento): aqui só o histórico some, a sessão
  criptográfica e o contato continuam intactos, a conversa segue funcionando normalmente depois.
- **Buscar nesta conversa** (plano §13/§14) — campo de busca que filtra as mensagens já carregadas
  pelo texto, sem índice novo nem consulta separada. Simplificação deliberada do plano: ele
  descreve uma lista de resultados própria com "ação de ir à mensagem"; aqui a própria lista
  filtrada já cumpre o mesmo papel, com menos peça nova.

**12 novos testes** (4 `ChatContactStoreTest` — isBlocked default, setBlocked round-trip, no-op
sem contato, compatibilidade com dado antigo —, 3 `ChatMessagingRepositoryTest` — envio recusado,
recepção ignorada até desbloquear, atualização de perfil nem enviada nem aplicada). **114 testes
no total agora** (contagem direta). `:feature:connect:compileDebugKotlin` e `:app:assembleRelease`
limpos.

## 20. Avatar real, notificações/silenciamento, decisão de navegação (2026-10-17, "pode fazer tudo isso")

Dos quatro itens pedidos, três foram construídos de verdade e um foi conscientemente recusado —
ver a nota sobre NFC no final desta seção antes de mais nada.

### 20.1 Decisão de navegação (revisada com o usuário)

Primeira avaliação (minha, unilateral): manter o estado local, já que nenhum motivo "clássico"
pra `NavHost` (deep link, múltiplas pilhas) se aplicava. O usuário pediu pra decidir junto — ao
colocar os dois lados lado a lado (ver abaixo), a decisão virou **migrar para Navigation3**.

- **A favor de manter:** zero risco de regressão nas 5 telas já testadas (inclusive ao vivo).
- **A favor de migrar:** a pilha de navegação não sobrevive à morte do processo hoje — trocar de
  app ou o sistema liberar memória enquanto numa conversa faz reabrir voltar pra "Conexões" em vez
  de retomar de onde parou. `rememberNavBackStack` resolve isso de graça. Também: o projeto já
  tinha `Navigation3` catalogado (`libs.versions.toml`) sem nenhum uso real em lugar nenhum —
  ficar pra sempre sem exercitar essa dependência também tem custo.

**Migração feita** — `AtlasConnectScreen` agora usa `NavDisplay`/`rememberNavBackStack`/
`entryProvider` de verdade (`androidx.navigation3.runtime`/`.ui`, primeira vez usado no projeto
inteiro). Cada `ConnectDestination` é `@Serializable` (precisou do plugin
`kotlin.plugin.serialization`, também nunca antes aplicado em `feature:connect`), condição pro
back stack sobreviver à morte do processo. `rememberViewModelStoreNavEntryDecorator` dá a cada
entrada da pilha seu próprio `ViewModelStore` — duas conversas diferentes empilhadas nunca mais
compartilham a mesma instância de `ConversationViewModel` (antes, tudo reutilizava o
`ViewModelStoreOwner` da Activity).

**Efeito colateral limpo**: `NavDisplay` já trata o botão/gesto de voltar do sistema sozinho, então
os `BackHandler` que `ConversationScreen`/`ContactDetailScreen`/`ProfileScreen` registravam por
conta própria (o fix original do bug de voltar, mais cedo nesta sessão) viraram redundantes e
foram removidos — a seta de voltar na tela continua chamando `onBack` normalmente.
`AddConnectionScreen` é a ÚNICA exceção: seu `BackHandler` faz algo a mais (volta um passo na
máquina de estado do pareamento antes de sair da tela), então continua exatamente como estava.

Versões confirmadas via `dl.google.com/android/maven2` antes de usar às cegas: `navigation3-*`
1.1.3 (já estava no catálogo) e `androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0`
(adicionado agora, mesma versão já fixada pro resto do `lifecycle` no projeto).

**Confirmado ao vivo (2026-10-17, mesmo dia, Moto G60, release build com R8):**
- Voltar (botão/gesto do sistema) funciona corretamente em todos os níveis: Conexões → Conversa
  → Detalhes da conexão → Conversa → Conexões, um passo de cada vez.
- **O teste que motivou a migração, de verdade**: navegado 2 níveis de profundidade (Conversa →
  Detalhes da conexão), processo morto de verdade (`adb shell am kill`, confirmado via `ps` que
  não sobrou processo nenhum — diferente de `force-stop`, que limpa a tarefa dos recentes e NÃO é
  o cenário real que a migração resolve), reaberto (`am start` retornou "its current task has been
  brought to the front", confirmando que restaurou a tarefa existente, não criou uma nova) — **o
  app voltou exatamente pra "Detalhes da conexão"**, não resetou pra "Conexões". Voltar a partir
  desse estado restaurado também funcionou certo (foi pra "Conversa", não quebrou).
- `AddConnectionScreen`'s `BackHandler` próprio (a lógica de voltar um passo na máquina de estado
  do pareamento) continua funcionando normalmente dentro do `NavDisplay` — testado avançando de
  "Adicionar contato" pra "Seu nome" e voltando, ficou em "Adicionar contato", não saiu da tela.
- Nenhum crash de R8/kotlinx.serialization — as regras de ProGuard adicionadas proativamente
  (nunca precisaram ser testadas isoladamente, já que o app funcionou de primeira).

### 20.2 Avatar real — plano §4

Pipeline completo, mas deliberadamente mais simples que o plano em um ponto: recorte é automático
(centralizado), não um recorte interativo arrastável — "Oferece recorte quadrado com prévia
circular" do plano §4.2 passo 3 não foi construído, uma fatia honesta em vez de uma UI de recorte
pela metade.

- **`AvatarImagePipeline`** (feature:connect) — valida dimensões ANTES da decodificação completa
  (até 10 MB / 20 megapixels), faz downsample durante a decodificação (nunca segura um bitmap de
  20 megapixels na memória só pra encolher em seguida), recorta ao centro, redimensiona pra
  512×512, comprime como JPEG reduzindo a qualidade até caber em 256 KiB. Remoção de EXIF não
  precisou de código: decodificar e recomprimir já não carrega metadados adiante.
- **`ChatAvatarStore`** (chat-storage) — virou uma interface (`EncryptedFileChatAvatarStore` é a
  implementação real, `FakeChatAvatarStore` o fake de teste), mesmo padrão que
  `ChatMessageRemoteClient`/`ChatPairingRemoteClient` já usam — `EncryptedFile` não tem equivalente
  JVM-fake do jeito que `FakeSharedPreferences` substitui `SharedPreferences`. Um arquivo pro
  próprio avatar, um por contato que já enviou um, todos cifrados com o mesmo `MasterKey` que o
  resto do `chat:storage` já usa.
- **`ChatMessagePayload.AvatarUpdate`** (chat-remote) — reaproveita o MESMO canal E2EE do
  `ProfileUpdate`, não um endpoint de upload separado: `atlas_chat_message_envelopes.ciphertext_b64`
  é `text` sem limite no Postgres, não precisou mudar nada no schema. Bytes nulos = avatar
  removido (plano §4.3), nunca "sem mudança".
- **UI**: `ProfileScreen` ganhou seletor de foto real (`ActivityResultContracts.PickVisualMedia`)
  + "Remover foto"; `ContactAvatar` (core/ui) ganhou um parâmetro `photoBytes` (decodifica direto
  pra `ImageBitmap`, sem passar pelo Coil — os bytes já estão em memória, não precisam de
  carregamento assíncrono); avatar aparece em "Conexões" (lista), no cabeçalho da conversa, e nos
  detalhes da conexão.

**9 novos testes** (3 `ChatMessagePayloadTest`, 4 `ChatMessagingRepositoryTest` — envio/recepção,
remoção, nunca gera recibo, bloqueio impede os dois sentidos).

### 20.3 Notificações e silenciamento — plano §15

`ChatNotificationWorker` (novo pacote `feature:connect/notifications`), mesmo padrão exato de
`AutomationWorker`/`UpdateWorker` já estabelecidos no projeto (`@HiltWorker`, `CoroutineWorker`,
`runCatching` + `Result.retry()`). Roda a cada 15 minutos (piso do WorkManager pra trabalho
periódico), chama o mesmo `fetchAndDecryptNew()` que a tela de conversa já usa, e posta **no
máximo uma** notificação por execução — nunca uma por mensagem, e sempre o mesmo texto genérico
("Atlas" / "Você recebeu uma nova mensagem", exigência exata do plano, sem nome, avatar, trecho
ou ação de resposta). Um id fixo de notificação faz a segunda substituir a primeira em vez de
empilhar.

- `ChatContact.mutedUntilEpochSeconds` (novo, mesmo padrão de `isBlocked`) — null = não
  silenciado; `Long.MAX_VALUE` modela "até reativar" sem precisar de um campo tri-state separado.
  **Importante**: silenciar só afeta se o worker posta notificação — não tem nenhum efeito em
  `ChatMessagingRepository` (mensagens continuam chegando, recibos continuam sendo trocados,
  exatamente como o plano exige: "não impede sincronização, não marca mensagens como lidas").
- UI: menu "⋮" da conversa ganhou "Silenciar notificações" (1 hora / 8 horas / até reativar / não
  silenciar) e "Buscar nesta conversa" voltou a aparecer ali (sem mudança, só contexto).
- Agendado incondicionalmente em `LauncherApplication.onCreate()`, mesmo padrão de
  `AutomationScheduler.schedule(this)` logo acima.

**6 novos testes** (`ChatContactStoreTest` — default, round-trip, expiração, "até reativar",
no-op sem contato).

### 20.4 NFC real — recusado, não esquecido

**Não implementado.** O "Android Beam" (troca NDEF direta entre dois aparelhos) foi **removido do
Android a partir da versão 10** — não existe mais. A única forma de pareamento NFC telefone-a-
telefone hoje em dia exige Host Card Emulation (HCE): um aparelho simula um "cartão" com um
`HostApduService` e um protocolo próprio de APDU, o outro lê em modo leitor — papéis assimétricos
que não combinam com o fluxo bilateral simétrico que QR já usa (qualquer um dos dois lados escolhe
"apresentar" ou "ler"). Construir isso é uma superfície de plataforma bem mais frágil que QR, e —
mais importante — **impossível de verificar sem dois aparelhos NFC se tocando ao vivo**. Decisão:
não escrever esse código às cegas sem nenhum caminho de verificação; fica pronto pra ser atacado
numa sessão com teste ao vivo nos dois aparelhos, não antes.

**15 novos testes no total** (9 avatar + 6 silenciamento), **125 testes no total agora** em
`core/chat-*` (contagem direta). `:app:compileDebugKotlin` e `:app:assembleRelease` limpos.

## 21. Próximo passo

Ainda falta, nessa ordem de bloqueio real:
1. **Confirmar o fix #2 (crash de câmera) ao vivo.**
2. **Testar tudo construído nesta sessão ao vivo** — bloquear, excluir histórico, buscar, avatar
   real (enviar/receber foto), silenciamento, notificação de fato aparecendo em background.
3. **Teste com os dois aparelhos online ao mesmo tempo** — tudo testado até agora foi com um
   aparelho sozinho; falta ver "Entregue" atualizar em tempo real, distribuição de perfil/avatar
   chegando do outro lado, e a notificação disparando enquanto o app está fechado.
4. NFC real — **código escrito (ver §22)**, falta só o teste ao vivo nos dois aparelhos.

*(Navigation3 — item 2 da lista anterior — já confirmado ao vivo, ver §20.1 acima.)*

## 22. NFC real (HCE) + cards de perfil com bio/interesses (2026-10-18)

Atacado na sessão com os dois aparelhos (Moto G60 + Edge 70) finalmente disponíveis — fecha §20.4
e o item 4 da lista acima, no que depende de código (o teste ao vivo com os dois aparelhos
encostados ainda está pendente, ver abaixo).

**Cards de perfil completos** — `ChatProfile`/`ChatProfile Update` ganharam `bio` e `interesses`
(lista, separada por vírgula na UI), ao lado de nome/recado que já existiam. Distribuídos pelo
mesmo canal `ProfileUpdate` cifrado (formato binário estendido com prefixo de tamanho em cada
campo, `interesses` juntados por `U+001F` já que um interesse do usuário pode conter vírgula).
Novo composable `ProfileCard` (foto + nome + recado + bio + chips de interesse, reusa
`ContactAvatar`) aparece em "Meu perfil" (prévia ao vivo) e nas telas de apresentar/ler contato
(sempre como prévia LOCAL — "é isso que a outra pessoa vai ver depois de conectar", nunca algo que
já trafegou por QR/NFC, que continuam carregando só metadados de pareamento). Pareamento recém-
confirmado agora dispara automaticamente um `ProfileUpdate`+`AvatarUpdate` pro novo contato, em vez
de esperar a próxima edição manual do perfil. "Detalhes da conexão" não ganhou exibição do card
recebido ainda — decisão explícita, os campos já chegam e ficam guardados pra uma tela futura.

**NFC real via HCE** — `NfcCardTransfer` (protocolo puro, testável em JVM: AID proprietário
`F041544C4153`, blocos de 200 bytes, comandos customizados `GET_LENGTH`/`GET_CHUNK` sobre CLA
`0x80`) + `NfcCardService` (lado "cartão", `HostApduService`) + `NfcCardReader` (lado leitor,
`NfcAdapter.ReaderCallback`/`IsoDep`). Nenhum `PairingMethod` novo — NFC roda **ao mesmo tempo**
que QR nas mesmas fases (`PRESENTING`/`SCANNING`): quem registra o serviço preferido
(`CardEmulation.setPreferredService`) ou o modo leitor (`NfcAdapter.enableReaderMode`) é um
`LifecycleEventObserver` ligado ao ciclo de vida real da Activity (não só a composição), ativado
só quando `PackageManager.FEATURE_NFC_HOST_CARD_EMULATION` existe — um aparelho sem NFC/HCE nem
vê menção a isso na UI. `TagLostException` no meio da leitura mostra "toque de novamente" em vez
de cair no estado de erro do pareamento. 13 testes novos em `NfcCardTransferTest` (primeiro teste
de verdade em `feature:connect`).

**Verificado:** `:core:chat-storage:testDebugUnitTest`, `:core:chat-remote:testDebugUnitTest`,
`:feature:connect:testDebugUnitTest`, `:app:assembleDebug` e `:app:assembleRelease` (R8) — todos
limpos. **Não verificado ainda** (impossível sem hardware): os 5 passos de teste ao vivo com os
dois aparelhos (presentear/ler nos dois sentidos, fluxo completo até `ESTABLISHED`, caminho de
erro de toque perdido) — ver o plano desta sessão pro checklist exato.
