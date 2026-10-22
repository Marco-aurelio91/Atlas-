# Política de Privacidade do Atlas — RASCUNHO

> **Rascunho técnico, não revisado por advogado.** Foi escrito a partir do que o código do app
> realmente faz (versão 0.7.45). Antes de publicar: preencha os campos **⚠️ PREENCHER**, confirme
> as retenções dos provedores de IA e faça uma revisão jurídica (LGPD). A Google Play exige que
> este texto fique em uma **URL pública**.

**Última atualização:** ⚠️ PREENCHER (data de publicação)

## 1. Quem somos
O Atlas é um assistente de inteligência artificial para Android. O controlador dos dados pessoais é
**⚠️ PREENCHER (nome completo ou razão social, CPF/CNPJ)**. Contato para qualquer assunto de
privacidade e para exercer seus direitos: **⚠️ PREENCHER (e-mail)**.

## 2. Que dados tratamos e para quê

| Dado | Para quê | Onde fica |
|---|---|---|
| E-mail e credenciais de login (ou conta Google) | Criar e acessar sua conta | Supabase (autenticação) |
| Memórias: fatos, preferências, pessoas, lugares, tarefas, projetos, notas, rotinas e histórico de uso | Fazer o assistente lembrar de você | Supabase, **cifradas no seu aparelho antes de enviar** |
| Mensagens que você envia ao assistente, anexos (imagem, PDF) e o trecho de memória relevante | Gerar a resposta da IA | Enviados a Anthropic (Claude) e/ou Google (Gemini) — ver item 4 |
| Conversas do Atlas Connect | Chat entre pessoas | Cifradas de ponta a ponta; o servidor só guarda conteúdo cifrado |
| Localização aproximada | Clima e cidade atual | Só enquanto o recurso é usado; coordenadas aproximadas vão ao Open-Meteo e a um serviço de geocodificação (BigDataCloud) |
| Localização em segundo plano | Gatilho opcional de automação por lugar (geofence) | Processada no aparelho; só ativa se você criar essa automação e conceder a permissão |
| Calendário (leitura) | Resumo do seu dia | Lido no aparelho; não é enviado a terceiros, exceto se você pedir à IA que o use na resposta |
| Microfone | Entrada de voz | Reconhecimento de voz do sistema Android |
| Câmera | Ler QR de pareamento | Processada no aparelho |
| Contagem de uso da IA | Aplicar limites por usuário | Supabase |

O Atlas **não usa** SDKs de publicidade, análise de comportamento ou relatório de falhas de terceiros.

## 3. Criptografia e segurança
- As memórias são cifradas no aparelho com uma chave que fica protegida pelo Android Keystore. O
  servidor armazena apenas o conteúdo cifrado. O código de recuperação permite restaurar a chave em
  outro aparelho; **se você perder o aparelho e o código, não conseguimos recuperar suas memórias.**
- As conversas do Atlas Connect usam criptografia de ponta a ponta.
- As chaves dos provedores de IA ficam no servidor, nunca no aplicativo.
- A segurança do protocolo do Atlas Connect ainda não passou por auditoria independente. ⚠️ Revisar
  esta frase quando houver auditoria.

## 4. Compartilhamento com terceiros
Para responder, o Atlas precisa enviar seu texto (e anexos) **sem cifra de ponta a ponta** aos
provedores de IA, pois eles precisam lê-lo para responder:
- **Anthropic** (modelo Claude) e **Google** (modelo Gemini): recebem a mensagem, anexos e o trecho
  de memória escolhido para aquela resposta. ⚠️ PREENCHER: política de retenção e treinamento de cada
  provedor para o tipo de acesso (API) em uso.
- **Supabase**: hospeda a conta, o banco de dados e o gateway. ⚠️ PREENCHER: região do projeto.
- **Open-Meteo e BigDataCloud**: recebem coordenadas aproximadas para clima e cidade.
- **Google**: login com conta Google, quando você escolhe essa opção.

Não vendemos dados pessoais.

## 5. Por quanto tempo guardamos
Mantemos seus dados enquanto sua conta existir. Ao **excluir a conta** (Configurações → Conta →
Excluir minha conta), apagamos de forma definitiva os dados guardados no servidor. ⚠️ PREENCHER:
prazo e política de retenção de backups do Supabase e dos provedores de IA.

## 6. Seus direitos (LGPD, art. 18)
Você pode, a qualquer momento: confirmar a existência de tratamento, acessar, corrigir, pedir
portabilidade, pedir a eliminação dos dados e revogar consentimentos. A exclusão da conta pode ser
feita dentro do app. Para os demais pedidos, escreva para **⚠️ PREENCHER (e-mail)**.

## 7. Crianças e adolescentes
O Atlas não é destinado a menores de 13 anos. ⚠️ PREENCHER: confirmar a faixa etária adotada.

## 8. Mudanças nesta política
Avisaremos no app quando houver mudança relevante.

---

### Pendências para publicar (checklist)
- [ ] Preencher todos os campos ⚠️.
- [ ] Revisão jurídica.
- [ ] Publicar em URL pública e informar essa URL na Play Console e no app.
- [ ] Manter a seção "Segurança de dados" da Play Console coerente com este texto.
- [ ] **Exportação de dados:** o app ainda não tem botão de exportar. Até existir, o pedido de
      acesso/portabilidade é atendido por e-mail — manter essa frase no item 6.
