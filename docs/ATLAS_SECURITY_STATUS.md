# AtlasSegurit — status da implantação

**Status geral:** migrations de segurança aplicadas em produção e verificações pós-implantação aprovadas. A função `atlas-ai` está ativa (versão 3), com JWT obrigatório. O gateway usa esforço `low` para Claude e a versão nova do app configura nível baixo e streaming para o Gemini. O release `0.7.44` foi gerado; a medição da latência com ele ainda depende do teste no aparelho. Não declarar E2EE validado em produção.

## Concluído localmente

- Chave de conta de 256 bits protegida pelo Android Keystore e código de recuperação de alta entropia.
- Envelope de recuperação cifrado com HKDF-SHA256 e AES-256-GCM. O código e a chave em claro não são enviados ao Supabase.
- Tela **Configurações → Segurança e recuperação**, com confirmação de que o código foi guardado fora do aparelho.
- Dados de memória sincronizados pelo `MemoryRepository` cifrados no aparelho e guardados em `atlas_encrypted_objects`; filtros, ordenação e busca lexical são locais.
- Migração recuperável de linhas legadas do UUID autenticado: copia, lê de volta, compara e só então apaga a linha antiga. Linhas com `user_id = 'default'` não são atribuídas sem validar o dono.
- Busca remota por embeddings desativada para evitar enviar texto privado ao serviço de vetores.
- Claude e Gemini encaminhados por `atlas-ai`. Chaves de provedor não são incluídas no APK; valores locais antigos foram removidos dos arquivos ignorados do Gradle.
- Quatro testes locais de criptografia e recuperação passaram; o build release e o lint vital passaram.
- Release assinado `0.7.44` (`versionCode 59`) gerado em 2026-10-15; assinatura v2 verificada.
- Cópia para teste: `C:\Users\Atlas\Desktop\Atlas-0.7.44-release.apk`.
- SHA-256 do APK: `DB03D23CFD67B2FF06E978B5863743C6AB6285020CE06AFF625811C9F9B731C1` (49.045.980 bytes).
- As migrations aplicaram sem erro na instância Supabase local; `supabase db lint --local --fail-on error` não apontou erros.
- Doze testes pgTAP locais passaram. Teste isolado confirmou que a migration para diante de linhas `default` e aplica a política de dono às 15 tabelas legadas.
- MFA TOTP opcional integrado ao login e a **Configurações → Segurança e recuperação**. O fluxo local de cadastro, desafio, verificação AAL2 e limpeza do usuário de teste passou no Supabase local; o Auth REST usa `GET /user` para listar fatores.
- TOTP habilitado no `supabase/config.toml` para desenvolvimento local. A configuração MFA/AAL2 do projeto remoto não foi alterada e continua pendente de validação.
- Roteiro de operação, publicação, backup/restauração e incidentes criado em `docs/ATLAS_SECURITY_RUNBOOK.md`.
- Varredura Gitleaks no histórico Git terminou sem achados. A verificação dos arquivos fonte também passou; o arquivo local ignorado `supabase.properties` gera um falso positivo para `SUPABASE_ANON_KEY`, que é a chave pública de cliente.
- Pipeline GitHub Actions preparado em `.github/workflows/security.yml`: testes Android, Supabase local/pgTAP, CodeQL, Gitleaks e envio do grafo Gradle. Ele ainda precisa executar no GitHub; nenhum resultado remoto foi recebido.

## Estado da implantação remota

