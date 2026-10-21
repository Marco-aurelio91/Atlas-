# AtlasSegurit — roteiro de operação e implantação

Este roteiro acompanha o plano de segurança. Aplique primeiro em um projeto Supabase de staging com dados sintéticos. O APK `0.7.31` não configura o projeto remoto por conta própria.

## 1. Preparação e credenciais

1. Revogue o token pessoal Supabase e as chaves Claude/Gemini que foram compartilhados anteriormente; emita credenciais novas.
2. Entre no Supabase pelo mecanismo local da CLI (`supabase login`) e confirme o projeto no Dashboard. Não cole tokens ou chaves neste repositório, em tickets ou no chat.
3. Confira que `supabase/.temp/project-ref` corresponde ao projeto de staging antes de executar qualquer comando de publicação.
4. Em **Supabase Dashboard → Edge Functions → Secrets**, configure `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, `GEMINI_API_KEY`, `GEMINI_CHAT_MODEL` e `GEMINI_EMBEDDING_MODEL`. Use credenciais separadas por ambiente. Não crie um arquivo `.env` persistente no workspace.

## 2. Auditoria de linhas antigas

Antes da migration `20261014120000_atlas_memory_rls.sql`, consulte apenas contagens de `user_id = 'default'` nas 15 tabelas de memória. Não exporte os conteúdos para logs ou planilhas. A migration falha de propósito enquanto houver linhas sem proprietário confirmado.

Se houver registros, valide no painel Auth qual UUID pertence à conta do Atlas e confirme com o proprietário dos dados antes de atualizar. Faça a associação em transação, com backup anterior, e confira a contagem por tabela antes/depois. Não associe automaticamente com base no primeiro usuário que entrar.

## 3. Publicar em staging

No diretório raiz do projeto, com o CLI autenticado e o projeto correto vinculado:

```powershell
supabase.cmd migration list
supabase.cmd db push
supabase.cmd functions deploy atlas-ai
supabase.cmd test db
```

O `db push` aplica migrations em ordem de timestamp. Revise o diff e os alvos mostrados pelo CLI antes de confirmar. Não use `--include-all` para contornar uma migration pendente sem revisar o histórico remoto.

Antes do teste, habilite TOTP em **Authentication → Multi-Factor Authentication** no projeto de staging. Teste cadastro e confirmação de um fator, novo login exigindo o código, código incorreto/expirado, cancelamento do login e remoção do fator. A autenticação do app consulta os fatores verificados do usuário; a exigência AAL2 também precisa ser aplicada às operações protegidas no servidor.

Depois, valide no staging:

- dois usuários de teste isolados; `anon` sem acesso às tabelas de memória;
- gravação e leitura de um objeto cifrado e falha com ciphertext adulterado;
- uso de Claude/Gemini com JWT válido, rejeição sem sessão e limites de cota;
- restauração da ARK em aparelho limpo com o código correto e falha com código incorreto;
- ausência de prompts e respostas em logs, traces e analytics;
- execução da migration com e sem registros legados `default` em uma base descartável.

Os testes SQL automatizados de RLS estão em `supabase/tests/atlas_security_test.sql`.

## 4. Backup, restauração e exclusão

Antes de cada mudança de schema, exporte o PostgreSQL e faça cópia independente dos objetos do Storage; o backup do banco não contém os arquivos do bucket. Restrinja acesso às cópias e criptografe-as no destino. Se a origem ainda possuir linhas legadas em texto claro, trate o backup como dado sensível em texto claro.

Restaure primeiro em um projeto isolado. Compare contagens e checksums de envelopes/objetos, valide políticas RLS e recupere conteúdo somente com uma conta e chave de teste. Registre data, responsável, resultado e tempo para restauração. Não conclua que o backup funciona até completar uma restauração real.

Defina e registre prazos aprovados para memórias, arquivos, logs e cópias de backup. A exclusão da conta deve abranger linhas PostgreSQL, objetos de Storage e filas/retentativas; documente o prazo até a expiração de cópias. Os prazos e bases legais precisam ser definidos pelo responsável do serviço, não presumidos pelo código.

## 5. Resposta a incidentes

| Evento | Ações iniciais |
|---|---|
| Chave de provedor exposta | Revogar e emitir outra no fornecedor; atualizar Supabase Secrets; procurar uso indevido e custo; remover a cópia do repositório/histórico quando aplicável. |
| Suspeita de `service_role` exposta | Rotacionar imediatamente no Supabase, revisar logs de administração e funções, invalidar cópias antigas e investigar acessos. Nunca colocar a chave nova no APK. |
| Dump de banco ou Storage exposto | Restringir acesso, preservar evidências, determinar escopo e dados legados em claro, seguir o processo de comunicação aplicável e não publicar conteúdo do incidente em logs abertos. |
| Aparelho perdido | Revogar sessão no Auth, orientar troca de credenciais, registrar o dispositivo como não confiável quando o registro/revogação estiver implantado e avaliar risco de dados locais. Revogação remota não apaga cópias offline. |
| Provedor de IA comprometido | Desabilitar o provedor no gateway, rotacionar secrets se necessário, limitar novas divulgações e identificar quais requisições foram enviadas sem armazenar prompts em relatórios. |

Para todo evento, registrar horários, decisões, responsáveis, sistemas afetados e evidências redigidas. Não registrar tokens, códigos de recuperação, ARK, prompts ou conteúdo de memória. Os prazos legais de notificação e o canal de escalonamento precisam ser preenchidos pelo responsável antes do lançamento.

## 6. Liberação de produção

Não promover staging até MFA, gestão/revogação de dispositivos, política de retenção/exclusão, backup restaurado, testes RLS/API, SAST/SCA/DAST e revisão externa terem evidência anexada ao checklist de release. Registrar versão do app, migration aplicada, hash do APK, resultado dos testes e plano de rollback.

