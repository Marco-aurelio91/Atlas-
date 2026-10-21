# Atlas Chat — desenho criptográfico (sem dependência de terceiros)

**Status:** criptografia (`core/chat-crypto`), armazenamento local (`core/chat-storage`), o cartão
de pareamento NFC/QR (`core/chat-pairing`) e a persistência real da identidade do dispositivo
implementados e testados (2026-10-16) — identidade X25519 (agora sobrevive a um restart de
verdade, não só em memória), ratchet simétrico HMAC, envelope AES-256-GCM, persistência atômica de
sessão+outbox, convite/aceite/fingerprint de pareamento, e `chat:storage` já ligado a
`AtlasSecurePreferences` (Keystore) via Hilt — não é mais só teste com `SharedPreferences` fake. 48
testes passando ao todo (15 + 13 + 21 + 5), cobrindo T-04/T-05/T-07/T-08/T-09 (três
variantes)/T-10/T-11/T-23 da matriz do plano completo §21.1, incluindo dois testes de ponta a
ponta (um só local — `chat-pairing` — e um pelo cliente/orquestração real de `chat-remote` contra
um backend falso). O schema Supabase do pareamento (device registry, claim/confirm de contato, 4
RPCs — §9.1) já está aplicado no projeto real, e um módulo (`core/chat-remote`, §9.2) já chama
essas RPCs de verdade. Ainda sem NFC/QR de hardware de verdade (precisa de aparelho conectado),
sem transporte de mensagens (`chat:sync`), ou UI.
**Depende de:** `ATLAS_CHAT_Plano_Completo_Criacao_e_Implantacao.md` e
`ATLAS_CHAT_Plano_Perfil_Avatar_Interface_e_Funcionalidades.md` (planos de produto do responsável,
não auditados, servem de referência de requisitos — não de arquitetura de criptografia).

## 1. Por que este documento existe

Os planos de produto cogitam `libsignal` como biblioteca candidata para X3DH + Double Ratchet. A
licença dela é **AGPL-3.0** — a mesma categoria de problema já registrada para o código do
Island portado do SmartIsland (GPL-3.0, não resolvido, ver `atlas-island-built-from-scratch`
na memória do projeto). Embutir AGPL num app fechado distribuído pela Play Store pode forçar o
app inteiro a virar AGPL, ou exigir isolamento de processo que os planos não resolvem.

Decisão: **não usar libsignal nem qualquer outra biblioteca de protocolo de terceiros.**
Implementar os primitivos a partir de especificações públicas (X3DH, Double Ratchet — ver seção 30
do plano completo para os links), usando apenas o que o próprio Android já expõe nativamente. Isso
elimina o risco de licença por completo: algoritmos publicados em specs abertas não carregam a
licença de uma implementação específica.

## 2. Primitivos e por que cada um foi escolhido

| Primitivo | Uso | Fonte |
| --- | --- | --- |
| X25519 (Curve25519 ECDH) | Acordo de chave no pareamento | `KeyAgreement.getInstance("XDH")`, nativo desde API 31 via Conscrypt (componente do próprio AOSP, Apache-2.0) |
| HKDF-SHA256 | Derivação de chaves (ratchet, wrap de identidade) | `javax.crypto.Mac` com `HmacSHA256` — já implementado à mão em `AtlasRecoveryEnvelopeCrypto.hkdf()`, reaproveitar o mesmo padrão |
| AES-256-GCM | Cifra de conteúdo e de chaves locais | `javax.crypto.Cipher("AES/GCM/NoPadding")` — já em uso em `AtlasSecurity.kt` |
| Android Keystore | Proteção da identidade privada em repouso | Mesmo padrão de `AtlasAccountKeyManager.getOrCreateDeviceWrappingKey()` |

Nenhuma dependência Gradle nova. `minSdk` do projeto é 30; X25519 nativo via `KeyAgreement`
("XDH") exige API 31+ — **verificar se isso é aceitável** (pode exigir levantar o `minSdk` do
módulo de chat especificamente, ou usar uma implementação de Curve25519 em Kotlin puro como
fallback para API 30). Marcar como decisão aberta antes de codificar.

## 3. Identidade — separada da chave da conta