1. O projeto `Atlas` (`hqfezdzvsmuakheldtcr`) foi confirmado como produção e é o projeto vinculado no workspace. O proprietário pediu explicitamente para continuar nele, sem staging.
2. Antes das mudanças, foi feito o backup lógico descrito abaixo. Em 2026-10-14, as cinco migrations `20261014120000` a `20261014160000` foram aplicadas em produção sem erro. A lista remota não mostra migrations pendentes.
3. A verificação somente leitura posterior confirmou zero linhas `user_id = 'default'` nas 15 tabelas legadas, 15 políticas de proprietário e nenhum privilégio de leitura/inserção de `anon` em `memory_notes` ou `memory_places`.
4. As tabelas `atlas_ai_usage`, `atlas_encrypted_objects` e `atlas_key_recovery_envelopes` existem. A função de quota só pode ser executada por `service_role`; o trigger de atualização de `atlas_encrypted_objects` está presente.
5. A Edge Function `atlas-ai` está `ACTIVE`, versão 3, com `verify_jwt = true`. Os secrets `ANTHROPIC_API_KEY` e `GEMINI_API_KEY` foram atualizados; `ANTHROPIC_MODEL=claude-sonnet-5` e `GEMINI_CHAT_MODEL=gemini-3.8-flash` estão configurados. O gateway define esforço baixo para o Claude. O app `0.7.44` envia `thinkingLevel=LOW` ao Gemini 3 e usa `streamGenerateContent` para exibir os trechos assim que chegam. A redução de latência ainda precisa ser medida em uma sessão autenticada no aparelho.
6. Backup lógico anterior às migrations: `%LOCALAPPDATA%\Atlas\SecurityBackups\Atlas-prod-20261014-170027`.
   - Contém cinco dumps DPAPI de `public`, `auth`, `storage`, `supabase_migrations` e papéis personalizados; todos foram descriptografados em memória para confirmar legibilidade, e os hashes estão em `manifest.json`.
   - DPAPI está vinculado ao perfil Windows atual; ACL restrita à conta Atlas, SYSTEM e Administradores.
   - Consultas confirmaram zero buckets e zero objetos do Storage. Não havia arquivos para copiar.
   - É um backup lógico: não contém todos os schemas internos/extensões, não há snapshot físico/PITR e restauração ainda não foi testada.
7. A primeira migration também verificou que não havia linhas legadas sem proprietário. Nenhum conteúdo de memória foi lido. As migrations alteraram schema, políticas e privilégios, sem editar dados existentes do aplicativo.
8. A implantação direta em produção foi feita a pedido do proprietário. Não houve validação em staging nem testes ponta a ponta contra produção. A CLI provisionou logins temporários PostgreSQL para os dumps/comandos; a Supabase informa que eles expiram após algumas centenas de segundos.

## Trabalho de segurança ainda necessário

- Instalar o release `0.7.44` e medir latência/streaming; validar separadamente respostas de Claude e Gemini, sem enviar chaves ou tokens de sessão no chat ou no repositório.
- Revisar os logs da Edge Function durante a chamada autenticada. O código usa `verify_jwt = true` e também valida o usuário em Auth.
- Validar o MFA TOTP com o app e impor AAL2 no servidor. A interface do app, por si só, não exige MFA em todas as operações.
- Implementar inventário/revogação de dispositivos e rotação da chave de conta. Recuperação por código já existe; isso não implementa pareamento seguro entre aparelhos.
- Fazer restauração real do backup em projeto isolado e testar exportação/exclusão ponta a ponta.
- Definir retenção, consentimentos, privacidade/LGPD e resposta a incidentes para a operação real.
- Executar testes RLS multiusuário, Storage, funções e quotas em ambiente isolado; obter resultados de CodeQL/SCA no GitHub; fazer DAST e pentest independente.
- Ampliar CI com SBOM, proveniência e assinatura controlada; revisar alertas do grafo de dependências e confirmar code scanning.

## Próximos passos

1. Instalar `Atlas-0.7.44-release.apk`, enviar uma pergunta curta e observar o tempo até o primeiro trecho e até a resposta completa.
2. Conferir os logs e os registros de quota para validar as chamadas autenticadas ao gateway.
3. Completar a validação do app, MFA/AAL2, restauração do backup e os controles operacionais restantes antes de considerar o serviço pronto para produção.

O APK `0.7.44` está pronto para teste. A função `atlas-ai` versão 3 está ativa. A latência real ainda precisa ser confirmada no aparelho após a instalação; as validações de provedores, MFA/AAL2 e restauração do backup continuam pendentes.
