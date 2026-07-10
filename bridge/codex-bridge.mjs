#!/usr/bin/env node
// codex-bridge.mjs
// ChatGPT サブスク枠(Codex OAuth)を、Android アプリが期待する素朴な契約に橋渡しする薄いプロキシ。
//
//   POST /chat
//     body: { model, effort, messages:[{role, content, image?(base64 png)}] }
//     resp: text/event-stream  -> data: {"delta":"..."}\n\n を連続、最後に data: [DONE]
//
//   GET /health  -> {ok:true}
//   GET /models  -> {models:[...]}   (MODELS 環境変数で上書き可)
//
// 認証は ~/.codex/auth.json（codex login 済み）を読むだけ。トークンは端末に置かない。
// 依存ゼロ（Node 18+ 標準のみ）。Windows/Linux/Termux いずれも可。
//
// 環境変数:
//   PORT(=18923) HOST(=0.0.0.0) BRIDGE_TOKEN(任意のアクセス制御)
//   CODEX_HOME(=~/.codex) INSTRUCTIONS(systemプロンプト上書き) MODELS(カンマ区切り)

import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const PORT = Number(process.env.PORT || 18923);
const HOST = process.env.HOST || "0.0.0.0";
const BRIDGE_TOKEN = process.env.BRIDGE_TOKEN || null;
const CODEX_HOME = process.env.CODEX_HOME || path.join(os.homedir(), ".codex");
const AUTH_FILE = path.join(CODEX_HOME, "auth.json");
const RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";
const TOKEN_URL = "https://auth.openai.com/oauth/token";
const CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"; // OpenAI Codex public client

// 汎用アシスタントとして振る舞わせる。endpoint は instructions 非空を要求するだけ。
// 400 が出る環境では Codex 風の前置き("You are Codex ...")に変えると通りやすい。
const INSTRUCTIONS =
  process.env.INSTRUCTIONS ||
  "You are a concise, helpful assistant embedded in a phone overlay. " +
  "When the user attaches a screenshot, answer about what is visible on it.";

const MODELS = (process.env.MODELS ||
  "gpt-5.6-sol,gpt-5.6-terra,gpt-5.6-luna,gpt-5.5,gpt-5.4,gpt-5.3-codex,gpt-5-codex-mini,o3")
  .split(",").map(s => s.trim()).filter(Boolean);

// ---------- auth.json ----------

function readAuth() {
  const raw = JSON.parse(fs.readFileSync(AUTH_FILE, "utf8"));
  const t = raw.tokens || {};
  return { raw, tokens: t,
    access: t.access_token, refresh: t.refresh_token, id: t.id_token };
}

function decodeJwt(token) {
  try {
    const p = token.split(".")[1];
    const pad = p + "=".repeat((4 - (p.length % 4)) % 4);
    return JSON.parse(Buffer.from(pad, "base64url").toString("utf8"));
  } catch { return null; }
}

function accountId(auth) {
  if (auth.tokens.account_id) return auth.tokens.account_id;
  for (const tok of [auth.access, auth.id]) {
    const c = decodeJwt(tok || "");
    const a = c?.["https://api.openai.com/auth"]?.chatgpt_account_id;
    if (a) return a;
  }
  return null;
}

function isExpired(token, skewSec = 60) {
  const c = decodeJwt(token || "");
  return !c?.exp || c.exp * 1000 - Date.now() < skewSec * 1000;
}

// OAuth refresh（標準のサポート経路は「codex に任せる」だが、単体ブリッジ用に best-effort 実装）
async function refresh(auth) {
  if (!auth.refresh) throw new Error("no refresh_token; run `codex login`");
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      client_id: CLIENT_ID,
      refresh_token: auth.refresh,
      scope: "openid profile email",
    }),
  });
  if (!res.ok) throw new Error(`refresh failed ${res.status}: ${await res.text()}`);
  const d = await res.json();
  // auth.json を壊さず差分更新（temp→rename でアトミック）
  const next = structuredClone(auth.raw);
  next.tokens = next.tokens || {};
  if (d.access_token) next.tokens.access_token = d.access_token;
  if (d.id_token) next.tokens.id_token = d.id_token;
  if (d.refresh_token) next.tokens.refresh_token = d.refresh_token;
  next.last_refresh = new Date().toISOString();
  const tmp = AUTH_FILE + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(next, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, AUTH_FILE);
  console.log("[bridge] token refreshed");
  return readAuth();
}

async function freshAuth() {
  let auth = readAuth();
  if (isExpired(auth.access)) {
    try { auth = await refresh(auth); }
    catch (e) { console.warn("[bridge] refresh skipped:", e.message); }
  }
  return auth;
}

// ---------- Responses 組み立て ----------

