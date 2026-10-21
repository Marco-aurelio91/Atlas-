# ATHENA — primeiro incremento

Plano consolidado e acompanhamento das próximas fases: [ATHENA_PLANO_COMPLETO.md](ATHENA_PLANO_COMPLETO.md).

Este registro descreve o primeiro incremento de 27/09. A evolução local multidomínio de 30/09 está documentada em [ATHENA_SDK.md](ATHENA_SDK.md), com evidências em [ATHENA_VALIDACAO.md](ATHENA_VALIDACAO.md).

Este documento registra a implementação iniciada após a análise de lacunas. O relatório anterior descreve o estado antes destas mudanças.

## Escopo

Fundação local e consulta de condições atuais de clima. Não implementa previsão de tempestades, subscriptions, proatividade externa, correlação, persistência mundial em disco ou ATHENA Cloud.

Os módulos ATHENA são bibliotecas Kotlin/JVM sem dependência de Android, UI, memória pessoal ou ferramentas do ATLAS:

| Módulo | Responsabilidade |
|---|---|
| `:athena:core` | Eventos tipados, fontes, proveniência, região aproximada, contratos de consulta/conector e protocolo versionado |
| `:athena:runtime` | Gateway, validação, IDs canônicos, deduplicação, cache mundial temporário, bus, saúde e contadores |
| `:athena:connector-weather` | Cliente Open-Meteo, normalização, restrição de destino e leitura limitada |
| `:core:athena-integration` | Bridge do lado ATLAS, validação de versão, correlação, região e validade da resposta |

Fluxo:

```text
WeatherRepository (ATLAS: permissão e localização)
  → região arredondada para duas casas
  → AtlasAthenaBridge
  → protocolo v1
  → AthenaRuntime
  → OpenMeteoConnector
  → transporte HTTPS limitado
  → WeatherObservation (candidato tipado)
  → EventGateway (validação + normalização canônica)
  → cache + deduplicação + bus
  → resposta versionada
  → WeatherRepository
  → chat / widget existente
```

## Decisões de contrato

- O conector retorna um candidato; somente o gateway cria um `AthenaEvent` publicável. Isso resolve a ambiguidade entre retornar eventos prontos e normalizar depois do gateway.
- O MVP possui apenas capability `QUERY` e domínio `WEATHER`. Não anuncia streaming, polling ou lifecycle de conexão sem implementação.
- Payload de clima é tipado, sem campos de texto arbitrário, comando, usuário ou credencial. Campos extras do provedor não são encaminhados ao ATLAS.
- `GeographicRegion` aceita coordenadas em centésimos inteiros, impedindo precisão adicional no contrato. A região é parâmetro de consulta e chave temporária de cache, sem associação a identidade pessoal.
- O protocolo v1 é local, em processo. Envelope versionado não implica serialização de rede já implementada.
- `confidence` permanece desconhecida (`null`). As evidências explícitas não são convertidas em uma probabilidade inventada. Condições atuais não são tratadas como alerta oficial e usam severidade `INFO`.
- O bus é ao vivo, sem replay, com buffer de 64 e descarte dos eventos mais antigos para consumidores lentos. Não oferece entrega durável. O resultado da consulta é a resposta autoritativa para esse consumidor; novos observadores não recebem um histórico.
- O cache armazena no máximo oito regiões; a deduplicação, no máximo 64 IDs. Ambos são somente em memória, com remoção por validade na consulta seguinte e limitação de tamanho. Não existe histórico persistente. Limpeza física por timer no runtime está fora deste incremento.
- A validade é limitada pela retenção do conector (30 minutos) e pela idade da observação (uma hora). `maxAge` controla a idade desde a consulta ao provedor; zero força nova consulta. Um retorno recém-obtido não transforma uma observação antiga em condição atual.
- O deadline engloba a espera pelo mutex e a chamada do conector. Cancelamento do chamador é propagado; o transporte cancela a chamada HTTP.
- Consultas concorrentes são serializadas no primeiro runtime de um único conector. Isso evita duplicação de preenchimento de cache, mas não pretende ser a política futura de dezenas de fontes.

## Segurança e limites

O transporte aceita apenas HTTPS, host `api.open-meteo.com`, porta 443 e caminho `/v1/forecast`; desabilita redirects e limita a resposta descomprimida a 64 KiB. O parser verifica profundidade antes de desserializar e exige os campos/unidades esperados. O gateway rejeita valores não finitos, temperaturas fora da faixa aceita, códigos WMO desconhecidos, região incorreta e timestamps inválidos/vencidos.

Erros retornam códigos, sem corpo HTTP ou URL com região. O runtime não executa ferramentas, não acessa memória e não possui uma rota para a Island. A geocodificação existente continua no ATLAS e não atravessa o conector ATHENA.

A configuração do provedor utiliza os campos de condições atuais, unidades Celsius e timestamps Unix descritos na [documentação oficial do Open-Meteo](https://open-meteo.com/en/docs). Não há consultas reais com localização pessoal nos testes.

## Mudança percebida nos consumidores

O chat passa a esperar `getFresh()`, em vez de aceitar qualquer valor não nulo do `StateFlow`. Se a consulta atual falha ou não atende à validade, não apresenta silenciosamente o valor anterior como atualizado. O widget preserva sua API de leitura, com descarte da apresentação ao vencer.

A busca do nome da cidade é um enriquecimento assíncrono do ATLAS: a consulta de clima não espera a geocodificação. Na primeira resposta do chat o nome pode ainda não estar disponível; o widget recebe a atualização quando ela termina.

## Próximos incrementos

1. Política de resiliência por fonte: cooldown para rate limit, retry/backoff limitado, circuit breaker e latência.
2. Lifecycle de monitoramento e limpeza ativa quando necessários; persistência técnica com TTL se houver requisito de retomada.
3. Consumidor ATLAS independente do chat, relevância pessoal e política de apresentação antes de integrar eventos externos à Island.
4. Segundo conector e evolução dos contratos a partir de casos reais.
5. Subscriptions, change detection e correlação com proveniência.

Não há novas permissões Android nem cadastro de serviços externos neste incremento.

## Validação e alteração incidental

Validação realizada em 2026-09-27: **170 testes, zero falhas e zero erros**, sendo 23 testes novos neste incremento. `assembleDebug` concluído com sucesso também após o ajuste final do transporte.

```text
./gradlew :athena:core:test :athena:runtime:test :athena:connector-weather:test
          :core:athena-integration:test :core:data:testDebugUnitTest :app:assembleDebug
```

As tarefas devem ser passadas na mesma linha. A compilação completa passou, incluindo a composição Hilt do aplicativo. Os testes cobrem fronteiras de módulos, integração com provedor falso, validade e região, rejeição de dados, duplicatas, concorrência, deadlines, falha/recuperação de fonte, restrição HTTP e proteção contra geocodificação atrasada. Após o ajuste final de classificação do timeout HTTP, foram repetidos os testes do conector/bridge e a geração do APK.

Durante o build foi encontrada uma incompatibilidade já presente no trabalho local da Island: `IslandOverlayService` passava `otherCount`, mas `ActivityCircle` ainda não declarava o parâmetro. O componente foi ajustado para aceitar o contador e exibir `+N` quando positivo, preservando as demais alterações locais.

Não houve instalação nem teste em aparelho; a validação de localização/permissões, aparência e conectividade reais continua necessária. O APK de debug fica em `app/build/outputs/apk/debug/app-debug.apk`.
