# Relay do Fotos na TV

Worker da Cloudflare que repassa as fotos dos convidados para o aparelho
ligado na TV.

**Isto não é um acervo de fotos.** O convidado sobe pela internet, o Fire TV
puxa em poucos segundos e manda apagar. O álbum continua morando na TV.
Salas sem a TV aparecer por 48h são removidas pela faxina horária.

## Publicar

```bash
cd relay
npm install
npx wrangler login                                  # abre o navegador
npx wrangler r2 bucket create fotos-na-tv-relay
npx wrangler deploy
```

No fim o Wrangler imprime o endereço, algo como
`https://fotos-na-tv.SEU-SUBDOMINIO.workers.dev`.

Cole esse endereço no `/admin` do app, em **Modo nuvem**, e clique em
**Ligar modo nuvem**. A TV passa a mostrar o QR da internet.

## Custo

O R2 não cobra tráfego de saída e aqui não há acervo acumulando — o espaço
usado é só o das fotos que ainda não foram buscadas. Uma festa de 300 fotos
movimenta algumas centenas de MB, dentro do nível gratuito com folga.

## Rotas

| Rota | Quem chama | O que faz |
|---|---|---|
| `GET /f/:codigo` | convidado | página de enviar foto |
| `POST /api/rooms` | TV | cria a sala, devolve código e segredo |
| `POST /api/rooms/:codigo/photos` | convidado | envia a foto |
| `GET /api/rooms/:codigo/pending` | TV | o que chegou (exige segredo) |
| `GET /api/rooms/:codigo/photos/:id` | TV | baixa (exige segredo) |
| `DELETE /api/rooms/:codigo/photos/:id` | TV | apaga depois de guardar |
| `POST /api/rooms/:codigo/heartbeat` | TV | segura a sala viva |

## Limites

Como o envio é aberto (igual ao da rede local), tudo tem teto: 12 MB por
foto, 300 fotos aguardando por sala, 5 salas novas por IP por hora e sala
expirando em 48h sem a TV aparecer.