function buildBody({ model, effort, messages }) {
  const input = messages.map(m => {
    const role = (m.role || "user").toLowerCase();
    const isUser = role === "user" || role === "system";
    const content = [{ type: isUser ? "input_text" : "output_text", text: m.content ?? "" }];
    if (m.image && isUser) {
      content.push({ type: "input_image", image_url: `data:image/png;base64,${m.image}` });
    }
    return { type: "message", role, content };
  });
  const body = {
    model: model || MODELS[0],
    instructions: INSTRUCTIONS,
    input,
    store: false,
    stream: true,
  };
  if (effort) body.reasoning = { effort }; // minimal|low|medium|high
  return body;
}

function upstreamHeaders(auth) {
  const acct = accountId(auth);
  if (!acct) throw new Error("chatgpt_account_id not found in auth.json");
  return {
    "Authorization": `Bearer ${auth.access}`,
    "ChatGPT-Account-Id": acct,
    "Content-Type": "application/json",
    "Accept": "text/event-stream",
    // 実クライアントに寄せる（互換性が壊れにくい）。変更点はここに集約。
    "User-Agent": "codex_cli_rs/0.0.0",
    "originator": "codex_cli_rs",
    "OpenAI-Beta": "responses=experimental",
    "session_id": crypto.randomUUID(),
  };
}

async function callUpstream(payload) {
  let auth = await freshAuth();
  let res = await fetch(RESPONSES_URL, {
    method: "POST", headers: upstreamHeaders(auth), body: JSON.stringify(payload),
  });
  if (res.status === 401) {            // 期限切れ等 → 1回だけ更新して再試行
    auth = await refresh(readAuth());
    res = await fetch(RESPONSES_URL, {
      method: "POST", headers: upstreamHeaders(auth), body: JSON.stringify(payload),
    });
  }
  return res;
}

// ---------- SSE 再ラップ ----------

async function pipeStream(upstream, res) {
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
  });
  const send = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`);

  const reader = upstream.body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let nl;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line.startsWith("data:")) continue;
        const data = line.slice(5).trim();
        if (!data || data === "[DONE]") continue;
        let ev; try { ev = JSON.parse(data); } catch { continue; }
        switch (ev.type) {
          case "response.output_text.delta":
            if (ev.delta) send({ delta: ev.delta });
            break;
          case "response.failed":
          case "response.error":
          case "error":
            send({ delta: `\n⚠ ${ev.error?.message || ev.message || "upstream error"}` });
            break;
          // response.reasoning_summary_text.delta 等は無視
        }
      }
    }
  } finally {
    res.write("data: [DONE]\n\n");
    res.end();
  }
}

// ---------- HTTP ----------

function authorized(req) {
  if (!BRIDGE_TOKEN) return true;
  return req.headers["authorization"] === `Bearer ${BRIDGE_TOKEN}`;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let b = ""; req.on("data", c => (b += c));
    req.on("end", () => { try { resolve(b ? JSON.parse(b) : {}); } catch (e) { reject(e); } });
    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    return res.end(JSON.stringify({ ok: true }));
  }
  if (req.method === "GET" && req.url === "/models") {
    return res.end(JSON.stringify({ models: MODELS }));
  }
  if (req.method === "POST" && req.url === "/chat") {
    if (!authorized(req)) { res.writeHead(401); return res.end("unauthorized"); }
    try {
      const { model, effort, messages } = await readJson(req);
      if (!Array.isArray(messages) || messages.length === 0) {
        res.writeHead(400); return res.end("messages required");
      }
      const upstream = await callUpstream(buildBody({ model, effort, messages }));
      if (!upstream.ok) {
        const text = await upstream.text();
        res.writeHead(200, { "Content-Type": "text/event-stream" });
        res.write(`data: ${JSON.stringify({ delta: `⚠ upstream ${upstream.status}: ${text.slice(0, 300)}` })}\n\n`);
        res.write("data: [DONE]\n\n");
        return res.end();
      }
      await pipeStream(upstream, res);
    } catch (e) {
      console.error("[bridge] /chat error:", e);
      if (!res.headersSent) res.writeHead(500);
      res.end(`error: ${e.message}`);
    }
    return;
  }
  res.writeHead(404); res.end("not found");
});

server.listen(PORT, HOST, () => {
  console.log(`[bridge] listening http://${HOST}:${PORT}`);
  console.log(`[bridge] auth: ${AUTH_FILE}`);
  try {
    const a = readAuth();
    console.log(`[bridge] account: ${accountId(a) || "(not found)"}, access expired: ${isExpired(a.access)}`);
  } catch (e) {
    console.warn(`[bridge] auth.json 読込失敗: ${e.message} — まず \`codex login\` を実行してください`);
  }
});
