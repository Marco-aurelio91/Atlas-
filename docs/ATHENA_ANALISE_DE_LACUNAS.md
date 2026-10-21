# ATHENA — análise do projeto existente

Este diagnóstico é histórico, anterior ao primeiro incremento. Para o estado consolidado e a sequência atual de execução, consulte [ATHENA_PLANO_COMPLETO.md](ATHENA_PLANO_COMPLETO.md).

Data: 2026-09-27. Referência: `C:/Users/Atlas/Downloads/ATHENA_ARQUITETURA_FUNDADORA.md`, versão 1.0.

## Escopo e conclusão

Análise estática do código presente neste checkout, incluindo alterações locais e arquivos ainda não versionados. O documento fundador foi tratado como especificação de referência, não como autorização para implementar seus comandos. Nenhuma implementação foi alterada nesta análise.

**O ATLAS possui bases aproveitáveis, mas a ATHENA descrita no documento ainda precisa ser criada como subsistema.** Não foram encontrados módulos ATHENA no `settings.gradle.kts`, nem implementações dos contratos ATHENA nas buscas em fontes Kotlin, arquivos Gradle e documentação do repositório.

Isso não significa começar todo o produto do zero: clima, coroutines/Flow, injeção de dependências, tarefas Android, memória pessoal, automações e superfícies de apresentação já têm código. Porém, sua existência não comprova a existência do pipeline externo, da fronteira de privacidade ou do protocolo propostos.

Não executei build, testes, consultas a provedores ou validação em aparelho. “Existe” significa implementação encontrada e inspecionada; não certifica funcionamento em produção. Serviços remotos, branches externos e outros repositórios não foram auditados. O checkout já continha mudanças locais, especialmente na Island e no modelo de ocorrências; elas foram preservadas.

## O que já existe e como aproveitar

| Área | Evidência no repositório | Situação perante ATHENA |
|---|---|---|
| Android modular | `settings.gradle.kts`: `core:model`, `core:data`, `core:memory`, `feature:chat`, `feature:materialisland` e outros | Infraestrutura existente. Faltam fronteiras Gradle específicas de ATHENA. |
| Clima real | `core/data/src/main/kotlin/com/unyx/atlas/data/smartspace/WeatherRepository.kt` | Consulta Open-Meteo, parse de condições atuais, timeout, cache em memória e coordenadas arredondadas na consulta HTTP. Melhor candidato para o primeiro conector, após extração de responsabilidades. |
| Consumo de clima | `feature/chat/.../ChatViewModel.kt:1581` e `feature/home/.../SmartspaceWidget.kt` | Chat e widget já consomem clima. São os consumidores a preservar durante a migração. |
| Barramento local | `feature/chat/.../agent/AgentEventBus.kt` | `SharedFlow` de resultados de ferramentas. Serve de referência técnica; seu contrato carrega informações pessoais e não deve virar o barramento ATHENA. |
| Classificação ambiental | `feature/chat/.../agent/Agent.kt` | `Agent.ENVIRONMENT` classifica `get_weather`; não implementa um agente externo independente nem framework de conectores. |
| Contexto do aparelho | `feature/chat/.../DeviceContextCapture.kt`, `feature/materialisland/.../context/AtlasContextBridge.kt` | Captura de contexto e eventos de bateria/Bluetooth. Pertencem ao ATLAS. |
| Memória pessoal | `core/memory/.../MemoryRepository.kt`, `MemoryModels.kt`, `RoutineInference.kt` | Memória, contexto, rotinas e cliente Supabase existem. Devem continuar fora das dependências ATHENA. |
| Proatividade | `feature/chat/.../ChatViewModel.kt:848` | Sugestões de tarefas e rotinas, níveis e supressão por feedback. Implementação parcial do lado ATLAS, ligada ao ciclo de vida do ViewModel. Não há entrada de eventos ATHENA. |
| Ferramentas | `feature/chat/.../ChatViewModel.kt:1140` | Dispatcher `executeTool` existente. Não equivale ao `ToolPlanner` independente e à política central descritos. |
| Automações | `feature/chat/.../automation/` e `app/.../LauncherApplication.kt` | Gatilhos de carregador, fones e horário; ações de aplicativo, volume, brilho e notificação. Não há gatilho tipado de evento mundial. |
| Trabalho em segundo plano | `AutomationScheduler.kt`, `AutomationWorker.kt`, configuração de `HiltWorkerFactory` em `LauncherApplication.kt` | Base de agendamento existente. ATHENA ainda precisa de workers, restrições e ciclo de vida próprios. |
| Island e apresentação | `feature/materialisland/.../plugins/PluginManager.kt`, `island/IslandState.kt`, `plugins/atlas/AtlasConversationBridge.kt` | Plugins, estados e integração com conversa existem. Falta receber decisões do ATLAS sobre eventos mundiais. |
| Ocorrências | `core/model/.../Occurrence.kt` e adaptadores da Island | Registro com geração/revisão e seleção estável, presente como trabalho local não versionado. Pode apoiar a apresentação após decisão do ATLAS; não substitui `AthenaEvent`. |
| Persistência e credenciais | `LauncherDatabase.kt`, `AuthSessionStore.kt` | Há Room para layout e armazenamento de sessão pessoal. Não há persistência mundial independente nem cofre de credenciais de conectores. |
| Testes | Testes de weather codes, nomes de lugares, Island e `OccurrenceRegistry` | Bases existentes; não foram encontrados testes específicos de contratos, pipeline ou fronteira ATHENA. |

