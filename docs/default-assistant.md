# ATLAS como assistente padrão

O chat registra ACTION_ASSIST e ACTION_VOICE_ASSIST com CATEGORY_DEFAULT. O Android pode oferecer o ATLAS como candidato ao papel de assistente sem um serviço de hotword.

Configurações > Atlas > Assistente padrão do telefone solicita ROLE_ASSISTANT; a escolha pertence ao usuário. O estado é atualizado ao retornar das configurações do sistema. Há fallback para as configurações de voz e de aplicativos padrão.

Ao ser chamado como assistente, o chat aguarda login e estado RESUMED, solicita RECORD_AUDIO se necessário e usa o reconhecimento de voz existente. Não inicia outra sessão enquanto já está ouvindo ou enviando uma mensagem. O pedido é consumido e salvo na recriação da atividade. Abrir pelo ícone não solicita escuta automática. Negar microfone mantém o chat disponível por texto.

Não inclui ativação por palavra-chave nem desbloqueio do telefone. O botão/gesto disponível depende do fabricante.

Referência: https://developer.android.com/reference/kotlin/androidx/core/role/RoleManagerCompat#ROLE_ASSISTANT()

Verificação manual: selecionar ATLAS no seletor do sistema; invocar pelo gesto com chat fechado e aberto; permitir/negar microfone; abrir pelo ícone; girar a tela após uma chamada; invocar durante envio; verificar login pendente.