A chave de identidade do chat (`ChatIdentityKeyPair`, X25519) é gerada e protegida
**independentemente** da ARK (`AtlasAccountKeyManager`). São domínios de confiança diferentes:
comprometer uma não deve comprometer a outra, e o ciclo de vida é diferente (a identidade do chat
muda a cada troca de aparelho; a ARK sobrevive trocas via recuperação).

Armazenamento local: mesmo padrão de `AtlasSecurePreferences.open()` — chave privada envelopada
pela chave de dispositivo do Keystore, nunca exportada, nunca em texto puro em disco.
**Implementado:** `ChatIdentityKeyPair.privateKeyBytes()`/`.restore()` (codificação PKCS8) +
`ChatIdentityStore` (em `core/chat-storage`), fornecido via Hilt em `ChatStorageModule` sobre
`AtlasSecurePreferences.open(context, "atlas_chat_identity_secure")` — arquivo de preferências
separado do de sessões (`atlas_chat_sessions_secure"`), de propósito: perder a identidade é
catastrófico (todo pareamento existente precisa ser refeito), perder uma sessão só força
re-pareamento daquela conversa específica — não há motivo para acoplar os dois.

## 4. Pareamento — acordo de chave

1. Cada aparelho já tem seu par de chaves X25519 de identidade, gerado no registro do dispositivo
   (RF-02 do plano completo).
2. O convite (NFC/QR) carrega a chave pública X25519 do criador, não um segredo de longo prazo.
3. No momento da confirmação bilateral (ambos os lados confirmam presencialmente), cada aparelho
   calcula o segredo compartilhado via X25519: `sharedSecret = X25519(minhaChavePrivada,
   chavePublicaDoOutro)`.
4. O segredo compartilhado passa por HKDF-SHA256 com `info` fixo por contexto (userId de ambos +
   `conversation_id`) para derivar a **chave-raiz da sessão** (`sessionRootKey`, 32 bytes).
5. `sessionRootKey` nunca é usada diretamente para cifrar mensagens — só para iniciar o ratchet
   (seção 5).

Isso cobre a troca de chave; a *verificação* de identidade (o código de segurança que os dois
usuários comparam presencialmente) é o fingerprint da chave pública X25519 de cada um — ex: hash
SHA-256 da chave pública, formatado em grupos legíveis. Não precisa de biblioteca extra.

## 5. Ratchet — KDF-chain simplificado (não é o Double Ratchet completo)

Em vez do Double Ratchet (DH-ratchet entrelaçado com ratchet simétrico), usar **só o ratchet
simétrico**: cada lado mantém uma "chain key" que avança a cada mensagem via HKDF, sem repetir o
passo de Diffie-Hellman por mensagem.

```
chainKey_0 = sessionRootKey
# a cada mensagem enviada:
messageKey_n, chainKey_(n+1) = HKDF-SHA256(chainKey_n, info="atlas-chat-ratchet-v1")
# messageKey_n cifra essa mensagem (AES-256-GCM); chainKey_n é apagada da memória
```

Duas chains independentes por conversa (uma pra cada direção de envio), como o Signal faz, pra
evitar que mensagens cruzadas avancem a chain errada.

**O que isso dá:** sigilo futuro de verdade — comprometer `chainKey_n` não expõe mensagens
anteriores (a chain não pode ser revertida), já que `messageKey` e a próxima `chainKey` vêm de uma
função de via única.

**O que isso NÃO dá, ao contrário do Double Ratchet completo:** recuperação automática pós-
comprometimento. Se um atacante rouba `chainKey_n` em algum momento, ele consegue derivar todas as
mensagens futuras daquele lado até a próxima vez que os dois aparelhos façam um novo acordo DH
(que, neste desenho simplificado, só acontece de novo em um *re-pareamento* — não automaticamente
a cada N mensagens). Essa é uma limitação real e deliberada, não escondida: documentar no app e na
seção de modelo de ameaças do plano completo (seção 4) como risco residual aceito.

**Mensagens fora de ordem / puladas:** manter uma janela limitada de `messageKey`s derivadas mas
ainda não usadas (ex: até 1000 por direção, como o Signal faz), indexadas por número de sequência,
pra permitir decifrar uma mensagem que chegou fora de ordem sem precisar re-derivar a chain inteira.
Descartar chaves mais antigas que a janela.

## 6. Formato do envelope

Reaproveitar a mesma estrutura de `EncryptedEnvelope` já usada em `core/security` (versão,
algoritmo, nonce, ciphertext — ver `AtlasSecurity.kt`), adaptada:

