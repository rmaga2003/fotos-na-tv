# Fotos na TV

**Instalar no Fire TV:** abra o app **Downloader** e digite o código **`6387552`**
**Página de download:** [rmaga2003.github.io/fotos-na-tv](https://rmaga2003.github.io/fotos-na-tv/)

Álbum de fotos ao vivo para festas. Os convidados escaneiam um QR code, mandam
a foto pelo celular e ela aparece na TV na hora.

É a evolução do `fotosapp1` (Spring Boot), com a diferença que importa: **o app
roda dentro do próprio Fire TV Stick / Android TV**. Não precisa mais de
notebook ligado na TV por HDMI, nem de internet, nem de servidor na nuvem.

---

## O que mudou em relação ao fotosapp1

| | fotosapp1 | Fotos na TV |
|---|---|---|
| Onde roda | Notebook com Spring Boot, TV só como monitor HDMI | Dentro do Fire TV Stick / Android TV |
| Internet | Precisava (QR codes vinham de `api.qrserver.com`) | Nenhuma — QR gerados no próprio aparelho |
| IP do QR | Fixo no HTML (`192.168.0.220`) | Detectado sozinho a cada partida |
| Configuração | Editar HTML e recompilar | Painel web em `/admin`, pelo celular |
| Controle remoto | Não existia | Menu completo no controle da TV |
| Fotos | Guardadas no tamanho original | Giradas pelo EXIF e reduzidas (TV fluida, menos espaço) |
| Distribuição | Nenhuma | APK em GitHub Releases |

---

## Dois caminhos para a foto chegar na TV

O app aceita foto por dois canais ao mesmo tempo, e os dois caem no mesmo
álbum:

| | Rede local (padrão) | Modo nuvem (opcional) |
|---|---|---|
| O convidado precisa | estar no Wi-Fi da casa | só de internet no próprio celular |
| Internet na festa | dispensa | obrigatória |
| Android 16/17 | exige liberar a rede local | funciona direto |
| As fotos | nunca saem da casa | passam pelo relay e são apagadas de lá |
| Custo | zero | praticamente zero (veja `relay/`) |

Ligando o modo nuvem no `/admin`, a TV passa a mostrar **um QR só**, que
funciona para qualquer convidado — inclusive quem nem entrou no Wi-Fi.
Se a internet cair no meio da festa, a TV volta sozinha a mostrar o
endereço local.

O relay é um Worker da Cloudflare de uma página só, e **não guarda acervo**:
ele segura a foto até a TV buscar e apagar. Veja [`relay/`](relay/) para
publicar o seu.

## Como funciona

```
┌──────────────────────── Wi-Fi da casa ────────────────────────┐
│                                                               │
│   Celular do convidado                  Fire TV Stick         │
│   ┌─────────────────┐                   ┌──────────────────┐  │
│   │ escaneia o QR   │ ── http://ip:8080 │ servidor embutido│  │
│   │ tira / escolhe  │ ─── POST /upload ►│ (NanoHTTPD)      │  │
│   │ a foto          │                   │   ↓              │  │
│   └─────────────────┘                   │ reduz + gira     │  │
│                                         │   ↓              │  │
│                                         │ WebView em       │  │
│                                         │ tela cheia ──────┼──► TV
│                                         └──────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

O app sobe um servidor HTTP no próprio aparelho e abre uma WebView em tela
cheia apontando para ele. As mesmas páginas servem a TV e os celulares:

| Endereço | Para quem |
|---|---|
| `/` e `/upload` | convidado: manda foto (câmera ou galeria, várias de uma vez) |
| `/album` | convidado: vê todas as fotos e salva as que quiser |
| `/admin` | anfitrião: configurações, moderação, baixar tudo em `.zip` |
| `/tv` | a própria TV (slideshow) |

---

## Instalar no Fire TV Stick

### 1. Liberar a instalação de apps

No Fire TV: **Configurações → Meu Fire TV → Opções do desenvolvedor** →
ligue **Instalar apps desconhecidos** para o app **Downloader**.

> Se **Opções do desenvolvedor** não aparecer, vá em
> **Configurações → Meu Fire TV → Sobre** e clique 7 vezes em
> **Fire TV Stick**.

### 2. Baixar o APK

Instale o app **Downloader** (está na loja da Amazon), abra e digite no campo
de endereço apenas o código:

```
6387552
```

O download começa na hora e o Downloader já oferece instalar.

> O código é um atalho do AFTVnews (`aftv.news/6387552`) apontando para a
> release `latest`. Ou seja: **ele não muda a cada versão** — digitar o mesmo
> número sempre traz o APK mais recente. Se preferir digitar um endereço, use
> `rmaga2003.github.io/fotos-na-tv` e clique em *Baixar o APK*.

### 3. Abrir

O app aparece na linha **Seus aplicativos e canais** da tela inicial do
Fire TV. Segure o botão do controle sobre ele para mover para o começo.

> **Via adb** (se preferir): ative a **Depuração ADB** nas opções do
> desenvolvedor e rode `adb connect IP_DA_TV:5555` e
> `adb install -r fotos-na-tv.apk`.

---

## Usando na festa

1. Abra o app no Fire TV. A TV mostra o QR code e o endereço.
2. No seu celular, acesse esse endereço e entre em `/admin` para
   configurar o título da festa e o Wi-Fi (opcional, mas vale muito).
3. Pronto: os convidados escaneiam e mandam foto.

### Wi-Fi no QR code

Preenchendo rede e senha no painel, a TV passa a mostrar **dois** QR codes:
o primeiro conecta o celular no Wi-Fi da casa sem digitar senha, o segundo
abre a página de enviar foto. Isso resolve o maior atrito da festa.

### Controle remoto

| Botão | O que faz |
|---|---|
| ◄ ► | foto anterior / próxima |
| OK | pausa e despausa |
| ▲ | QR code grande em tela cheia ("pessoal, escaneia aí!") |
| ▼ ou Menu | abre o menu do telão |
| Voltar | fecha o menu (ou sai do app) |

No menu dá para mudar o tempo por foto, ordem aleatória, mostrar/ocultar QR e
miniaturas, apagar a foto que está na tela e fechar o app.

### Contagem regressiva

No painel você marca data e hora (Ano Novo, meia-noite do aniversário...).
A TV faz a contagem dos 10 segundos finais e solta fogos. Diferente do
fotosapp1, a data não é mais fixa no código.

---

## Build

### Pelo GitHub Actions (recomendado)

Todo push na `main` gera o APK como artefato do build. Para publicar uma
versão que os amigos baixam:

```bash
git tag v1.0.0
git push origin v1.0.0
```

O workflow cria uma *Release* com o `fotos-na-tv.apk` anexado. Como o código
`6387552` aponta para `releases/latest`, ele passa a servir a versão nova
automaticamente — não precisa gerar atalho novo nem avisar ninguém.

#### Chave de assinatura

Sem configurar nada, o APK sai assinado com a chave de debug — instala
normalmente, mas quem já tem o app precisa **desinstalar antes de atualizar**.
Para evitar isso, gere uma chave uma única vez:

```bash
keytool -genkeypair -v -keystore release.keystore -alias fotostv \
  -keyalg RSA -keysize 2048 -validity 10000

base64 -w0 release.keystore   # no Windows: certutil -encode
```

E cadastre nos *secrets* do repositório (Settings → Secrets → Actions):

| Secret | Conteúdo |
|---|---|
| `KEYSTORE_BASE64` | o keystore em base64 |
| `KEYSTORE_PASSWORD` | senha do keystore |
| `KEY_ALIAS` | `fotostv` |
| `KEY_PASSWORD` | senha da chave |

> Guarde o `release.keystore` em lugar seguro. Perdeu a chave, perdeu a
> capacidade de publicar atualizações por cima da versão instalada.

### Na sua máquina

Precisa de **JDK 17** (o AGP 8.7 não aceita JDK 25) e do **Android SDK**.
Com o Android Studio instalado, é só:

```bash
./gradlew assembleRelease
# APK em app/build/outputs/apk/release/
```

Para instalar direto no Fire TV com adb ligado:

```bash
./gradlew installRelease
```

---

## Estrutura

```
app/src/main/
├── java/com/exemplo/fotostv/
│   ├── MainActivity.kt    WebView em tela cheia + teclas do controle
│   ├── PhotoServer.kt     servidor HTTP e API (NanoHTTPD)
│   ├── PhotoStore.kt      álbum em disco, sem banco de dados
│   ├── ImageUtils.kt      rotação por EXIF e redução das fotos
│   ├── QrUtils.kt         QR codes offline (ZXing)
│   ├── NetUtils.kt        descoberta do IP da rede local
│   └── Settings.kt        configurações da festa
└── assets/web/
    ├── tv.html            slideshow do telão
    ├── upload.html        envio pelo celular
    ├── album.html         galeria dos convidados
    ├── admin.html         painel do anfitrião
    └── base.css
```

### API

| Rota | Método | Descrição |
|---|---|---|
| `/api/state` | GET | revisão, contagem, hora do servidor (polling barato) |
| `/api/info` | GET | IP, URLs, versão, configurações |
| `/api/photos?limit=` | GET | lista de fotos |
| `/api/upload` | POST | multipart `file`, `author`, `caption` |
| `/api/qr?d=&s=` | GET | PNG do QR code |
| `/api/settings` | GET/POST | configurações |
| `/api/photos/delete` | POST | apaga uma foto |
| `/api/photos/clear` | POST | apaga tudo |
| `/api/download-all` | GET | álbum inteiro em `.zip` |

---

## Se algo não funcionar

**A TV mostra "Sem rede".** O Fire TV não está conectado. Conecte no Wi-Fi da
casa — tem que ser a **mesma rede** dos celulares.

**O celular Android mostra `ERR_TOO_MANY_RETRIES`.** Esse é o caso mais comum
hoje e não é problema de rede. A partir do **Android 16/17** o sistema bloqueia
apps que falam com aparelhos da rede local: o Chrome precisa da permissão
`ACCESS_LOCAL_NETWORK` e, sem ela, devolve esse erro.

> **Libere em:** Ajustes → Apps → Chrome → Permissões → **Dispositivos por perto**
> (em alguns aparelhos, **Rede local**) → Permitir.
>
> Alternativa sem mexer em permissão: abrir o endereço no **Firefox**.

**O celular não abre o endereço.** Alguns roteadores têm *isolamento de
clientes* (AP isolation), que impede um aparelho de falar com outro na mesma
rede. Desligue essa opção no roteador, ou use uma rede de convidados sem
isolamento.

**Rede de 2,4 GHz e 5 GHz separadas.** Se o roteador publica as duas como
redes diferentes, coloque o Fire TV e os celulares na mesma.

**O app fecha sozinho com muitas fotos.** Baixe "Qualidade guardada" para
1280 px e o "Máximo de fotos" no painel. O Fire TV Stick tem pouca memória.

**Não consigo atualizar o app.** Se a versão anterior foi assinada com outra
chave, desinstale antes de instalar a nova.

---

## Privacidade

Tudo fica no aparelho ligado na TV: as fotos ficam no armazenamento interno do
app, não sobem para nenhum servidor e somem quando o app é desinstalado.
Baixe o `.zip` pelo painel antes de desinstalar se quiser guardar a festa.
