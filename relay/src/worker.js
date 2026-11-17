/**
 * Fotos na TV — relay.
 *
 * Este servidor NAO e um acervo de fotos. Ele e uma caixa de passagem:
 * o convidado sobe a foto pela internet, o aparelho ligado na TV puxa em
 * poucos segundos e manda apagar. O album continua morando na TV.
 *
 * Por isso o custo é quase nulo e a privacidade se mantem: nada fica aqui
 * alem do tempo necessario para a TV buscar.
 */

import { guestPage } from "./guestPage.js";

// ---------------------------------------------------------------------------
// Limites — este endpoint de upload é público, então tudo tem teto.
// ---------------------------------------------------------------------------
const MAX_FOTO_BYTES = 12 * 1024 * 1024; // 12 MB por foto
const MAX_PENDENTES = 300;               // fotos aguardando a TV, por sala
const SALA_TTL_MS = 48 * 60 * 60 * 1000; // sala morre após 48h sem a TV aparecer
const MAX_SALAS_POR_IP_HORA = 5;

// Alfabeto sem 0/O/1/I: o código vai ser lido na tela da TV.
const ALFABETO = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

export default {
  async fetch(request, env, ctx) {
    try {
      return await rotear(request, env, ctx);
    } catch (erro) {
      console.error("falha:", erro && erro.stack);
      return json({ error: "erro interno" }, 500);
    }
  },

  /** Faxina periódica: salas que a TV abandonou não ficam ocupando espaço. */
  async scheduled(event, env, ctx) {
    ctx.waitUntil(limparSalasVelhas(env));
  },
};

async function rotear(request, env, ctx) {
  const url = new URL(request.url);
  const caminho = url.pathname.replace(/\/+$/, "") || "/";
  const metodo = request.method;

  // ---------- páginas ----------
  if (caminho === "/") return paginaInicial();

  const mf = caminho.match(/^\/f\/([A-Z0-9]{4,12})$/i);
  if (mf) {
    const codigo = mf[1].toUpperCase();
    const sala = await lerSala(env, codigo);
    if (!sala) return paginaSalaInexistente(codigo);
    return html(guestPage(codigo, sala.titulo || "Álbum da Festa"));
  }

  // ---------- API ----------
  if (caminho === "/api/rooms" && metodo === "POST") {
    return criarSala(request, env);
  }

  const mr = caminho.match(/^\/api\/rooms\/([A-Z0-9]{4,12})(\/.*)?$/i);
  if (mr) {
    const codigo = mr[1].toUpperCase();
    const resto = mr[2] || "";

    if (resto === "/photos" && metodo === "POST") {
      return receberFoto(request, env, codigo);
    }
    if (resto === "/pending" && metodo === "GET") {
      return listarPendentes(request, env, codigo);
    }
    if (resto === "/heartbeat" && metodo === "POST") {
      return baterPonto(request, env, codigo);
    }

    const mp = resto.match(/^\/photos\/([A-Za-z0-9_.-]+)$/);
    if (mp && metodo === "GET") return baixarFoto(request, env, codigo, mp[1]);
    if (mp && metodo === "DELETE") return apagarFoto(request, env, codigo, mp[1]);
  }

  return json({ error: "nao encontrado" }, 404);
}

// ---------------------------------------------------------------------------
// Salas
// ---------------------------------------------------------------------------

/** A TV cria a sala e guarda o segredo; só ela consegue puxar e apagar fotos. */
async function criarSala(request, env) {
  const ip = request.headers.get("CF-Connecting-IP") || "0.0.0.0";
  if (!(await permitirNovaSala(env, ip))) {
    return json({ error: "muitas salas criadas deste endereço; tente mais tarde" }, 429);
  }

  let corpo = {};
  try {
    corpo = await request.json();
  } catch (e) {
    // sem corpo tambem serve
  }

  const codigo = await codigoLivre(env);
  const segredo = aleatorio(32);
  const sala = {
    codigo,
    segredo,
    titulo: String(corpo.titulo || "Álbum da Festa").slice(0, 80),
    criadaEm: Date.now(),
    tvVistaEm: Date.now(),
    recebidas: 0,
  };

  await gravarSala(env, sala);

  return json({
    code: codigo,
    secret: segredo,
    guestUrl: new URL("/f/" + codigo, request.url).toString(),
  });
}

async function codigoLivre(env) {
  for (let i = 0; i < 8; i++) {
    const tentativa = aleatorio(6, ALFABETO);
    if (!(await lerSala(env, tentativa))) return tentativa;
  }
  // praticamente impossivel chegar aqui; alonga para desempatar
  return aleatorio(9, ALFABETO);
}

