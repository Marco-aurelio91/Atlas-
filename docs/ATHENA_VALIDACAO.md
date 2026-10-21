# ATHENA — registro da entrega local

Data: **2026-09-30**. Entrega: implementação local de F0–F8, candidata ao aceite da ATHENA 1.0 local. O usuário autorizou concluir com APK e deixar validação física pendente. Não houve instalação em aparelho, publicação de release ou implantação Cloud.

## Identificação

| Item | Valor |
|---|---|
| Revisão base | `3476f419df6cee0abd6d9b2c15f0f8124b17025e` |
| Estado do código | Checkout com alterações locais, incluindo trabalhos anteriores; nenhum commit criado |
| Validação automatizada concluída | `2026-09-30T16:31:03Z` |
| Variante / pacote | Debug / `com.unyx.atlas.debug` |
| Versão do launcher | `0.7.11`, versionCode `26`; não confundir com o marco arquitetural ATHENA 1.0 |
| APK | [app-debug.apk](../app/build/outputs/apk/debug/app-debug.apk) |
| SHA-256 do APK | `673B25F4F1ED9F911FFD73B08617D6FE2BC1369BABDD4DBC565BF1331EA7593A` |
| Tamanho | 88.168.971 bytes |
| Assinatura | `apksigner verify --verbose`: válida, esquema v2, um signatário; chave de debug |
| Pacote de testes Android | [app-debug-androidTest.apk](../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk), compilado; não executado |

Os links de artefatos apontam para arquivos de build locais, ignorados pelo Git e sujeitos a substituição no próximo build. O hash identifica esta entrega; os registros seguintes devem atualizar esse valor ao gerar outro APK.

## Resultado automatizado

**245 testes, zero falhas, zero erros e zero testes ignorados.** `validateAthena`, `assembleDebug` e `assembleDebugAndroidTest` concluídos com sucesso.

| Módulo | Testes |
|---|---:|
| `athena/core` | 3 |
| `athena/runtime` | 35 |
| `athena/connector-weather` | 6 |
| `athena/connector-status` | 9 |
| `core/athena-integration` | 4 |
| `core/data` | 149 |
| `feature/materialisland` | 39 |
| **Total** | **245** |

Cobertura relevante: fronteiras de módulos; contrato comum de três implementações de teste; compatibilidade v1/v2; isolamento de fontes; deadline incluindo fila; cancelamento; retry/cooldown/circuit breaker; rejeição de payload; limites de transporte; cache e resposta fora de ordem; persistência atômica; schema incompatível e arquivo corrompido; falha de disco; reinício/TTL; agrupamento, cancelamento e expiração de subscriptions; correlação com evidências; política de relevância, silêncio, dispensa e resolução.

O teste de estabilidade executa 200 atualizações do mesmo serviço e verifica estado limitado e supressão de mudanças duplicadas. Isso não substitui medição de consumo ou um teste prolongado em hardware. A correlação foi verificada em fixtures positivas e negativas, sem alegação de calibração meteorológica.

Os dois testes instrumentados preparados verificam abertura da tela com a composição Hilt real e persistência/reinício/cancelamento em armazenamento Android isolado. Foram compilados, mas não contam entre os 245 testes executados.

## Consulta real dos provedores

Executado `:core:athena-integration:liveAthenaProbe`, fora da suíte determinística. Saída:

```text
open-meteo.weather: ACCEPTED; schema=2; fresh=true
github.status: ACCEPTED; schema=2; fresh=true
```

A consulta percorreu os conectores reais, gateway e runtime v2 na JVM desta máquina. O clima usou uma região pública fixa de teste, sem ler localização do usuário ou do aparelho. O teste comprova conectividade e aceitação dos dados neste ambiente; não comprova permissões, rede ou execução em Android.

## Evidências locais e reprodução

