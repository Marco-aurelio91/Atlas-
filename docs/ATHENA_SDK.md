# ATHENA local — contratos e guia de conectores

## Contratos e compatibilidade

O endpoint `AtlasAthenaProtocol` v1 continua exclusivo de clima. A composição Android agora usa `WorldWeatherProtocol`, que traduz explicitamente a resposta v2 para o evento v1 consumido por `AtlasAthenaBridge`. O antigo `AthenaRuntime` permanece como implementação de referência v1 e tem testes de regressão; não é instanciado pela aplicação.

`WorldProtocol` v2 aceita `WorldRequest`, seletor tipado (`Weather` ou `Service`), `maxAge` e deadline total. Uma versão desconhecida retorna `UNSUPPORTED_VERSION` antes de consultar a rede. Ambos os protocolos são locais, em processo. Não existe serialização de transporte remoto.

| Contrato | Versão | Compatibilidade |
|---|---|---|
| Clima legado / chat / widget | 1 | Preservada pelo adaptador |
| Estado multidomínio | 2 | Rejeita versões desconhecidas |
| Arquivo técnico | 2 | Revalida e descarta schema incompatível; dados pessoais não são afetados |
| Regra de frio persistente | 1 | Duas observações, sem probabilidade inventada |

Mudanças incompatíveis em payload, chave, tempo ou interpretação exigem nova versão e testes de migração. A implementação atual não promete leitores antigos para arquivos futuros. No downgrade, o cache técnico pode ser reconstruído. Preferências pessoais de apresentação estão no ATLAS.

## Adicionar um conector

1. Criar módulo Kotlin/JVM dependente de `:athena:core`. Não importar Android, ATLAS, UI, Hilt ou bancos pessoais.
2. Implementar `WorldConnector`, declarar `WorldManifest` com ID estável, fonte e retenção entre um segundo e uma hora. `accepts` delimita os seletores atendidos; sobreposição entre conectores faz a consulta falhar, sem escolha implícita.
3. Retornar `WorldCandidate` com o seletor solicitado, payload tipado e timestamp da fonte. Somente o gateway cria eventos aceitos; conectores não produzem eventos derivados.
4. Usar transporte com destino fixo ou allowlist, HTTPS, timeout, cancelamento, limite de bytes descomprimidos e profundidade de parsing. Não seguir redirects implicitamente. Descartar campos externos não usados. Não encaminhar URLs, corpos HTTP ou segredos em erros.
5. Mapear falhas para `ConnectorException`; `retryAfter` pode acompanhar `RATE_LIMITED`. Dados inválidos não recebem retry. O runtime controla as tentativas.
6. Adicionar testes de parser/transporte e herdar `WorldConnectorContract` com um provedor falso. Dependência Gradle: `testImplementation(testFixtures(project(":athena:core")))`.
7. Registrar a instância em `AthenaModule`, sem alterar memória pessoal ou dispatcher de ferramentas. Novos domínios exigem evolução explícita dos tipos; serviços adicionais usam o seletor `Service` existente.
8. Adicionar controle de acompanhamento do lado ATLAS para qualquer nova fonte voltada ao usuário. Não iniciar consultas automáticas por mera instalação do conector.

Exemplos executáveis: `StatusContractTest`, `WeatherContractTest` e `ExampleServiceContractTest`. O terceiro é um serviço fictício usado para comprovar extensibilidade, não uma terceira integração de produção.

## Estado, identidade e entrega

A identidade é SHA-256 de fonte, seletor, timestamp de ocorrência e payload tipado. Uma observação repetida mantém identidade; refresh atualiza a data de consulta. Uma mudança no payload notifica o bus; resposta com timestamp anterior ao estado aceito é rejeitada. Não há cancelamento de incidentes remotos no domínio inicial de status agregado: a resolução é representada por `OPERATIONAL`.

O snapshot `state` é autoritativo. `changes` é ao vivo, sem replay, com buffer de 64 e descarte dos mais antigos. Consumidores devem reconciliar o snapshot ao reconectar. O disco é confirmado antes de publicar novo estado; falha de escrita impede a publicação e incrementa diagnóstico. Expiração remove a projeção em memória mesmo se o disco estiver indisponível; todo conteúdo persistido é revalidado ao reiniciar.

O arquivo em `noBackupFilesDir/athena/world-v2.json` tem schema e limite de 128 KiB. Escrita usa arquivo temporário, sincronização e substituição atômica. Há no máximo 32 eventos e 16 subscriptions. Só a região de clima mais recente é mantida no runtime; não se acumula trajetória. Eventos derivados permanecem em memória e são recalculados a partir de nova evidência após reinício. O arquivo fica fora do backup Android.

