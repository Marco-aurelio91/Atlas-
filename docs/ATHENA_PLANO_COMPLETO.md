# ATHENA — plano consolidado de implementação e implantação

Atualizado em: **2026-09-30**. Status: implementação local das fases F0–F8 disponível no checkout, com build/testes e aceite registrados em [ATHENA_VALIDACAO.md](ATHENA_VALIDACAO.md). A validação física permanece pendente por decisão do usuário. Não há release 1.0 publicado nem ATHENA Cloud implantada. O detalhamento das fases abaixo preserva os critérios de aceite do plano; implementação não equivale a aprovação de todos os critérios em aparelho.

Contratos finais, limites e operação: [ATHENA_SDK.md](ATHENA_SDK.md). O primeiro incremento continua documentado como histórico em `ATHENA_IMPLEMENTACAO.md`.

## 1. Objetivo e referências

Evoluir a ATHENA de consulta local de clima para um subsistema de fontes externas, estado mundial, monitoramento e correlação, integrado às decisões do ATLAS. ATHENA fornece conhecimento externo; ATLAS mantém contexto pessoal, decide relevância e controla interação e ações.

Este é o ponto de entrada para acompanhar o trabalho. Referências:

- `ATHENA_ARQUITETURA_FUNDADORA.md`, versão 1.0, consultada em `C:/Users/Atlas/Downloads/ATHENA_ARQUITETURA_FUNDADORA.md`: visão arquitetural; arquivo externo ao repositório.
- [Análise de lacunas](ATHENA_ANALISE_DE_LACUNAS.md): diagnóstico histórico de 27/09, anterior à implementação.
- [Primeiro incremento](ATHENA_IMPLEMENTACAO.md): contratos, limites e validação da entrega inicial.

As fases deste plano são incrementos de trabalho. As versões 0.1–1.0 da arquitetura fundadora são marcos de capacidade, sem correspondência obrigatória de uma fase para uma versão. Não há datas de entrega ou estimativas de esforço confirmadas; elas dependem da validação inicial e das fontes escolhidas.

## 2. Estado inicial verificado e limites (antes da execução F1–F8)

| Área | Estado em 30/09 | Evidência / limite |
|---|---|---|
| Contratos e fronteira | Implementados no escopo de clima | `athena/core`; apenas `WEATHER` e `QUERY` |
| Gateway, normalização e proveniência | Implementados para clima | `athena/runtime/EventGateway.kt` no pacote Kotlin do módulo |
| Runtime, cache e deduplicação | Implementados em memória | Um conector, oito regiões e 64 IDs; sem persistência |
| Event bus | Implementado, entrega ao vivo | Sem replay; buffer de 64, descarte dos mais antigos |
| Protocolo e bridge | Implementados em processo | Versão 1; sem transporte remoto |
| Open-Meteo | Conector implementado | Condições atuais; não oferece previsão de tempestade ou alerta oficial |
| Integração Android | Conectada no código | Hilt, `WeatherRepository`, chat e widget |
| Saúde e métricas | Básicas | Sem política completa de retry, cooldown ou circuit breaker |
| Build e testes | Validação histórica registrada | 170 testes sem falhas e `assembleDebug` em 27/09; não repetidos ao redigir este plano |
| Aparelho / distribuição | Pendentes de comprovação | Documento inicial informa ausência de teste em aparelho; APK local não comprova instalação ou publicação |
| Git | Trabalho local ainda não consolidado | Novos módulos sem rastreamento e integrações modificadas na inspeção de 30/09 |
| Monitoramento, Island externa e correlação | Planejados | Ocorrências/plugins existentes não equivalem a esse fluxo |
| Cloud e SDK público | Planejados, Cloud condicional | Nenhum serviço remoto validado neste levantamento |

Os contratos atuais incluem `WeatherObservation`, `AthenaEvent.weather` e `WorldQuery` de clima. A interface de conectores ainda precisa evoluir antes do segundo domínio. O manifesto existente não constitui um SDK completo.

## 3. Regras de execução

1. ATHENA não depende de memória, contatos, conversas, histórico de localização, ferramentas pessoais ou UI do ATLAS.
2. Localização e permissões ficam no ATLAS. Parâmetros enviados à ATHENA são mínimos, com retenção limitada; regiões e seletores também podem permitir inferências e exigem minimização.
3. O conector produz um candidato; o gateway valida antes de publicar. Conteúdo externo permanece dado sem autoridade para executar ferramentas.
4. Proveniência, validade e incerteza acompanham os eventos. Severidade mundial não autoriza interrupção pessoal.
5. O ATLAS decide apresentação e ações. A integração com Island passa por um adaptador do ATLAS.
6. Estado em cache e notificações do bus têm contratos distintos. Entrega durável só será anunciada quando implementada e testada.
7. Cada fase termina com evidências de aceite. Código, validação automatizada, validação em aparelho e implantação são estados separados.

