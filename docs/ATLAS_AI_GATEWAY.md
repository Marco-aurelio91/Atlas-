# Gateway de IA do Atlas

O app Android chama `supabase/functions/atlas-ai` com a sessão Supabase do usuário. O gateway valida essa sessão e só então encaminha a operação para Anthropic ou Google. As chaves de provedor ficam como segredos do Supabase Edge Function e não são incluídas no APK.

## Segredos necessários no projeto Supabase

Configure no painel do Supabase, em **Edge Functions → Secrets**, ou via Supabase CLI:

- `ANTHROPIC_API_KEY`
- `ANTHROPIC_MODEL`
- `GEMINI_API_KEY`
- `GEMINI_CHAT_MODEL`
- `GEMINI_EMBEDDING_MODEL` (opcional; o gateway usa `gemini-embedding-001` como padrão)

Não grave os valores desses segredos em arquivos versionados, no `BuildConfig`, no APK ou nesta documentação. `SUPABASE_URL` e `SUPABASE_ANON_KEY` são configurações públicas do projeto; o cliente Android usa esses valores para chamar Supabase.

## Publicar

1. Vincule a CLI ao projeto remoto correto (`supabase link`) e confirme o project ref no painel.
2. Configure os segredos listados acima no projeto correto.
3. Aplique `20261014120000_atlas_memory_rls.sql` somente após revisar os dados legados e confirmar os UUIDs proprietários.
4. Aplique `20261014130000_atlas_ai_quota.sql` para criar o limitador atômico usado pela função.
5. Aplique `20261014140000_atlas_encrypted_objects.sql` antes de usar o repositório de objetos cifrados.
6. Aplique `20261014150000_atlas_key_recovery_envelopes.sql` antes de habilitar a configuração de recuperação de chave no app.
7. Aplique `20261014160000_atlas_encrypted_objects_updated_at.sql` para habilitar os timestamps de sincronização incremental.
8. Publique a função: `supabase functions deploy atlas-ai`.
9. Gere um novo APK e valide o login e as três operações: Claude, Gemini de reserva e embeddings.

O gateway exige JWT (`verify_jwt = true`) e também valida a sessão consultando Supabase Auth. As cotas iniciais são 20 chamadas de chat por minuto e 150 por dia; embeddings têm 60 por minuto e 1.000 por dia. O controle usa `SUPABASE_SERVICE_ROLE_KEY` somente dentro da Edge Function, para chamar um RPC que não pode ser executado pelo cliente. Se a RPC falhar, chamadas são bloqueadas. O gateway também limita o tamanho da requisição a 18 MiB, quantidade de mensagens/ferramentas e tokens máximos de saída. Para deixar margem para a expansão Base64 e o restante do histórico, o app limita cada PDF novo a 10 MiB e só inclui na requisição o PDF do turno atual; turnos anteriores mantêm o texto, mas indicam que o arquivo não foi reenviado. Erros do provedor não retornam chaves, conteúdo de prompts ou cabeçalhos secretos.

As chamadas ao Anthropic e ao Gemini são interrompidas após 35 s e 25 s, respectivamente; os orçamentos de rede do cliente são limitados a 55 s para o Claude e 45 s para o Gemini de reserva, incluindo rodadas de ferramentas e tentativas. O cliente HTTP também tem limite total por chamada. Esses limites reduzem esperas longas, mas uma ação de dispositivo que já tenha sido executada não é repetida automaticamente se a resposta seguinte falhar.

## Estado atual

O código Android e a função foram preparados localmente, mas a função não foi publicada e os segredos não foram configurados no Supabase remoto. Até concluir esses passos, chamadas de IA via gateway não funcionarão em produção.