```json
{
  "envelope_version": 1,
  "algorithm": "AES-256-GCM",
  "sender_device_id": "uuid",
  "sequence": 42,
  "nonce_b64": "...",
  "ciphertext_b64": "..."
}
```

AAD (dado autenticado, não cifrado) amarra `conversation_id + sender_device_id + sequence +
relationship_epoch` — mesmo princípio que já existe em `AtlasSecurity.associatedData()`: um
envelope roubado ou reaproveitado em outra conversa/sequência falha a verificação do GCM.

**Implementado no protótipo** (`ChatEnvelope.kt`): AAD amarra `conversation_id + sender_device_id +
sequence` — `relationship_epoch` ainda falta porque esse conceito só existe quando a camada de
pareamento/schema for construída (o protótipo não tem época de relacionamento, só duas identidades
trocando mensagens). Fechar essa lacuna quando `chat:pairing`/`chat:storage` existirem — não
esquecer, é exatamente o tipo de amarração que impede um envelope de uma geração revogada de ser
reaproveitado (RF-13 do plano completo).

O payload interno (tipo, texto, resposta) vai dentro do ciphertext, conforme seção 11.2 do plano
completo.

## 6.1 Cartão de pareamento (`core/chat-pairing`)

Implementado conforme §8.2/§8.3 do plano completo, até onde dá sem hardware NFC/QR de verdade nem
backend:

- `PairingInvitation` — o que A transmite (NFC tap ou QR): `invitationId`, token de 256 bits,
  expiração, `atlasUserId`/`deviceId`/`deviceEpoch` de A, a chave pública X25519 de A, nome de
  exibição. `create()`/`parse()`/`serialize()`; `MAX_CARD_BYTES = 4096`; qualquer coisa malformada,
  truncada, de versão não suportada ou expirada é rejeitada com `InvalidPairingCardException`, não
  lança exceção genérica nem trava o parser.
- `PairingAcceptance` — a resposta de B, amarrada ao `invitationId` exato que está aceitando
  (`parse(bytes, expectedInvitationId)` rejeita uma aceitação que responde a um convite diferente).
  O plano prevê isso passando pelo servidor (B resgata, servidor registra, A confirma) — esse
  trecho servidor não existe neste protótipo; esta classe só modela o formato dos bytes que
  precisam chegar de um lado a outro, por qualquer transporte.
- `PairingFingerprint` — o código de segurança presencial da seção 4: SHA-256 completo sobre as
  duas chaves públicas (em ordem canônica, simétrico independente de quem é "local") + o
  `conversation_id`, formatado em grupos de dígitos decimais só para leitura humana — nenhum byte
  do hash é descartado, ao contrário de um código truncado arbitrário (que o plano explicitamente
  pede para não fazer).

Transporte-agnóstico de propósito, igual ao resto: como os bytes do convite/aceite atravessam de
um aparelho a outro (NFC, QR, ou claim via servidor) é trabalho de `chat:remote`, não deste módulo.

## 7. Decisões — o que foi resolvido e o que ainda falta

1. ~~`minSdk` 30 vs exigência de API 31~~ **Resolvido:** usar `org.conscrypt:conscrypt-android`
   (Apache-2.0, Google/BoringSSL — mesma categoria "primitivo, não protocolo" já aceita) instalado
   como provedor JCA, dando `KeyAgreement("XDH")` em todo o `minSdk` 30 do projeto sem depender da
   API 31. Validado de verdade: `X25519 agreement is symmetric between both sides` passou usando
   Conscrypt de verdade (via `conscrypt-openjdk-uber` nos testes JVM) — não é só plano, é código
   rodando. Ver `core/chat-crypto/build.gradle.kts`.
2. ~~Tamanho da janela de mensagens puladas~~ **Resolvido:** 1000, implementado e testado (T-23 —
   um salto de sequência muito além da janela é rejeitado, não trava nem estoura memória).
3. Critério de re-pareamento "forçado" periódico — **ainda em aberto**, decisão de produto, não
   técnica. Não bloqueia o protótipo; só importa quando o pareamento de verdade existir.
4. ~~BouncyCastle vs nativo~~ **Resolvido pela decisão 1** (Conscrypt).
5. **Novo, do protótipo:** `relationship_epoch` ainda não está na AAD do envelope — ver nota na
   seção 6. Fechar isso faz parte do trabalho de `chat:pairing`/`chat:storage`, não do crypto puro.
