# BaixaVídeo

App Android (Java puro, sem dependências) que recebe um link do X/Twitter ou do YouTube e baixa o vídeo (.mp4) ou só o áudio (.m4a) para `Download/BaixaVideo`.

- Aceita o link colado ou via **Compartilhar** nos apps do X e do YouTube.
- X: usa o endpoint público de embed (`cdn.syndication.twimg.com`) e lista as qualidades MP4.
- YouTube: consulta a API InnerTube com clientes ANDROID_VR e IOS; junta vídeo H.264 + áudio AAC com o `MediaMuxer` nativo, sem recodificar.
- Áudio: extrai a trilha AAC para .m4a, também sem recodificar.
- Android 10 ou superior; não pede permissão de armazenamento.

## Gerar o APK

**Automático:** cada push na `main` dispara o workflow *Gerar APK e Release*, que publica o APK em [Releases](../../releases/latest). Basta abrir essa página no celular e baixar o `.apk`. Para recompilar sem mudar nada, use *Actions → Gerar APK e Release → Run workflow*.

**Assinatura fixa (recomendado):** sem configuração, cada build sai com uma chave de debug diferente, e aí é preciso desinstalar a versão anterior para atualizar. Para manter a mesma chave entre versões, crie estes Secrets em *Settings → Secrets and variables → Actions*:

| Secret | Conteúdo |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | o arquivo `.keystore` convertido para base64 |
| `SIGNING_STORE_PASSWORD` | senha do keystore |
| `SIGNING_KEY_ALIAS` | alias da chave |
| `SIGNING_KEY_PASSWORD` | senha da chave |

Para gerar a chave (no PC, com JDK): `keytool -genkeypair -keystore baixavideo.keystore -alias baixavideo -keyalg RSA -keysize 2048 -validity 36500`, e depois `base64 -w0 baixavideo.keystore` (Linux) ou `[Convert]::ToBase64String([IO.File]::ReadAllBytes("baixavideo.keystore"))` (PowerShell). Nunca faça commit do keystore.

**No PC:** abra a pasta no Android Studio e use *Build → Build APK(s)*, ou rode `gradle assembleDebug` com o Android SDK instalado.

## Se o YouTube parar de funcionar

O YouTube muda a API com frequência. Atualize as versões em `YT_CLIENTS` no arquivo `Extractors.java`, usando como referência os valores atuais do yt-dlp.
