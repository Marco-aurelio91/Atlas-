# Declarações da Play Console — rascunhos de justificativa

Textos prontos para copiar nos formulários de **Conteúdo do app** da Play Console. Cada permissão
sensível exige uma justificativa de uso central e, em alguns casos, um vídeo. Baseado no código da
versão 0.7.45. Conferir as regras vigentes da Play Console na hora de enviar: elas mudam.

## Permissões removidas nesta versão
- `QUERY_ALL_PACKAGES` → substituída por `<queries>` (apps com ícone de launcher).
- `WRITE_CALENDAR` → o evento abre na Agenda pré-preenchido e o usuário toca em Salvar.

## Localização em segundo plano (`ACCESS_BACKGROUND_LOCATION`)
**Decisão atual:** manter. É a permissão de maior risco de rejeição.

**Recurso que depende dela:** automação do usuário por lugar ("quando eu chegar em X / sair de X,
faça Y"), via geofence. É opcional: só é pedida quando o usuário cria uma automação de localização.

**Justificativa sugerida (campo de texto):**
> O Atlas permite ao usuário criar automações opcionais disparadas por lugar (por exemplo, ao
> chegar em casa, ajustar o volume). Para disparar enquanto o app está fechado, o recurso precisa
> monitorar a entrada e a saída da área escolhida em segundo plano, por meio de geofences do
> Google Play Services. A localização contínua não é coletada nem enviada a servidores: o sistema
> avisa o app apenas quando o usuário cruza o perímetro definido por ele. A permissão é solicitada
> somente quando o usuário cria uma automação por localização, com explicação na tela, e o app
> continua funcionando se ela for negada.

**Vídeo exigido (roteiro de 30–60 s):**
1. Abrir Atlas → Automações → criar automação por localização.
2. Mostrar o texto de explicação do app antes do pedido de permissão.
3. Conceder "Permitir o tempo todo" na tela do sistema.
4. Mostrar a automação salva e o disparo ao cruzar a área (ou o aviso resultante).
5. Mostrar como desativar/excluir a automação.

**Risco:** se a Play Console rejeitar, a saída é remover o gatilho de geofence e esta permissão do
manifest; os demais gatilhos (horário, carregador, fones, bateria baixa) não dependem dela.

## Serviços em primeiro plano
- **`mediaProjection`** (captura de tela): usado só quando o usuário pede ao assistente para
  analisar a tela. Vídeo: acionar a captura, mostrar a notificação do sistema e o resultado.
- **`specialUse`** (overlay flutuante do assistente): subtipo declarado no manifest —
  "User-enabled floating Atlas assistant overlay". É ativado manualmente nas configurações.
  Justificativa: o usuário liga o balão de atalho para acessar o assistente sobre outros apps.

## Sobrepor a outros apps (`SYSTEM_ALERT_WINDOW`)
Necessária para o balão flutuante do assistente, opcional e ativado pelo usuário em Configurações.
Sem ela, o resto do app funciona.

## Alterar configurações do sistema (`WRITE_SETTINGS`)
Usada apenas pela ferramenta "ajustar brilho" a pedido do usuário; o app checa `canWrite` e cai em
mensagem de falha se a permissão especial não estiver concedida. Candidata a corte se a Play
Console questionar (função de baixo valor frente ao risco).

## Permissões de runtime pedidas sob demanda
Calendário (leitura), localização em primeiro plano, microfone, câmera e notificações são pedidas
no momento em que a função é usada, não na abertura do app.

## Segurança de dados (formulário)
Coerente com `docs/PRIVACIDADE_RASCUNHO.md`:
- **Coletados e enviados:** e-mail (conta), mensagens/anexos para IA (processados por provedores),
  localização aproximada (clima), conteúdo cifrado de memória e de chat.
- **Criptografia em trânsito:** sim. **Exclusão de conta:** disponível no app.
- **Compartilhado com terceiros:** provedores de IA, hospedagem (Supabase), clima/geocodificação.
- **Sem anúncios, sem venda de dados.**

## Conteúdo gerado por IA
A política da Google Play para apps de IA generativa exige um jeito de o usuário **denunciar ou
sinalizar** respostas ofensivas dentro do app. **Ainda não implementado** — pendência antes da
publicação.