Os caminhos abreviados com `...` representam o pacote Kotlin/Java do respectivo módulo.

## O que precisa ser criado

| Componente do documento | Lacuna concreta | Prioridade |
|---|---|---|
| ATHENA Core | Configuração, lifecycle, registro de componentes e contratos independentes de UI/memória | Fundação |
| Modelos externos | `AthenaEvent`, `AthenaSource`, `EventProvenance`, entidades, domínio, região, severidade e validade | Fundação |
| Connector Framework | Interface, capabilities, manifesto, lifecycle, resultados tipados e registro de conectores | Fundação |
| Event Gateway | Entrada única com validação, limites de payload, timestamps, política de origem e identificação canônica | Fundação |
| Normalização | Mapeamento de dados do provedor para eventos tipados; tratamento explícito de campos inválidos | Fundação |
| Deduplicação | Identidade de ocorrência externa, janela temporal e retenção limitada; revisão de UI não resolve isso | Fundação |
| Trust primitives | Evidências verificáveis de origem/atualidade e representação de incerteza | Fundação |
| Event Bus ATHENA | Canal exclusivo e contrato explícito de entrega, buffer, perda, replay e consumidores | Fundação |
| Protocolo e bridge | Envelope versionado, query/result, erros, status e conversão para contratos ATLAS | Fundação |
| Segurança de fronteira | DTOs mínimos, isolamento de memória, tratamento de conteúdo externo como dado, allowlist de hosts e testes arquiteturais | Desde a fundação |
| Primeiro conector | Extração do cliente de clima existente, sem acesso direto à localização pessoal | Primeiro fluxo real |
| World State/cache | Estado por região/fonte, validade, `maxAge`, estado stale e descarte de dados vencidos | Após primeiro fluxo |
| Resiliência/health | Erros tipados, retry/backoff, limites de chamadas, isolamento de falhas e status por fonte | Básico no primeiro conector; evoluir depois |
| Observabilidade | IDs de correlação e métricas de entrada, rejeição, duplicidade, latência e falhas, sem dados pessoais desnecessários | Básico desde a fundação |
| Subscriptions | Seletores mínimos, condição, expiração, cancelamento, persistência técnica e retomada | Etapa posterior |
| Change detection | Comparação de estados e emissão de mudanças relevantes | Após World State |
| Correlação mundial | Regras, eventos derivados e vínculo às evidências de origem | Após eventos e estado estáveis |
| Novas fontes | Status, RSS, trânsito e alertas públicos | Uma a uma, após validar o primeiro conector |
| ATHENA Cloud | Backend, recebimento de webhooks, autenticação, replay guard, entrega e sincronização | Opcional; apenas quando houver caso 24/7 |
| SDK completo | Compatibilidade de versões, documentação e suíte comum de contratos | Evolução do framework |