## 4. Sequência e dependências

| Fase | Entrega | Dependência | Situação |
|---|---|---|---|
| F0 | Fundação e consulta de clima | — | Implementada |
| F1 | Consolidar e validar a primeira entrega | F0 | Build/testes/APK; aceite físico pendente |
| F2 | Resiliência e diagnóstico por fonte | F1 | Implementada; testes de cooldown, retry e circuito |
| F3 | Contratos para múltiplos domínios e segundo conector | F2 | Implementada; v2 + adaptador v1 + GitHub Status |
| F4 | World State persistente e detecção de mudanças | F3 | Implementada; snapshot atômico com TTL e limites |
| F5 | Decisão ATLAS e apresentação na Island | F3 | Implementada; teste de política; aparência física pendente |
| F6 | Subscriptions e monitoramento Android | F4 + F5 | Implementada; suspensão/consumo em aparelho pendentes |
| F7 | Confiança e correlação com proveniência | F6 | Implementada; regra determinística de frio, confiança desconhecida |
| F8 | SDK, compatibilidade e preparação da 1.0 local | F7 | Guia, suíte comum e tarefa de validação; aceite físico pendente |
| F9 | ATHENA Cloud | Caso concreto + F6 + contratos de F8 | Opcional |

Ordem operacional sugerida: F1 → F2 → F3 → F4 → F5 → F6 → F7 → F8. Cloud não bloqueia a 1.0 local. Novos conectores entram um a um depois de F3, seguindo o mesmo aceite.

## 5. Fases executáveis

### F0 — Fundação entregue

Responsáveis técnicos: `:athena:core`, `:athena:runtime`, `:athena:connector-weather`, `:core:athena-integration`, composição em `:app` e consumo em `:core:data`.

- [x] Modelos tipados, manifesto inicial e protocolo local versionado.
- [x] Gateway, identidade canônica, deduplicação, cache e bus limitado.
- [x] Conector Open-Meteo com restrição de destino e limites de resposta.
- [x] Bridge e migração do clima no ATLAS, incluindo tratamento de validade no chat.
- [x] Testes e build registrados no primeiro incremento.
- [ ] Aceite em aparelho, realizado em F1.

Não reimplementar esta fase. Preservar seus contratos documentados até que uma migração explícita os substitua.

### F1 — Consolidar e validar a primeira entrega

**Resultado:** APK identificável, fluxo de clima comprovado em aparelho e alterações da ATHENA prontas para revisão isolada.

Entregas:

- Revisar alterações ATHENA e dependências incidentais, preservando o trabalho local da Island e de outras áreas.
- Executar novamente os testes pertinentes e gerar APK do mesmo estado de código.
- Registrar revisão ou identificação do snapshot, data, variante, aparelho, versão Android e resultados em `ATHENA_VALIDACAO.md`.
- Validar permissão concedida/negada/revogada, localização indisponível, consulta normal, modo offline, recuperação da rede, cache vencido e reinício do aplicativo.
- Conferir chat e widget: dado antigo não pode aparecer como recém-atualizado; nome da cidade assíncrono não pode substituir o de uma consulta posterior.
- Preparar consolidação no Git com escopo revisável, sem incluir mudanças alheias por conveniência.

**Aceite:** testes sem falhas; build concluído; cada cenário manual com resultado e evidência; nenhuma falha crítica aberta no fluxo; pendências não críticas explicitadas. Sem aparelho disponível, marcar validação manual como bloqueada, não concluída.

### F2 — Resiliência e observabilidade

**Resultado:** chamadas limitadas e diagnóstico suficiente para entender indisponibilidade e recuperação.

Entregas em runtime, transporte e contratos necessários:

- Cooldown por fonte para rate limit; tratamento de instrução de espera do provedor quando disponível.
- Retry limitado com backoff e jitter apenas para falhas transitórias elegíveis, dentro do deadline total.
- Circuit breaker com estados e recuperação testáveis; cancelamento do chamador sempre propagado.
- Latência, tentativas, cache hit/miss, rejeições, falhas e estados de saúde, sem registrar região, corpo externo ou segredo.
- Relógio e política de espera substituíveis em testes; limites configuráveis com valores documentados.

