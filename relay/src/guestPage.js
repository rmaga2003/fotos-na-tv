/**
 * Pagina que o convidado abre pela internet (4G ou qualquer Wi-Fi).
 *
 * E irma da assets/web/upload.html do app, com a mesma reducao de imagem e a
 * mesma correcao de EXIF — a diferenca e que aqui a foto vai para o relay em
 * vez de ir direto para o aparelho da TV.
 */
export function guestPage(codigo, titulo) {
  const tituloSeguro = escapar(titulo);

  return (
    `<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0b0718">
<title>` + tituloSeguro + `</title>
<style>
  :root{--bg:#0b0718;--bg2:#16102b;--card:rgba(255,255,255,.055);--border:rgba(255,255,255,.12);
  --text:#f5f2ff;--muted:#a89ec4;--accent:#ffca28;--accent2:#ff7043;--ok:#7ee2a8;--err:#ff8a8a}
  *{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
  body{min-height:100vh;background:radial-gradient(900px 480px at 50% -10%,#2a1a52 0,transparent 60%),
  linear-gradient(180deg,var(--bg2),var(--bg) 60%);background-attachment:fixed;color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,system-ui,sans-serif;
  font-size:16px;line-height:1.45;padding:0 0 48px}
  .wrap{max-width:560px;margin:0 auto;padding:22px 16px}
  .card{background:var(--card);border:1px solid var(--border);border-radius:18px;padding:20px;
  box-shadow:0 18px 40px rgba(0,0,0,.55)}
  .hero{text-align:center}
  .ring{width:84px;height:84px;border-radius:50%;margin:0 auto 12px;display:grid;place-items:center;
  font-size:36px;background:radial-gradient(circle at 32% 28%,#ffd54f 0,#f4511e 42%,#4a148c 100%);
  box-shadow:0 0 34px rgba(255,170,60,.42)}
  h1{font-size:26px;margin-bottom:4px;line-height:1.2}
  .muted{color:var(--muted);font-size:14px}
  label.field{display:block;margin:16px 0 0;font-size:13px;color:var(--muted)}
  input[type=text]{width:100%;margin-top:6px;padding:12px 14px;border-radius:12px;
  border:1px solid var(--border);background:rgba(0,0,0,.35);color:var(--text);font-size:16px;font-family:inherit}
  input:focus{outline:2px solid rgba(255,202,40,.6);outline-offset:1px}
  .pickers{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:18px}
  .picker{display:flex;flex-direction:column;align-items:center;gap:6px;padding:20px 10px;
  border-radius:16px;cursor:pointer;border:1px dashed rgba(255,255,255,.28);
  background:rgba(255,255,255,.04);font-size:14px;font-weight:600;text-align:center}
  .picker:active{background:rgba(255,255,255,.1)}
  .picker .ico{font-size:28px}
  .queue{margin-top:18px;display:flex;flex-direction:column;gap:10px}
  .item{display:flex;gap:12px;align-items:center;background:rgba(0,0,0,.3);
  border:1px solid var(--border);border-radius:14px;padding:10px}
  .item img{width:56px;height:56px;border-radius:10px;object-fit:cover;flex:none;background:#000}
  .item .meta{flex:1;min-width:0}
  .item .name{font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .item .sub{font-size:12px;color:var(--muted)}
  .bar{height:5px;border-radius:999px;background:rgba(255,255,255,.12);margin-top:6px;overflow:hidden}
  .bar>i{display:block;height:100%;width:0;background:linear-gradient(90deg,var(--accent),var(--accent2));transition:width .2s}
  .item.done .sub{color:var(--ok)}
  .item.fail .sub{color:var(--err)}
  .status{margin-top:14px;font-size:14px;min-height:20px}
  .status.ok{color:var(--ok)}
  .status.err{color:var(--err)}
  .foot{text-align:center;margin-top:20px;font-size:12px;color:var(--muted);line-height:1.6}
  .codigo{letter-spacing:.2em;color:var(--accent);font-weight:700}
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <div class="hero">
      <div class="ring">🎉</div>
      <h1>` + tituloSeguro + `</h1>
      <p class="muted">Sua foto aparece na TV da festa em instantes</p>
    </div>

    <label class="field">
      Seu nome (aparece junto da foto na TV)
      <input type="text" id="author" placeholder="Ex.: Rafael" maxlength="40" autocomplete="name">
    </label>

    <div class="pickers">
      <label class="picker" for="fileCamera"><span class="ico">📷</span><span>Tirar foto agora</span></label>
      <input id="fileCamera" type="file" accept="image/*" capture="environment" hidden>
      <label class="picker" for="fileGallery"><span class="ico">🖼️</span><span>Escolher da galeria</span></label>
      <input id="fileGallery" type="file" accept="image/*" multiple hidden>
    </div>

    <div class="status" id="status"></div>
    <div class="queue" id="queue"></div>

    <div class="foot">
      Festa <span class="codigo">` + escapar(codigo) + `</span><br>
      As fotos vão direto para a TV e não ficam guardadas aqui.
    </div>
  </div>
</div>

<script>
(function () {
  "use strict";

  var CODIGO = "` + escapar(codigo) + `";
  var MAX_DIM = 1920;
  var JPEG_QUALITY = 0.86;
  var SKIP_RESIZE_BELOW = 1200000;

  var $ = function (id) { return document.getElementById(id); };
  var authorInput = $("author");
  var statusEl = $("status");
  var queueEl = $("queue");

  try {
    var saved = localStorage.getItem("fotostv.author");
    if (saved) authorInput.value = saved;
  } catch (e) {}

  authorInput.addEventListener("change", function () {
    try { localStorage.setItem("fotostv.author", authorInput.value.trim()); } catch (e) {}
  });

  // Orientacao EXIF: sem isso foto de iPhone chega deitada no telao.
  function exifOrientation(file) {
    return new Promise(function (resolve) {
      var reader = new FileReader();
      reader.onerror = function () { resolve(1); };
      reader.onload = function (e) {
        try {
          var view = new DataView(e.target.result);
          if (view.byteLength < 4 || view.getUint16(0, false) !== 0xFFD8) return resolve(1);
          var offset = 2;
          while (offset + 4 < view.byteLength) {
            var marker = view.getUint16(offset, false);
            offset += 2;
            if (marker === 0xFFE1) {
              if (view.getUint32(offset + 2, false) !== 0x45786966) return resolve(1);
              var tiff = offset + 8;
              var little = view.getUint16(tiff, false) === 0x4949;
              var ifd = tiff + view.getUint32(tiff + 4, little);
              var tags = view.getUint16(ifd, little);
              for (var i = 0; i < tags; i++) {
                var entry = ifd + 2 + i * 12;
                if (view.getUint16(entry, little) === 0x0112) {
                  return resolve(view.getUint16(entry + 8, little) || 1);
                }
              }
              return resolve(1);
            }
            if ((marker & 0xFF00) !== 0xFF00) break;
            offset += view.getUint16(offset, false);
          }
        } catch (err) {}
        resolve(1);
      };
      reader.readAsArrayBuffer(file.slice(0, 131072));
    });
  }

  function drawOriented(bitmap, orientation, maxDim) {
    var w = bitmap.width, h = bitmap.height;
    var scale = Math.min(1, maxDim / Math.max(w, h));
    var sw = Math.round(w * scale), sh = Math.round(h * scale);
    var swap = orientation >= 5 && orientation <= 8;

    var canvas = document.createElement("canvas");
    canvas.width = swap ? sh : sw;
    canvas.height = swap ? sw : sh;

    var ctx = canvas.getContext("2d");
    switch (orientation) {
      case 2: ctx.transform(-1, 0, 0, 1, sw, 0); break;
      case 3: ctx.transform(-1, 0, 0, -1, sw, sh); break;
      case 4: ctx.transform(1, 0, 0, -1, 0, sh); break;
      case 5: ctx.transform(0, 1, 1, 0, 0, 0); break;
      case 6: ctx.transform(0, 1, -1, 0, sh, 0); break;
      case 7: ctx.transform(0, -1, -1, 0, sh, sw); break;
      case 8: ctx.transform(0, -1, 1, 0, 0, sw); break;
      default: break;
    }
    ctx.drawImage(bitmap, 0, 0, sw, sh);
    return canvas;
  }

  // Reduz no celular: pela internet isso importa ainda mais que na rede local.
  function prepare(file) {
    if (!window.createImageBitmap || !window.File || file.size < SKIP_RESIZE_BELOW) {
      return Promise.resolve(file);
    }
    return exifOrientation(file).then(function (orientation) {
      return createImageBitmap(file, { imageOrientation: "none" }).then(function (bitmap) {
        var canvas = drawOriented(bitmap, orientation, MAX_DIM);
        if (bitmap.close) bitmap.close();
        return new Promise(function (resolve) {
          if (!canvas.toBlob) return resolve(file);
          canvas.toBlob(function (blob) {
            resolve(blob && blob.size > 0 && blob.size < file.size ? blob : file);
          }, "image/jpeg", JPEG_QUALITY);
        });
      });
    }).catch(function () { return file; });
  }

  var queue = [];
  var sending = false;

  function addFiles(files) {
    if (!files || !files.length) return;
    for (var i = 0; i < files.length; i++) {
      var file = files[i];
      if (!file.type || file.type.indexOf("image/") !== 0) continue;
      queue.push(makeItem(file));
    }
    pump();
  }

  function makeItem(file) {
    var row = document.createElement("div");
    row.className = "item";
    row.innerHTML = '<img alt=""><div class="meta"><div class="name"></div>' +
      '<div class="sub">Na fila…</div><div class="bar"><i></i></div></div>';
    row.querySelector(".name").textContent = file.name || "foto.jpg";
    queueEl.insertBefore(row, queueEl.firstChild);

    var url = URL.createObjectURL(file);
    var img = row.querySelector("img");
    img.src = url;
    img.onload = function () { URL.revokeObjectURL(url); };
    return { file: file, row: row };
  }

  function setSub(item, text, cls) {
    item.row.querySelector(".sub").textContent = text;
    if (cls) item.row.classList.add(cls);
  }

  function setProgress(item, pct) {
    item.row.querySelector(".bar > i").style.width = pct + "%";
  }

  function pump() {
    if (sending) return;
    var item = queue.shift();
    if (!item) { statusEl.textContent = ""; return; }
    sending = true;
    setSub(item, "Preparando…");
    prepare(item.file).then(function (payload) { send(item, payload); })
      .catch(function () { send(item, item.file); });
  }

  function send(item, payload) {
    var form = new FormData();
    var filename = (item.file.name || "foto.jpg").replace(/[^\\w.\\-]+/g, "_");
    form.append("file", payload, filename);
    form.append("author", authorInput.value.trim());

    var xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/rooms/" + CODIGO + "/photos", true);

    xhr.upload.onprogress = function (e) {
      if (!e.lengthComputable) return;
      var pct = Math.round((e.loaded / e.total) * 100);
      setProgress(item, pct);
      setSub(item, "Enviando… " + pct + "%");
    };

    xhr.onload = function () {
      sending = false;
      setProgress(item, 100);
      if (xhr.status >= 200 && xhr.status < 300) {
        setSub(item, "No telão! 🎉", "done");
        statusEl.className = "status ok";
        statusEl.textContent = "Foto enviada, olha a TV!";
      } else {
        var msg = "Não deu certo.";
        try { msg = JSON.parse(xhr.responseText).error || msg; } catch (e) {}
        setSub(item, msg, "fail");
        statusEl.className = "status err";
        statusEl.textContent = msg;
      }
      pump();
    };

    xhr.onerror = function () {
      sending = false;
      setSub(item, "Sem conexão.", "fail");
      statusEl.className = "status err";
      statusEl.textContent = "Perdi a conexão. Tente de novo.";
      pump();
    };

    xhr.send(form);
  }

  $("fileCamera").addEventListener("change", function (e) { addFiles(e.target.files); e.target.value = ""; });
  $("fileGallery").addEventListener("change", function (e) { addFiles(e.target.files); e.target.value = ""; });
})();
</script>
</body>
</html>`
  );
}

function escapar(valor) {
  return String(valor).replace(/[&<>"']/g, function (c) {
    return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
  });
}