const chaveSala = (codigo) => "rooms/" + codigo + ".json";
const prefixoFotos = (codigo) => "rooms/" + codigo + "/photos/";

async function lerSala(env, codigo) {
  const obj = await env.FOTOS.get(chaveSala(codigo));
  if (!obj) return null;
  try {
    const sala = await obj.json();
    if (Date.now() - (sala.tvVistaEm || 0) > SALA_TTL_MS) return null;
    return sala;
  } catch (e) {
    return null;
  }
}

async function gravarSala(env, sala) {
  await env.FOTOS.put(chaveSala(sala.codigo), JSON.stringify(sala), {
    httpMetadata: { contentType: "application/json" },
  });
}

function autorizado(request, sala) {
  const url = new URL(request.url);
  const enviado = request.headers.get("X-Room-Secret") || url.searchParams.get("secret");
  return Boolean(enviado) && enviado === sala.segredo;
}

/** A TV avisa que está viva; é isso que segura a sala contra o TTL. */
async function baterPonto(request, env, codigo) {
  const sala = await lerSala(env, codigo);
  if (!sala) return json({ error: "sala expirada" }, 404);
  if (!autorizado(request, sala)) return json({ error: "segredo invalido" }, 403);

  sala.tvVistaEm = Date.now();
  await gravarSala(env, sala);
  return json({ ok: true, code: codigo, recebidas: sala.recebidas || 0 });
}

// ---------------------------------------------------------------------------
// Fotos
// ---------------------------------------------------------------------------

/** Vem do convidado, sem autenticação — como o upload na rede local. */
async function receberFoto(request, env, codigo) {
  const sala = await lerSala(env, codigo);
  if (!sala) return json({ error: "Esta festa já encerrou." }, 404);

  const declarado = Number(request.headers.get("content-length") || 0);
  if (declarado > MAX_FOTO_BYTES) {
    return json({ error: "Foto muito grande (limite de 12 MB)." }, 413);
  }

  const pendentes = await env.FOTOS.list({ prefix: prefixoFotos(codigo), limit: MAX_PENDENTES + 1 });
  if (pendentes.objects.length > MAX_PENDENTES) {
    return json({ error: "A TV está com a fila cheia. Tente de novo em instantes." }, 429);
  }

  const form = await request.formData();
  const arquivo = form.get("file");
  if (!arquivo || typeof arquivo === "string") {
    return json({ error: "Nenhuma foto recebida." }, 400);
  }
  if (arquivo.size === 0) return json({ error: "Arquivo vazio." }, 400);
  if (arquivo.size > MAX_FOTO_BYTES) {
    return json({ error: "Foto muito grande (limite de 12 MB)." }, 413);
  }

  const autor = String(form.get("author") || "").slice(0, 40);
  const id = Date.now() + "_" + aleatorio(6);

  await env.FOTOS.put(prefixoFotos(codigo) + id + ".jpg", arquivo.stream(), {
    httpMetadata: { contentType: "image/jpeg" },
    customMetadata: { author: autor, ts: String(Date.now()) },
  });

  sala.recebidas = (sala.recebidas || 0) + 1;
  await gravarSala(env, sala);

  return json({ ok: true, id });
}

/** A TV pergunta o que chegou enquanto ela não olhava. */
async function listarPendentes(request, env, codigo) {
  const sala = await lerSala(env, codigo);
  if (!sala) return json({ error: "sala expirada" }, 404);
  if (!autorizado(request, sala)) return json({ error: "segredo invalido" }, 403);

  sala.tvVistaEm = Date.now();
  await gravarSala(env, sala);

  // include é obrigatório: sem ele o R2 devolve a lista sem customMetadata,
  // e autor e horário chegariam vazios.
  const lista = await env.FOTOS.list({
    prefix: prefixoFotos(codigo),
    limit: 100,
    include: ["customMetadata"],
  });
  const fotos = lista.objects.map((obj) => ({
    id: obj.key.slice(prefixoFotos(codigo).length).replace(/\.jpg$/, ""),
    size: obj.size,
    author: (obj.customMetadata && obj.customMetadata.author) || "",
    ts: Number((obj.customMetadata && obj.customMetadata.ts) || 0),
  }));
  fotos.sort((a, b) => a.ts - b.ts);

  return json({ photos: fotos, truncated: lista.truncated === true });
}