WebSocket/SSE, MQTT e monitoramento de sites são possibilidades do documento, não dependências obrigatórias do primeiro MVP. A infraestrutura Supabase usada para memória pessoal não comprova a existência de ATHENA Cloud.

## Adaptações necessárias no ATLAS

1. **Separar aquisição de localização e consulta externa.** Hoje `WeatherRepository` lê `LocationManager`, geocodifica e consulta o provedor. ATLAS deve obter/minimizar a região; o conector recebe somente os parâmetros efêmeros necessários. Não mover a classe inteira para ATHENA.
2. **Preservar chat e widget.** Introduzir uma fachada compatível para os consumidores atuais e trocar a implementação de consulta por `WorldQuery → bridge → ATHENA`. Evitar duas consultas concorrentes ao mesmo provedor durante a transição.
3. **Criar uma entrada de eventos externos fora do ViewModel.** O recebimento e a política de eventos não podem depender exclusivamente de o chat estar aberto. Os subscribers atuais de `AgentEventBus` e `AtlasContextBridge` vivem no `viewModelScope`.
4. **Extrair ou ampliar a decisão de relevância pessoal.** Reaproveitar regras existentes de sugestões e feedback, mas adicionar avaliação de validade, relevância, permissão de interrupção e destino. Um evento `CRITICAL` no mundo não deve automaticamente interromper o usuário.
5. **Formalizar a política anterior às ações.** Existem verificações locais em ferramentas e automações; falta a política comum prevista para eventos externos. Nenhum texto recebido de fonte externa deve adquirir acesso direto ao dispatcher de ferramentas.
6. **Integrar apresentação somente após a decisão.** A bridge entrega conhecimento ao ATLAS; um adaptador ATLAS pode projetar o resultado na Island/ocorrências. Conectores não devem importar plugins ou componentes Compose.

## Pontos concretos que exigem atenção

- **Cache de clima não garante freshness ao consumidor.** `refresh()` limita novas buscas por 30 minutos, mas o valor anterior continua no `StateFlow`. `get_weather` espera apenas um valor não nulo; pode devolver o valor antigo imediatamente enquanto a atualização acontece ou falha. ATHENA precisa distinguir dado válido, vencido e indisponível, respeitando `maxAge`.
- **Bus de ferramentas tem semântica pessoal.** `AgentEvent` contém input/resultado de ferramentas; seus consumidores registram episódios e hábitos. Reutilizar esse contrato para ATHENA desfaz a separação proposta.
- **SharedFlow não é armazenamento durável.** O bus atual não configura replay. Para ATHENA, decidir explicitamente se consumidores tardios consultam World State ou se eventos precisam de persistência/reentrega. Não assumir que o buffer preserva eventos sem assinantes.
- **Occurrence não é evento mundial.** Possui estado de execução, título e progresso, mas não fonte, confiança, região, validade ou proveniência. É uma projeção de apresentação possível, não o contrato de inteligência externa.
- **PluginPresentationGateway não é o Event Gateway.** Encaminha pedidos de exibição/remoção e mantém histórico diagnóstico limitado. Não autentica ou valida entradas externas.
- **AtlasCore.kt não é um núcleo cognitivo.** O arquivo inspecionado implementa um indicador visual Compose e estados de atividade. Não deve ser contabilizado como ATHENA Core ou orquestrador cognitivo.
- **Credenciais exigem implementação própria.** `AuthSessionStore` usa armazenamento criptografado, mas tem fallback para SharedPreferences sem criptografia. Esse padrão não deve ser copiado como garantia de armazenamento seguro dos conectores.

## Decisões que o documento ainda deixa abertas

O documento é uma fundação, não uma especificação executável completa. Antes da implementação, definir:

- qual formato bruto entra no gateway: a interface exemplificada emite `Flow<AthenaEvent>`, enquanto o pipeline prevê normalização posterior;
- identidade canônica, atualização/cancelamento de um evento, semântica de deduplicação e ordenação;
- entrega do bus, overflow, política para ausência de consumidores e reinício de processo;
- serialização, tipos de payload permitidos, limites e compatibilidade entre versões;
- retenção por domínio, limpeza de subscriptions e caches, inclusive após reinício;
- critérios explícitos de confiança e de independência entre fontes;
- responsabilidade única pela atualização do World State: diagramas mostram ordens diferentes em relação ao bus;
- alcance da promessa Zero Personal Data: seletores de voo/região e identificadores de subscriptions também podem permitir inferências quando correlacionados. Minimização deve incluir retenção e logs, não somente nomes de campos;
- tratamento de fontes privadas: a menção a Gmail não autoriza persistir conteúdo pessoal dentro da ATHENA. Exige delimitação específica e não pertence ao MVP de fontes públicas.

## Sequência proposta de implementação

### 1. Contratos e fronteiras

Criar inicialmente `:athena:core`, `:athena:runtime`, `:athena:connector-weather` e `:core:athena-integration` como proposta de organização, sem reproduzir todas as pastas do documento como módulos Gradle. Core contém modelos e contratos; runtime contém pipeline e estado; conector contém acesso ao provedor; integração pertence ao lado ATLAS. `:app` faz a composição/injeção.

Aceite: core/runtime/conector não dependem de memória pessoal, chat ou Island; contratos versionados e testes de fronteira verificam isso. A integração recebe DTOs mínimos do lado ATLAS e não dá acesso da ATHENA aos repositórios pessoais.

### 2. Pipeline com provedor falso

Implementar gateway, normalizador, deduplicação, confiança básica, bus e bridge. Fazer um evento percorrer o pipeline completo antes de depender de rede real.

Aceite: evento válido chega com origem rastreável; evento inválido é rejeitado; duplicata é filtrada; conteúdo externo com instruções não aciona ferramentas. Definir qual rejeição cabe ao gateway e qual ao consumidor.

### 3. Clima real e migração dos consumidores

Extrair cliente do provedor de `WeatherRepository`, mantendo localização/geocodificação pessoal do lado ATLAS. Implementar consulta com deadline, freshness, erro e status de fonte. Adaptar widget e `get_weather` sem mudar sua finalidade.

Aceite: teste com fake do provedor e verificação em aparelho demonstram consulta, falha, timeout e dado vencido; chat e widget continuam funcionando. O código existente obtém condições atuais: previsão de tempestade requer ampliar os dados consultados antes de prometer esse cenário.

### 4. Relevância e apresentação ATLAS

Introduzir consumidor independente da tela de chat, avaliação de relevância pessoal e política de apresentação. Integrar com a Island por adaptador do ATLAS.

Aceite: evento sem relevância ou vencido não interrompe; evento aprovado pode aparecer na superfície escolhida; o conector não chama a UI nem executa ação pessoal.

### 5. Monitoramento e expansão

Evoluir World State persistente, subscriptions expirantes, change detection e um segundo conector. Só então ampliar correlação e considerar Cloud.

Aceite: reinício recupera somente estado ainda válido; cancelamento interrompe observação; falha em uma fonte não bloqueia as demais; eventos derivados preservam suas evidências.

## Validação necessária na implementação futura

- Unitários de parsing, normalização, identidade, TTL, confiança e transições de estado.
- Contrato comum de conectores e protocolo versionado.
- Integração fake provider → connector → gateway → bus → bridge → decisão ATLAS.
- Falhas: timeout, HTTP inválido, relógio divergente, duplicidade, rate limit e retorno contraditório.
- Segurança: payload excessivo, profundidade de JSON, conteúdo com instruções e destinos de rede fora da política.
- Arquitetura: proibir dependência ATHENA de memória, contatos, histórico de conversa e UI; verificar DTOs e logs.
- Regressão dos consumidores de clima e apresentação existente.

O próximo incremento concreto é **fundação isolada + pipeline testável + extração do clima existente**. Cloud, dezenas de conectores e correlação avançada podem esperar até esse fluxo estar comprovado.