- `build/athena-validation-run.log`: execução final de testes e empacotamento.
- `build/athena-validation.json`: contagens, revisão base e hash do APK.
- `build/athena-live-probe.log`: consulta real dos dois provedores.
- `build/athena-apk-signature.log`: verificação da assinatura.
- Relatórios JUnit em `build/test-results` de cada módulo listado acima.

Validação local:

```powershell
.\gradlew.bat validateAthena
```

Validação com resumo e hash:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-athena.ps1
```

A opção de execução acima vale somente para esse processo PowerShell; não altera a política global do Windows. O script também pode ser chamado diretamente onde a política permitir. Para executar a consulta real opcional:

```powershell
.\gradlew.bat :core:athena-integration:liveAthenaProbe
```

Quando houver aparelho disponível, executar `:app:connectedDebugAndroidTest` e completar a matriz abaixo. O Gradle mantém avisos de depreciação já existentes; eles não impediram o build.

## Matriz física pendente

Não havia aparelho autorizado conectado ao ADB. Tentativas de iniciar o emulador existente e um AVD Android 14 de validação não disponibilizaram um dispositivo utilizável. Os processos de emulador não permaneceram ativos. O usuário escolheu encerrar a entrega com APK e registrar esta limitação.

| Cenário | Situação |
|---|---|
| Instalação e abertura de Configurações → ATHENA | Pendente em aparelho |
| Permissão de localização concedida, negada e revogada | Pendente em aparelho |
| Localização ausente e atualização da região | Pendente em aparelho |
| Clima no chat/widget após migração para v2 | Compatibilidade automatizada passou; interação física pendente |
| GitHub acompanhado com chat fechado | Runtime/política testados; ciclo de vida Android pendente |
| Atualização, dispensa, resolução e expiração na Island | Política testada; aparência/overlay pendentes |
| Pausar tudo durante consulta e após reinício | Runtime testado; interação Android pendente |
| Offline, retorno da rede e rate limit real | Falhas simuladas passaram; rede Android pendente |
| Doze, economia de bateria, reinício e encerramento do processo | Pendente em aparelho |
| Latência de agendamento e consumo em 24 horas | Pendente de medição; nenhum orçamento empírico declarado |

Não marcar essas linhas como aprovadas por terem testes unitários relacionados. A arquitetura local está implementada; a homologação física e a distribuição de release continuam abertas.

## Operação e recuperação

Acesso: **Configurações → ATHENA**. Clima e GitHub têm opt-in separado, com duração de 24 horas. Apresentação na Island é opcional; silêncio entre 22h e 7h vem habilitado. A Island precisa estar ativa nas configurações do launcher.

Para interromper: **Pausar tudo**. Isso desativa acompanhamento e projeção, preservando consultas de clima sob demanda do chat/widget. Se a fonte falhar, a tela apresenta diagnóstico e o runtime aplica os limites de tentativa; dado vencido não é apresentado como atualização bem-sucedida.

Em downgrade, somente o cache técnico ATHENA é descartável/reconstruível. Não limpar dados do launcher ou memória pessoal. Esta entrega usa cache v2 em `noBackupFilesDir`, separado das preferências pessoais de acompanhamento e dispensa. Nenhuma credencial ou infraestrutura externa foi configurada.

## Escopo revisável

Novos componentes: `WorldProtocol`, `WorldRuntime`, gateway multidomínio, store de arquivo, correlação de frio, conector GitHub Status, adaptador v1, política de apresentação, coordenador/worker/tela Android e plugin de projeção na Island. O `WeatherRepository` expõe a região da apresentação atual ao coordenador do ATLAS.

Composição e acesso alterados em `AthenaModule`, `LauncherApplication`, manifesto, dependências Gradle, registro de plugins e entrada de configurações. Alterações anteriores de chat, memória, bateria e Island foram preservadas; não devem ser incluídas indiscriminadamente em um futuro commit da ATHENA.

Contratos e limites finais: [ATHENA_SDK.md](ATHENA_SDK.md). Acompanhamento: [ATHENA_PLANO_COMPLETO.md](ATHENA_PLANO_COMPLETO.md).