async function baixarFoto(request, env, codigo, id) {
  const sala = await lerSala(env, codigo);
  if (!sala) return json({ error: "sala expirada" }, 404);
  if (!autorizado(request, sala)) return json({ error: "segredo invalido" }, 403);

  const obj = await env.FOTOS.get(prefixoFotos(codigo) + id + ".jpg");
  if (!obj) return json({ error: "foto nao encontrada" }, 404);

  const headers = new Headers();
  headers.set("Content-Type", "image/jpeg");
  headers.set("Cache-Control", "no-store");
  headers.set("X-Photo-Author", (obj.customMetadata && obj.customMetadata.author) || "");
  headers.set("X-Photo-Ts", (obj.customMetadata && obj.customMetadata.ts) || "0");
  return new Response(obj.body, { headers });
}

/** Chamado pela TV depois de guardar a foto no álbum local. */
async function apagarFoto(request, env, codigo, id) {
  const sala = await lerSala(env, codigo);
  if (!sala) return json({ error: "sala expirada" }, 404);
  if (!autorizado(request, sala)) return json({ error: "segredo invalido" }, 403);

  await env.FOTOS.delete(prefixoFotos(codigo) + id + ".jpg");
  return json({ ok: true });
}

// ---------------------------------------------------------------------------
// Faxina e limites
// ---------------------------------------------------------------------------

async function limparSalasVelhas(env) {
  const salas = await env.FOTOS.list({ prefix: "rooms/", delimiter: "/", limit: 1000 });
  let removidas = 0;

  for (const obj of salas.objects) {
    if (!obj.key.endsWith(".json")) continue;
    const bruto = await env.FOTOS.get(obj.key);
    if (!bruto) continue;

    let sala;
    try {
      sala = await bruto.json();
    } catch (e) {
      continue;
    }
    if (Date.now() - (sala.tvVistaEm || 0) <= SALA_TTL_MS) continue;

    const fotos = await env.FOTOS.list({ prefix: prefixoFotos(sala.codigo), limit: 1000 });
    for (const f of fotos.objects) await env.FOTOS.delete(f.key);
    await env.FOTOS.delete(obj.key);
    removidas++;
  }

  console.log("faxina: " + removidas + " sala(s) expirada(s) removida(s)");
}

/** Freio simples contra alguém criar salas em massa. */
async function permitirNovaSala(env, ip) {
  const janela = Math.floor(Date.now() / 3600000);
  const chave = "limits/" + janela + "/" + ip.replace(/[^0-9a-fA-F.:]/g, "");

  const atual = await env.FOTOS.get(chave);
  let contador = 0;
  if (atual) {
    try {
      contador = Number(await atual.text()) || 0;
    } catch (e) {
      contador = 0;
    }
  }
  if (contador >= MAX_SALAS_POR_IP_HORA) return false;

  await env.FOTOS.put(chave, String(contador + 1), {
    httpMetadata: { contentType: "text/plain" },
  });
  return true;
}

// ---------------------------------------------------------------------------
// Utilidades
// ---------------------------------------------------------------------------

function aleatorio(tamanho, alfabeto) {
  const letras = alfabeto || "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  const bytes = new Uint8Array(tamanho);
  crypto.getRandomValues(bytes);
  let saida = "";
  for (let i = 0; i < tamanho; i++) saida += letras[bytes[i] % letras.length];
  return saida;
}

function json(corpo, status) {
  return new Response(JSON.stringify(corpo), {
    status: status || 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function html(corpo, status) {
  return new Response(corpo, {
    status: status || 200,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

function paginaInicial() {
  return html(
    `<!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Fotos na TV</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;text-align:center;padding:24px;
background:linear-gradient(180deg,#16102b,#0b0718);color:#f5f2ff;
font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,system-ui,sans-serif}
h1{font-size:28px;margin:0 0 10px}p{color:#a89ec4;max-width:420px;line-height:1.6}
a{color:#ffca28}
</style></head><body><div>
<div style="font-size:52px">📸</div>
<h1>Fotos na TV</h1>
<p>Este endereço serve as festas em andamento. Para enviar uma foto,
escaneie o QR code que está na TV da festa.</p>
<p><a href="https://rmaga2003.github.io/fotos-na-tv/">Conheça o app</a></p>
</div></body></html>`
  );
}

function paginaSalaInexistente(codigo) {
  return html(
    `<!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Festa encerrada</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;text-align:center;padding:24px;
background:linear-gradient(180deg,#16102b,#0b0718);color:#f5f2ff;
font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,system-ui,sans-serif}
h1{font-size:26px;margin:0 0 10px}p{color:#a89ec4;max-width:420px;line-height:1.6}
code{color:#ffca28}
</style></head><body><div>
<div style="font-size:52px">🎉</div>
<h1>Essa festa já encerrou</h1>
<p>O código <code>${codigo}</code> não está mais ativo.
Escaneie de novo o QR que está na TV.</p>
</div></body></html>`,
    404
  );
}
