# ChatFloat

Aplicativo Android de chat simples e leve, projetado para enviar mensagens enquanto joga, sem atrapalhar a jogatina. Possui dois modos de uso:

1. **Modo App Normal** — abre como um chat convencional dentro do app
2. **Modo Janela Flutuante** — exibe um overlay flutuante sobre outros apps, arrastável, com mensagens em tempo real

## Stack

- Kotlin + Jetpack Compose (UI do app principal)
- Views nativas (UI do overlay flutuante — mais leve e estável)
- Telegram Bot API (backend de mensagens)
- Ktor Client (HTTP) + kotlinx.serialization (JSON)
- Coroutines + Flow
- Material 3

## Como funciona

O app usa um bot do Telegram (`@ChatFloat5_bot`) como backend. Todas as mensagens enviadas pelo app são postadas no grupo do Telegram através do bot. Mensagens recebidas vêm do grupo via long polling em `getUpdates`.

- **Bot:** `@ChatFloat5_bot` (token configurado via `BuildConfig.TELEGRAM_BOT_TOKEN`)
- **Grupo:** `ChatFloat Grupo` (ID configurado via `BuildConfig.TELEGRAM_GROUP_ID`)
- **Nome de usuário:** configurado localmente no app (armazenado em SharedPreferences)

### ⚠️ Importante: Desativar Privacy Mode

Por padrão, bots no Telegram só veem mensagens que:
- São comandos (começam com `/`)
- Mencionam o bot
- São respostas a mensagens do bot

Para o bot ver **todas** as mensagens do grupo (necessário para o chat funcionar como esperado), desative o Privacy Mode:

1. Abra `@BotFather` no Telegram
2. Envio `/setprivacy`
3. Selecione o bot `@ChatFloat5_bot`
4. Escolha **Disable**
5. Remova o bot do grupo e adicione novamente (a mudança só faz efeito após re-adicionar)

## Funcionalidades

- Configuração inicial de nome de usuário (sem necessidade de login/senha)
- Mensagens enviadas aparecem no grupo do Telegram como o bot
- Mensagens de outros membros do grupo aparecem no app em tempo real
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
├── ChatFloatApplication.kt       # canal de notificação
├── MainActivity.kt               # ponto de entrada Compose
├── data/
│   ├── Models.kt                 # modelos de dados (ChatMessage, TelegramMessage etc.)
│   └── TelegramBotRepository.kt  # cliente do Telegram Bot API
├── service/
│   └── FloatingChatService.kt    # foreground service do overlay
├── ui/
│   ├── ChatViewModel.kt          # estado do chat + polling
│   ├── theme/                    # cores, tipografia, tema
│   ├── components/               # MessageBubble
│   └── screens/                  # UsernameScreen, ChatScreen
```

## Permissões

- `INTERNET` — acesso à API do Telegram
- `SYSTEM_ALERT_WINDOW` — desenhar overlay sobre outros apps
- `FOREGROUND_SERVICE` — manter o overlay ativo em background
- `POST_NOTIFICATIONS` — notificação do foreground service (Android 13+)