6. **Novo, resolvido:** formato do cartão de convite/aceite e o fingerprint presencial — ver seção
   6.1. O que continua em aberto depois disso: T-04 (requisição concorrente — duas aceitações pro
   mesmo convite) e T-06 (substituição de identidade detectada por *pinning*, não só pelo
   fingerprint no momento do pareamento) exigem um servidor de verdade para fazer sentido; não dá
   para simular isso de forma que prove algo sem ele.

## 8. Vetores de teste obrigatórios antes de qualquer UI

Mapeados diretamente da seção 21.1 do plano completo (T-07 a T-12).

- **T-07, T-08, T-09 (três variantes), T-23** — `core/chat-crypto/src/test/.../ChatSessionTest.kt`
  (13 testes). Estado só em memória, duas sessões conversando no mesmo processo de teste.
- **T-10, T-11** — `core/chat-storage/src/test/.../ChatConversationStoreTest.kt` (7 testes). De
  verdade, não só em memória: serialização JSON real, `SharedPreferences.Editor.commit()` atômico,
  "crash" simulado descartando o objeto `ChatSession` ao vivo e reconstruindo um novo só a partir
  do que o store persistiu. É o teste `T-10 a session restored after a crash resumes at the right
  sequence...` e `T-11 a receive-side crash before ack...` especificamente.
- **T-05** (QR/NFC adulterado) — `core/chat-pairing/src/test/.../PairingInvitationTest.kt` (9
  testes) e `PairingAcceptanceTest.kt` (7 testes). Cobre JSON invalido, campo obrigatório faltando,
  versão de formato não suportada, convite expirado, payload maior que `MAX_CARD_BYTES` e — único
  do lado da aceitação — uma aceitação bem formada que responde a um `invitationId` diferente do
  esperado. Tudo rejeitado com `InvalidPairingCardException`, nunca uma exceção genérica/crash.
- **T-12** (timeout após commit remoto) — ainda não testável: depende de uma chamada de rede de
  verdade (`chat:sync`), que não existe neste momento. Não inventar um teste que não prova nada.
- **Ponta a ponta** — `PairingEndToEndTest.kt` (2 testes): o fluxo completo do §8.3 do plano —
  convite, aceite, fingerprint calculado independentemente pelos dois lados (e confirmado igual),
  `ChatSession.establish` alimentado pelas chaves públicas *trocadas* (não geradas localmente nos
  dois lados, como nos testes de `chat:crypto` isolado), uma mensagem cifrada de verdade trocada, e
  sobrevivência a um "crash" simulado via `ChatConversationStore` — e o caso de uma aceitação
  respondendo a um convite não relacionado sendo rejeitada.

- **Identidade sobrevivendo a um restart de verdade** —
  `core/chat-crypto/src/test/.../ChatSessionTest.kt` (+2 testes: round-trip PKCS8 reproduz o
  mesmo segredo compartilhado; uma sessão estabelecida com identidade restaurada troca mensagem
  de verdade) e `core/chat-storage/src/test/.../ChatIdentityStoreTest.kt` (5 testes: `load` vazio
  retorna null em vez de lançar, round-trip completo via `ChatSession`, `loadOrCreate` persiste e
  não sobrescreve uma identidade já existente).

48 testes no total, 0 falhas, confirmados rodando (não é plano, é `./gradlew test` passando de
verdade — ver os três módulos). `./gradlew :core:chat-storage:assembleRelease` também compila
limpo, já com o módulo Hilt (`ChatStorageModule`) processado via KSP.

## 9.1 `chat:remote` — schema Supabase do pareamento (aplicado em produção, 2026-10-16)

O schema mínimo pra `core/chat-pairing` funcionar entre duas contas de verdade está em
`supabase/migrations/20261016190000_atlas_chat_pairing.sql` (cópia de referência em
`core/memory/schema/010_chat_pairing.sql`) e **já foi aplicado ao projeto Supabase real**
(`hqfezdzvsmuakheldtcr`) via `supabase db push` — confirmado via `supabase migration list`
(remoto = local) e via consulta direta a `information_schema` listando as 3 tabelas e as 4
funções.

- `atlas_chat_devices` — identidade pública por dispositivo; só um `status='active'` por
  `user_id` (RF-02), garantido por índice único parcial, não só pela função.