Subscriptions têm duração máxima de 24 horas, expiração e cancelamento idempotente. Polling agrupa seletores iguais. Cancelar durante uma chamada já iniciada suprime sua entrega pela subscription e impede novas consultas dela; o resultado da consulta pode ainda atualizar o cache compartilhado. Não há promessa de apagar um resultado também usado por outro consumidor.

## Resiliência e confiança

Defaults: até três tentativas; backoff de 250 ms, depois 500 ms, mais jitter de até o mesmo valor; tudo dentro do deadline. Rate limit não é repetido imediatamente. `Retry-After` aceita segundos ou data HTTP, limitado a uma hora. Sem header, cooldown de um minuto. Três consultas com falha abrem o circuito; após cooldown há uma tentativa de recuperação. Cache válido pode responder durante cooldown.

Locks e saúde são independentes por conector. Métricas e circuitos são locais ao processo, sem continuidade prometida após reinício. A persistência cobre eventos e subscriptions. Erros, tentativas, latência, rejeições, duplicatas, cache e falhas de disco ficam visíveis no diagnóstico.

Confiança numérica permanece desconhecida. A regra `SustainedCold` exige duas observações da mesma fonte/região com temperatura até 0 °C, separadas por 10–45 minutos, ambas válidas. Preserva IDs, fonte e tempos das duas evidências; não afirma confirmação independente, previsão de geada ou alerta oficial. Uma nova observação mais quente remove o derivado. Os testes incluem negativos por evidência ausente, vencida, contraditória e de outra fonte/região; não constituem calibração meteorológica em campo.

## Android e apresentação

`AthenaCoordinator` vive fora do chat. O worker usa WorkManager, intervalo pretendido de 15 minutos, rede conectada e bateria não baixa; o Android pode adiar a execução. O opt-in expira em 24 horas e não é renovado por reinício. O processo ativo verifica validade e apresentação a cada 30 segundos. Portanto, a remoção visual por vencimento pode atrasar até esse intervalo enquanto o processo está ativo.

Região e localização permanecem sob controle do ATLAS. Falha de localização ou revogação de permissão suspende a subscription de clima. GitHub pode continuar independente. Sem permissão de localização em segundo plano, não se promete atualização de posição quando o Android não disponibiliza um fix ao aplicativo.

Apresentação exige fonte acompanhada, evento válido e preferência habilitada. Silêncio padrão: 22h–7h no fuso do aparelho, configurável como liga/desliga. Dispensar suprime o evento e a mesma categoria por uma hora, em preferências do ATLAS, incluindo reinício. Resolução ou expiração remove o cartão. A Island precisa estar habilitada; ativar a projeção ATHENA não concede permissões de overlay. Nenhuma ação pessoal é executada automaticamente.

## Fontes técnicas consultadas

- [API pública do GitHub Status](https://www.githubstatus.com/api): endpoint de status agregado e indicadores `none`, `minor`, `major`, `critical`; nenhuma credencial requerida pelo endpoint documentado.
- [Definição de trabalho periódico no Android](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): agendamento periódico e restrições. A frequência configurada não é uma garantia de pontualidade.

Não há coleta de texto de incidentes, contas privadas ou credenciais nesta implantação. Novas fontes precisam de revisão de acesso, termos e limites próprios antes de serem adicionadas.

## Validação e operação

Executar `./gradlew validateAthena` ou, no PowerShell, `./scripts/validate-athena.ps1`. O script produz `build/athena-validation.json`, incluindo total de testes e SHA-256 do APK. Os testes automatizados não substituem o aceite no aparelho, registrado em [ATHENA_VALIDACAO.md](ATHENA_VALIDACAO.md).

`validateAthena` também compila o APK dos testes instrumentados, sem executá-los. Com um aparelho autorizado, executar `./gradlew :app:connectedDebugAndroidTest`. A tarefa opcional `:core:athena-integration:liveAthenaProbe` consulta os dois provedores com região pública fixa; não participa dos testes determinísticos.

Para interromper monitoramento e apresentação: Configurações → ATHENA → Pausar tudo. O clima sob demanda do chat/widget continua disponível. Para recuperação de uma versão defeituosa, interromper acompanhamento, instalar o APK previamente validado e restaurar somente o cache técnico se necessário. Não apagar dados do launcher ou memória pessoal. Não foi configurada publicação automática nem ATHENA Cloud.
