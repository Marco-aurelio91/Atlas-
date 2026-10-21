# Ativar login com Google

O fluxo preparado obtém um Google ID token pelo Android Credential Manager e o troca por uma sessão do Supabase. O botão está oculto por enquanto e o código está marcado como recurso futuro, até as configurações no Google Auth Platform e no Supabase estarem concluídas.

## Google Auth Platform

1. Crie um OAuth Client ID do tipo **Web application**.
2. Cadastre como redirect URI o callback indicado em **Supabase Dashboard → Authentication → Sign In / Providers → Google**. Para o projeto atual, o formato é `https://<project-ref>.supabase.co/auth/v1/callback`.
3. Configure os escopos `openid`, `email` e `profile`. Se o app estiver em modo de teste, inclua a conta Google usada nos test users.
4. Guarde o Client ID e o Client Secret.

## Supabase

Em **Authentication → Sign In / Providers → Google**, habilite Google e informe o Client ID e o Client Secret criados acima. O Secret fica somente no Supabase; nunca o coloque no APK nem em `supabase.properties`.

## Build Android

Adicione o Client ID público do tipo Web ao arquivo local ignorado pelo Git, `supabase.properties`:

```properties
GOOGLE_WEB_CLIENT_ID=<client-id>.apps.googleusercontent.com
```

Esse ID é usado como audiência do token Google. Depois, gere e instale uma nova release. O código detecta a ausência do ID e mostra um erro na tela em vez de encerrar o app.

O deep link `atlas://auth-callback` continua atendendo à confirmação de e-mail; o callback OAuth do Google é o endpoint HTTPS do Supabase acima.