- `atlas_chat_contact_invites` — convite NFC/QR; só o hash do token (`pg_catalog.sha256`, nativo
  do Postgres 14+, não pgcrypto — evita ambiguidade de schema da extensão) fica salvo, nunca o
  token em si.
- `atlas_chat_contact_relationships` — vínculo confirmado, com `device_low_id`/`device_high_id`
  **fixados no momento da confirmação** (RF-04) — essa é a base de dados que um futuro
  `send_envelope` (chat:sync) teria que checar pra detectar substituição de identidade (T-06);
  esta migração só cria o pino, não implementa a verificação em si.
- 4 funções RPC, todas `security definer` com `search_path=''` e identidade do chamador sempre
  via `auth.uid()` interno (nunca um parâmetro do cliente): `atlas_chat_register_device`,
  `atlas_chat_create_contact_invite`, `atlas_chat_claim_contact_invite` (o `UPDATE ... WHERE
  status='pending'` atômico que implementa T-04 — duas chamadas concorrentes, só uma altera a
  linha), `atlas_chat_confirm_contact_relationship`.
- RLS em tudo; nenhuma tabela aceita INSERT/UPDATE/DELETE direto do cliente, só via essas RPCs.

**Nota operacional:** `supabase db push` tenta, como passo colateral, cachear uma imagem Docker do
edge-runtime — se o Docker Desktop não estiver rodando, isso aparece como uma sequência de erros
"failed to connect to the docker API", mas é inofensivo e não impede a migração real de ser
aplicada; conferir sempre pelo "Finished supabase db push." no final e por `supabase migration
list`, não pelos erros do meio.

**O que ainda falta nesta frente (não inventar que já está pronto):**
- Nenhum cliente Kotlin chama essas funções ainda — `core/chat-pairing` continua isolado em testes
  JVM, sem módulo `chat:remote` que use `SupabaseRestClient.rpc(...)` pra de fato registrar
  dispositivo, criar/resgatar convite e confirmar.
- T-04 está coberto no nível do SQL (o `UPDATE` atômico), mas sem um teste de concorrência real
  (duas conexões simultâneas) — só a lógica foi revisada, não exercitada sob corrida de verdade.
- T-06 tem onde os dados pinados ficam guardados, mas nenhuma função ainda *lê* esse pino pra
  bloquear um envio com identidade divergente — isso é `send_envelope`, que não existe (é
  `chat:sync`, a camada de transporte, ainda não desenhada).
- Convites abandonados em `'pending'`/`'claimed'` não são limpos automaticamente — não há rotina
  de expiração server-side, só o `expires_at` checado no momento do `claim`.

## 9.2 `chat:remote` — cliente Kotlin e orquestração (concluído, 2026-10-16)

Novo módulo `core/chat-remote`, que é o que uma tela de pareamento chamaria de verdade — nenhum
dos quatro módulos anteriores conversa entre si sozinho, isso é quem os liga:

- `ChatPairingRemoteClient` (interface) + `SupabaseChatPairingRemoteClient` — chama as 4 RPCs e
  faz os 3 SELECTs de leitura (`fetchInviteClaim`, `fetchDeviceIdentityPublicKeyB64`,
  `isRelationshipActive`) via o `SupabaseRestClient` que já existe em `core/memory`. Corrigido de
  passagem: `SupabaseRestClient.rpc()` quebrava em qualquer função `returns void` (as 4 daqui são
  todas assim) porque o PostgREST devolve 204/corpo vazio e o parser tentava `JSONArray("")` —
  agora trata corpo vazio/"null" como array vazio.
- `ChatPairingRepository` — a orquestração de verdade: `ensureDeviceRegistered()`,
  `createInvite()` (lado A), `acceptInvite()` (lado B, já calcula o fingerprint local sem round
  trip extra), `fetchClaim()` (A, leitura única — quem chama decide se e quando repetir),
  `confirmAndEstablish()` (A: confirma no servidor + estabelece e persiste a sessão local),
  `waitForConfirmation()` (B: leitura única; uma vez ativo, estabelece e persiste a sessão de B).
  Device id derivado deterministicamente da própria chave pública da identidade (SHA-256 dela,
  base64url) — nada de um segundo storage que pudesse desincronizar do identity store.