**Aceite:** testes determinísticos de timeout, rate limit, abertura/recuperação do circuito e cancelamento; nenhuma repetição ilimitada; validade do clima preservada. A prova de isolamento entre duas fontes fica em F3.

### F3 — Múltiplos domínios e segundo conector

**Resultado:** adicionar uma fonte sem alterar o núcleo pessoal do ATLAS.

Entregas:

- Evoluir candidato, payload, consulta e evento para tipos explícitos por domínio, mantendo o caminho de clima.
- Definir identidade, revisão, atualização/cancelamento e ordenação de eventos antes de ampliar a deduplicação.
- Registro de conectores e capabilities reais; concorrência e saúde isoladas por fonte, substituindo a serialização global quando necessário.
- Migrar protocolo/bridge com regra explícita de compatibilidade ou rejeição de versão; não mudar silenciosamente a semântica da v1.
- Criar suíte de contrato comum e um segundo conector. Proposta: status público de serviço, com provedor ainda a selecionar.
- Confirmar documentação, acesso, limites e termos da fonte na implementação. Não pressupor URL, credencial ou disponibilidade.

**Aceite:** clima e segundo domínio atravessam gateway → estado → bus/bridge; falha ou lentidão de uma fonte não bloqueia a outra; payload de domínio incompatível é rejeitado; regressões do clima passam.

### F4 — World State persistente e mudanças

**Resultado:** recuperar estado técnico ainda válido e emitir mudanças significativas, com armazenamento limitado.

Entregas:

- Contrato de armazenamento independente do Android e adaptador de disco separado dos dados pessoais do ATLAS; tecnologia escolhida por decisão técnica antes da implementação.
- Chaves por fonte/entidade/região, validade por domínio, quotas, limpeza ativa e migração de schema.
- Política explícita de retenção de seletores/regiões; nenhuma associação a identidade ou trajetória pessoal.
- Atualizar estado aceito antes de notificar consumidores; documentar atomicidade e recuperação se publicação falhar.
- Comparação determinística de estados, rejeição de respostas fora de ordem e supressão de oscilações repetitivas.
- Definir se consumidor tardio consulta snapshot ou usa mecanismo durável; o bus atual permanece sem garantia de replay até alteração explícita.

**Aceite:** reinício recupera somente dados válidos; vencidos são removidos; escrita interrompida não produz estado parcialmente aceito; estado idêntico não gera nova mudança; estado antigo não sobrescreve novo; quotas são respeitadas.

### F5 — Relevância pessoal e Island

**Resultado:** evento externo chega à apresentação somente após decisão do ATLAS, sem depender da tela de chat.

Entregas no lado ATLAS:

- Consumidor com ciclo de vida da aplicação e reconciliação com estado atual ao reconectar. Independência do chat não significa execução garantida com processo encerrado.
- Política testável de validade, relevância, repetição, preferência, horário de silêncio e permissão de interrupção.
- Decisões explícitas de ignorar, apresentar ou sugerir; histórico de decisão pessoal permanece no ATLAS.
- Adaptador para ocorrências/Island com identidade, atualização e remoção ao expirar ou resolver o evento.
- Controle para desativar apresentação externa e diagnóstico do motivo da decisão.
- Ações sugeridas atravessam a política de ferramentas existente; execução automática exige contrato e testes próprios antes de ser habilitada.

**Aceite:** com chat fechado e processo ativo, evento simulado relevante aparece; vencido, irrelevante ou suprimido não interrompe; atualização não duplica cartão; expiração remove apresentação; desativação funciona; nenhum conector importa UI ou chama ferramenta pessoal.

### F6 — Subscriptions e monitoramento Android

**Resultado:** observar condições por prazo definido e retomar apenas observações válidas.

Entregas:

- Criar, consultar e cancelar subscriptions com seletores tipados, IDs opacos, expiração obrigatória, quotas e operações idempotentes.
- Persistir apenas estado técnico necessário; associação ao contexto pessoal fica no ATLAS.
- Scheduler Android em camada de integração, com rede, bateria, intervalo e custo controlados. Definir comportamento para suspensão, reinício e encerramento do processo conforme as restrições da plataforma verificadas na implementação.
- Agrupar consultas equivalentes para evitar multiplicar chamadas; limpeza de subscriptions expiradas e canceladas.
- Exibir estado de monitoramento e permitir desligamento. Frequência pretendida e atraso observado devem ser distinguíveis.
- Registrar limites de latência, consumo e chamadas após medição em aparelho; alertas urgentes não podem depender de uma garantia de pontualidade não demonstrada.

