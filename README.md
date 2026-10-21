# Atlas

[![Licença: Apache-2.0](https://img.shields.io/badge/Licen%C3%A7a-Apache%202.0-blue)](LICENSE)
[![Feito com Jetpack Compose](https://img.shields.io/badge/Feito%20com-Jetpack%20Compose-4285F4)](https://developer.android.com/jetpack/compose)

Um assistente de IA para Android — chat com ferramentas, memória cifrada e sincronizada,
automações locais, notas e um chat humano-a-humano com criptografia de ponta a ponta — construído
inteiramente com **Jetpack Compose**.

> Status: em desenvolvimento, mas usado diariamente. Construído do zero sobre uma stack atual de
> Kotlin/Compose.
>
> Atlas **não é mais** uma tela inicial (launcher) Android — essa era uma versão anterior do
> projeto. O app de hoje não registra nenhuma Activity com `category.HOME`; cada recurso abre pelo
> próprio ícone na gaveta de apps do seu launcher normal.

## Recursos

- **Chat com IA** — conversa com Claude (com fallback para Gemini), streaming de respostas,
  busca na web (via Claude) e chamadas de ferramentas que executam ações reais no aparelho.
- **Assistente rápido** — abre pelo gesto de assistente do sistema (quando o Atlas é definido
  como assistente padrão do telefone), e aparece como destino ao compartilhar texto, imagem ou
  PDF de qualquer outro app. Também oferece análise/captura de tela sob pedido do usuário.
- **Ferramentas do assistente** no aparelho: abrir apps, ajustar volume/brilho, criar
  alarme/lembrete de calendário, consultar o clima, preparar mensagem no WhatsApp, abrir o
  discador para uma pessoa, abrir o painel de internet, entre outras.
- **Memória** — fatos, preferências, decisões, pessoas, lugares, tarefas, projetos, notas,
  eventos e rotinas, sincronizados com um backend próprio (Supabase) e protegidos por
  criptografia local (Android Keystore).
- **Automações locais** — gatilhos por horário, conexão do carregador, fone de ouvido, bateria
  baixa e geofence, com ações como abrir um app ou ajustar volume/brilho.
- **Notas** — criação e edição com salvamento automático, busca, checklists, tags, cores,
  fixar/arquivar/lixeira, e ações de IA (resumir, organizar, corrigir, extrair tarefas, sugerir
  lembrete).
- **Atlas Connect** — chat humano-a-humano com criptografia de ponta a ponta (X25519 + ratchet
  simétrico): pareamento por QR, perfis/avatares, respostas, busca local, bloqueio e
  silenciamento.
- **ATHENA** — camada de percepção em segundo plano (clima, status de serviços) que alimenta o
  restante do app; não é um assistente ou tela própria.

## Stack técnica

- **Kotlin** + **Jetpack Compose** (Material 3), Hilt, WorkManager, OkHttp.
- **Android Keystore** para cifragem local (memória, transcritos, sessões do Connect).
- **Supabase** para autenticação, memória sincronizada e funções de borda (gateway de IA,
  consultas da ATHENA).
- Arquitetura multi-módulo (22 módulos) com plugins de convenção em `build-logic`.
- JDK 21, AGP 9, Gradle 9.

## Build (compilação)

Você precisa do **JDK 21** (o JBR do Android Studio funciona bem) e do Android SDK.

```bash
# Aponte o build para o seu SDK (ou defina sdk.dir em local.properties)
./gradlew :app:assembleDebug
```

Instalar em um dispositivo conectado:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: `minSdk 30` (Android 11 ou mais recente), `targetSdk 37`.

## Estrutura do projeto

- `app` — as Activities (Chat, Assistente rápido, Notas, Connect, Configurações), o manifesto, o
  ponto de entrada da injeção de dependência e o WorkManager.
- `core/model`, `core/designsystem`, `core/ui` — modelos, tema e componentes Compose
  compartilhados (voz, avatares, utilitários).
- `core/data`, `core/memory`, `core/security` — preferências, clima, repositórios de dados,
  memória sincronizada, autenticação e cifragem.
- `core/chat-crypto`, `core/chat-pairing`, `core/chat-storage`, `core/chat-remote` — criptografia,
  pareamento, armazenamento local e sincronização de mensagens do Atlas Connect.
- `feature/chat`, `feature/connect`, `feature/notes`, `feature/settings`, `feature/updater` —
  telas e lógica de cada recurso.
- `athena/*`, `core/athena-integration` — protocolos, runtime e conectores (clima, status) da
  camada de percepção ATHENA.

## Licença

Licenciado sob a **Apache License, Version 2.0** — veja [LICENSE](LICENSE) e
[NOTICE](NOTICE) para créditos de terceiros.