- `ChatConversationStore.persistSession()` (novo, em `chat:storage`) — faltava uma forma de
  persistir uma sessão recém-`establish()`ada, antes de qualquer envelope existir; nem
  `persistAfterEncrypt` nem `persistAfterReceive` cobriam esse momento.
- Testado com `FakeChatPairingRemoteClient` (backend falso em memória, inclusive reproduzindo a
  regra do T-04 — segunda reivindicação concorrente falha) e `FakeChatAuthSession` — 5 testes,
  incluindo o fluxo bilateral completo A↔B de ponta a ponta terminando com uma mensagem cifrada de
  verdade trocada.

54 testes no total agora (15 + 13 + 21 + 5), 0 falhas, confirmados rodando nos quatro módulos.
`core:chat-remote:assembleRelease` e `core:memory:compileDebugKotlin` (depois do fix do `rpc()`)
também compilam limpo.

**O que ainda falta nesta frente:** nenhum teste chamou o Supabase real através deste cliente
(só o fake) — a integração de verdade com a rede só vai ser provada na primeira vez que uma tela
chamar isso de fato. `fetchClaim`/`waitForConfirmation` fazem uma leitura só; quem decide a
cadência de repetição (polling, pull-to-refresh) é a UI, que não existe ainda.

## 9.3 UI de pareamento por QR Code (concluída, 2026-10-17)

Construída em `feature/connect` (módulo "Atlas Connect") — `ConnectPairingViewModel` +
`AddConnectionScreen`, usando CameraX + ML Kit Barcode Scanning (decodificar) e ZXing `core`
(desenhar o QR). NFC segue fora de escopo de propósito. Detalhe completo em
`docs/ATLAS_PEOPLE_UI_STATUS.md` §6.

## 9.4 `chat:sync` — transporte de mensagens (concluído, 2026-10-17)

`supabase/migrations/20261017020000_atlas_chat_sync.sql` (cópia em
`core/memory/schema/011_chat_sync.sql`), **aplicada em produção** — tabela
`atlas_chat_message_envelopes` + 3 RPCs (`atlas_chat_send_envelope`,
`atlas_chat_fetch_pending_envelopes`, `atlas_chat_ack_envelope`). `send_envelope` é onde o T-06
finalmente ganha enforcement de verdade: compara o dispositivo ativo do remetente contra o
`device_low_id`/`device_high_id` pinado em `atlas_chat_contact_relationships` no momento da
confirmação — essa checagem só existia como pino de dados até agora (ver §9.1), sem nada que a
lesse. Idempotente por `(conversation_id, sender_device_id, sequence)`.

`core/chat-remote` ganhou `ChatMessageRemoteClient`/`SupabaseChatMessageRemoteClient` e
`ChatMessagingRepository` (`send`/`resendPending`/`fetchAndDecryptNew`) — cifra/decifra usando a
sessão já estabelecida por `chat:pairing`, persiste local antes de qualquer chamada de rede
(T-10) e persiste o lado recebedor antes de confirmar (T-11), exatamente como os testes de
`chat:storage` já provavam que precisava ser feito, agora com rede de verdade por trás. 9 testes
em `core/chat-remote` (4 novos + 5 de pareamento), 0 falhas — um bug real foi pego nos PRÓPRIOS
testes (dois lados compartilhando indevidamente um `ChatConversationStore`, não um problema do
código de produção) e corrigido antes de reportar sucesso.

**Limitação deliberada, não escondida:** isso só entrega confirmação de transporte ("aceito pelo
servidor"). O recibo E2EE do plano (uma segunda mensagem cifrada provando que o destinatário
decifrou e persistiu) não existe — sem ele, a UI não pode honestamente mostrar "Entregue", só
"Enviada", na tabela de estados do plano de layout §10.

## 9.5 Próximo passo

Criptografia, armazenamento, pareamento (cartão + UI de QR), fiação no app, schema Supabase de
pareamento E de transporte, e os clientes/repositórios Kotlin de ambos — tudo concluído e
compilando (`:app:assembleDebug`/`:app:assembleRelease` completos, grafo do Hilt validado). O que
falta agora é ligar telas reais a esse backend (nenhuma tela chama `ChatMessagingRepository`
ainda), o recibo E2EE, e testar em dois aparelhos físicos — nada disso foi exercitado fora de
testes com fake. Ver `docs/ATLAS_PEOPLE_UI_STATUS.md` §8 para o detalhe por prioridade.