**Aceite:** cancelar/expirar interrompe novas consultas e entregas da subscription; reinício recupera apenas as válidas; modo offline retoma sem tempestade de chamadas; testes de suspensão e consumo documentados; eventos aprovados percorrem F5.

### F7 — Confiança e correlação

**Resultado:** derivar conhecimento externo rastreável por regras explícitas.

Entregas:

- Evidências de origem, atualidade e independência entre fontes; manter confiança desconhecida quando não houver base calibrada.
- Uma regra de correlação com caso real, janela temporal, escopo e condições de invalidação definidos.
- Eventos derivados com referências aos eventos de origem, versão da regra e validade limitada pela evidência.
- Tratamento de contradições, retirada de evidência, ciclos e duplicação de fontes que republicam o mesmo conteúdo.
- Medir falsos positivos em fixtures representativas. Modelo generativo fica opcional e não participa de validação ou autorização.

**Aceite:** mesma entrada produz resultado determinístico; evidência ausente/vencida não sustenta conclusão; contradição preserva incerteza; origem pode ser inspecionada; nenhum dado pessoal é necessário à correlação mundial.

### F8 — SDK e preparação da ATHENA 1.0 local

**Resultado:** arquitetura reproduzível, compatível e pronta para manutenção de múltiplos conectores.

Entregas:

- Guia e exemplo mínimo de conector, manifesto, erros, retenção, segurança e suíte comum de contrato.
- Matriz de compatibilidade, política de evolução/depreciação e migrações testadas.
- Pipeline de validação automatizada de fronteiras, contratos, integração e build; testes de longo funcionamento para recursos limitados.
- Diagnóstico operacional, instruções de instalação/atualização e procedimento de desativação/retorno à versão anterior.
- Se um conector exigir segredos, implementar armazenamento seguro, revogação e tratamento de indisponibilidade antes de incluí-lo. Segredos não entram em eventos ou logs.

**Aceite da 1.0 local:** múltiplos conectores funcionais; resiliência e observabilidade; fronteira de dados pessoais validada; protocolo estável; monitoramento e apresentação testados em aparelho; documentação reproduzível; nenhuma falha crítica conhecida. Um terceiro conector de exemplo deve conseguir passar pela suíte sem mudanças no núcleo pessoal do ATLAS.

### F9 — ATHENA Cloud, quando houver necessidade concreta

**Gatilho:** requisito comprovado de webhooks, conexão persistente ou operação contínua que a implantação local não atende.

Entregas, após decisão de hospedagem e custo:

- Especificar transporte serializado, autenticação, autorização, isolamento de clientes, compatibilidade e minimização de seletores.
- Validar assinatura/origem, limitar tamanho e taxa, bloquear replay e encaminhar todo evento pelo gateway.
- Entrega com IDs idempotentes, confirmação, retentativa limitada, retenção e reconciliação após desconexão.
- Gestão de segredos, rotação, métricas, alertas operacionais e plano de indisponibilidade.
- Separar execução Cloud da memória pessoal do ATLAS; testar exclusão e expiração dos dados técnicos.
- Implantação em ambiente de teste, validação de ponta a ponta e liberação controlada com rollback.

**Aceite:** assinatura inválida e replay são rejeitados; isolamento entre clientes comprovado; reenvio não duplica efeitos no aparelho; reconexão entrega apenas estado válido; falha da Cloud tem comportamento documentado; custo e operação têm responsáveis definidos. Não contratar ou publicar infraestrutura apenas pela existência deste plano.

## 6. Expansão de fontes

| Ordem proposta | Fonte | Pré-requisito específico |
|---|---|---|
| Atual | Condições atuais de clima | Validar F1 |
| Segunda | Status público de serviço | F3 e escolha de provedor |
| Posterior | Previsão meteorológica / alertas públicos | Payload próprio; distinguir previsão de alerta oficial |
| Posterior | RSS / notícias | Limites de texto/XML, origem e conteúdo não confiável |
| Posterior | Trânsito / transporte | Fonte disponível, licença, custo e minimização geográfica |
| Sob demanda | Outros domínios da arquitetura | Caso de uso, contrato e suíte comum |

