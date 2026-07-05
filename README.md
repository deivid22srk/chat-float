# ChatFloat

Aplicativo Android de chat simples e leve, projetado para enviar mensagens enquanto joga, sem atrapalhar a jogatina. Possui dois modos de uso:

1. **Modo App Normal** — abre como um chat convencional dentro do app
2. **Modo Janela Flutuante** — exibe um overlay flutuante sobre outros apps, arrastável, com mensagens em tempo real

## Stack

- Kotlin + Jetpack Compose (UI do app principal)
- Views nativas (UI do overlay flutuante — mais leve e estável)
- Supabase (Auth + Postgres + Realtime)
- Coroutines + Flow
- Material 3

## Funcionalidades

- Cadastro / login por e-mail e senha
- Sala de chat geral pública em tempo real
- Janela flutuante arrastável com input e lista de mensagens
- Botão de recolher / expandir o overlay
- Notificação de foreground service para manter o overlay ativo

## Build

O APK de release é compilado via GitHub Actions (`.github/workflows/build.yml`).

Segredos necessários no repositório:

| Secret | Descrição |
|--------|-----------|
| `KEYSTORE_BASE64` | Base64 do arquivo `chat-release.keystore` |
| `KEYSTORE_PASSWORD` | Senha do keystore |
| `KEY_ALIAS` | Alias da chave |
| `KEY_PASSWORD` | Senha da chave |

Após executar o workflow, o APK assinado é publicado como artefato para download.

## Estrutura

```
app/src/main/java/com/deivid22srk/chatfloat/
├── ChatFloatApplication.kt       # init do Supabase + canal de notificação
├── MainActivity.kt               # ponto de entrada Compose
├── data/                         # models, repository, cliente Supabase
├── service/
│   └── FloatingChatService.kt    # foreground service do overlay
├── ui/
│   ├── AuthViewModel.kt
│   ├── ChatViewModel.kt
│   ├── theme/                    # cores, tipografia, tema
│   ├── components/               # MessageBubble
│   └── screens/                  # LoginScreen, ChatScreen
```

## Permissões

- `INTERNET` — acesso ao Supabase
- `SYSTEM_ALERT_WINDOW` — desenhar overlay sobre outros apps
- `FOREGROUND_SERVICE` — manter o overlay ativo em background
- `POST_NOTIFICATIONS` — notificação do foreground service (Android 13+)