Fontes privadas, como e-mail, exigem delimitação específica de dados e responsabilidades; não entram automaticamente na ATHENA. Streams, MQTT e monitoramento de sites entram apenas quando uma fonte concreta justificar sua implementação.

## 7. Decisões do plano e resolução local

| Decisão | Proposta / restrição inicial | Resolver antes de |
|---|---|---|
| Equipamento e matriz de validação | Registrar aparelho disponível e Android; ampliar conforme suporte do app | Aceite F1 |
| Valores de retry/circuit breaker | Resolvida: três tentativas, cooldown padrão de um minuto, circuito após três consultas com falha; detalhes no SDK | Implementada F2 |
| Segundo provedor | Resolvida: API pública GitHub Status, sem autenticação ou texto de incidentes | Implementada F3 |
| Tipos e identidade multidomínio | Resolvida: v2 tipada e adaptador explícito v1; hash e ordenação temporal | Implementada F3 |
| Persistência e migração | Resolvida: JSON v2 privado, fora do backup, 128 KiB, substituição atômica; cache incompatível descartado | Implementada F4 |
| Entrega / reconciliação | Resolvida: snapshot autoritativo; bus sem replay; nenhum transporte remoto | Implementada F4–F6 |
| Critérios de relevância | Resolvida: opt-in, validade, horários e supressão por uma hora; sem execução automática de ferramentas | Implementada F5 |
| Frequência e orçamento energético | Intervalo pretendido de 15 min, rede e bateria não baixa; medição energética e atraso real pendentes | Aceite físico F6 |
| Primeira regra de correlação | Resolvida: duas observações recentes de até 0 °C, mesma fonte/região, evidências preservadas | Implementada F7 |
| Hospedagem, custo e operação | Cloud condicional; nenhuma plataforma selecionada | F9 |

As decisões devem ser registradas com motivo, alternativas relevantes, consequências e testes de aceite. Isso permite detalhar cada fase sem tratar hipóteses como escolhas já aprovadas.

## 8. Validação, implantação e recuperação

Comando existente para revalidar a base no PowerShell, em uma linha:

```powershell
.\gradlew.bat :athena:core:test :athena:runtime:test :athena:connector-weather:test :core:athena-integration:test :core:data:testDebugUnitTest :app:assembleDebug
```

Adicionar testes dos módulos alterados conforme a fase. Testes com provedor falso cobrem falhas e limites; validação manual comprova integração real. Ambos devem constar no registro de entrega, sem substituir um pelo outro.

Para cada entrega, registrar: escopo, identificação do código, testes executados e resultados, artefato, cenários em aparelho, limitações e procedimento de recuperação. O APK de debug atual é gerado em `app/build/outputs/apk/debug/app-debug.apk`; sua presença não equivale a release publicado.

Sequência de implantação: build local → teste em aparelho → correção e revalidação dos cenários afetados → liberação controlada → ampliação conforme evidências. Distribuição externa e infraestrutura são etapas próprias, não realizadas por este documento.

Preparar desativação de novas capabilities antes de liberar monitoramento/apresentação. Em caso de regressão, interromper novas subscriptions/apresentações e preservar a experiência estável conforme a configuração da entrega. Antes de persistência em disco, definir compatibilidade de downgrade ou limpeza segura apenas do cache técnico; nunca apagar dados pessoais como recuperação da ATHENA.

Principais riscos acompanhados: dados vencidos tratados como atuais; tempestade de chamadas; alertas repetidos; perda de eventos sem reconciliação; seletores formando histórico pessoal; uma fonte bloqueando outras; incompatibilidade de schema e protocolo. Cada risco possui aceite correspondente em F2–F8.

## 9. Execução e pendências

- [x] Revisar o diff da ATHENA e identificar dependências do trabalho local.
- [x] Reexecutar testes e gerar APK do estado revisado.
- [x] Criar `ATHENA_VALIDACAO.md` com evidências efetivamente obtidas.
- [ ] Executar a matriz de aparelho de F1 e corrigir falhas encontradas.
- [x] Consolidar documentação e alterações locais para revisão; nenhum commit/publicação realizado.
- [x] Implementar F2–F8; usuário autorizou concluir com APK e registrar validação física pendente.

Permanecem abertos o aceite em hardware, o orçamento energético medido e a distribuição de release. A implementação da correlação comprova a regra em fixtures; não foi calibrada como previsão ou alerta meteorológico.

O encerramento de uma fase exige atualizar sua situação e apontar a evidência. Checkboxes de planejamento não devem ser marcados apenas porque os arquivos foram criados.
