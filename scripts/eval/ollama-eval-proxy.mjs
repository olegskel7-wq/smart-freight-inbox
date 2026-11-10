// Ollama-compatible proxy for model evaluation (ROADMAP L1). Dev only.
//
// The pipeline talks to Ollama at the tenant's `ollama.base-url` and always asks for the two models
// named in application.yaml. Point a demo tenant's base URL at this proxy and the REAL pipeline -
// same prompts, same rate / knowledge / example context - runs on any other model, with no change to
// production code:
//
//   POST /api/generate  -> answered by the model chosen below (Claude over the Anthropic API, or
//                          another model on an Ollama endpoint), in Ollama's response shape
//   everything else     -> passed through to the real Ollama (embeddings stay local)
//
// Every generate call is appended to a JSONL log (role, model, tokens, latency) - the source for the
// cost-per-email figure.
//
//   node scripts/eval/ollama-eval-proxy.mjs --short-model claude-haiku-5-5 --draft-model claude-sonnet-5-5 --log scripts/eval/runs/x.calls.jsonl
//
// The Anthropic key is read from the file SECRETS_DIR/ANTHROPIC_API_KEY (default
// ~/.smartfreight/secrets) and is never printed. Only demo data should flow through this.
import Anthropic from "@anthropic-ai/sdk";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";

const { values: opt } = parseArgs({
  options: {
    port: { type: "string", default: "11500" },
    // Model for the short structured calls (classification, extraction, document type, query routing).
    "short-model": { type: "string" },
    // Model for the reply drafts.
    "draft-model": { type: "string" },
    // The name the pipeline uses for its drafting model: a request for it is a draft, any other is short.
    "pipeline-draft-model": { type: "string", default: "llama3.1:8b" },
    // Real Ollama: pass-through target, and the endpoint for non-Claude models unless --model-url is set.
    "ollama-url": { type: "string", default: "http://localhost:11434" },
    "model-url": { type: "string" },
    log: { type: "string" },
  },
});
if (!opt["short-model"] || !opt["draft-model"] || !opt.log) {
  console.error("Usage: node ollama-eval-proxy.mjs --short-model <m> --draft-model <m> --log <file.jsonl> [--port 11500] [--model-url <ollama>]");
  process.exit(2);
}

const isClaude = (model) => model.startsWith("claude-");
let anthropic = null;
if (isClaude(opt["short-model"]) || isClaude(opt["draft-model"])) {
  const dir = process.env.SECRETS_DIR || path.join(os.homedir(), ".smartfreight", "secrets");
  const apiKey = fs.readFileSync(path.join(dir, "ANTHROPIC_API_KEY"), "utf8").replace(/^﻿/, "").trim();
  if (!apiKey) throw new Error(`ANTHROPIC_API_KEY in ${dir} is empty`);
  anthropic = new Anthropic({ apiKey });
}

// Thinking is always on for the current Claude models; effort is the cost/latency dial. A label or an
// identifier needs little of it, a customer-facing reply a bit more. max_tokens includes thinking.
const ROLES = {
  short: { model: opt["short-model"], effort: "low", maxTokens: 2048 },
  draft: { model: opt["draft-model"], effort: "medium", maxTokens: 4096 },
};

fs.mkdirSync(path.dirname(path.resolve(opt.log)), { recursive: true });
const logCall = (entry) => fs.appendFileSync(opt.log, JSON.stringify({ ts: new Date().toISOString(), ...entry }) + "\n");

async function generateWithClaude(role, prompt) {
  const response = await anthropic.messages.create({
    model: role.model,
    max_tokens: role.maxTokens,
    output_config: { effort: role.effort },
    messages: [{ role: "user", content: prompt }],
  });
  // A refusal or a truncated answer comes back as HTTP 200: surface it in the log, and hand the
  // pipeline whatever text there is (empty on a refusal - its own fail-open paths take over).
  const text = response.content.filter((b) => b.type === "text").map((b) => b.text).join("");
  return {
    text,
    inputTokens: response.usage.input_tokens,
    outputTokens: response.usage.output_tokens,
    stop: response.stop_reason,
  };
}

async function generateWithOllama(role, prompt) {
  const res = await fetch(new URL("/api/generate", opt["model-url"] || opt["ollama-url"]), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ model: role.model, prompt, stream: false }),
  });
  if (!res.ok) throw new Error(`Ollama answered ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const body = await res.json();
  return {
    text: body.response ?? "",
    inputTokens: body.prompt_eval_count ?? 0,
    outputTokens: body.eval_count ?? 0,
    stop: body.done_reason ?? "stop",
  };
}

async function handleGenerate(req, res, raw) {
  const { model: asked, prompt } = JSON.parse(raw);
  const roleName = asked === opt["pipeline-draft-model"] ? "draft" : "short";
  const role = ROLES[roleName];
  const started = Date.now();
  try {
    const out = await (isClaude(role.model) ? generateWithClaude(role, prompt) : generateWithOllama(role, prompt));
    logCall({ role: roleName, model: role.model, in: out.inputTokens, out: out.outputTokens, ms: Date.now() - started, stop: out.stop, promptChars: prompt.length });
    res.writeHead(200, { "content-type": "application/json" });
    // The fields the pipeline reads: `response`, plus the token counts its usage counter records.
    res.end(JSON.stringify({ model: asked, response: out.text, done: true, prompt_eval_count: out.inputTokens, eval_count: out.outputTokens }));
  } catch (error) {
    // Most specific first: a bad request or a bad key will not get better on retry, the rest might.
    let kind = "error";
    if (error instanceof Anthropic.BadRequestError) kind = "bad_request";
    else if (error instanceof Anthropic.AuthenticationError) kind = "auth";
    else if (error instanceof Anthropic.RateLimitError) kind = "rate_limited";
    else if (error instanceof Anthropic.APIError) kind = `api_${error.status ?? "connection"}`;
    logCall({ role: roleName, model: role.model, ms: Date.now() - started, error: kind, message: String(error.message).slice(0, 300) });
    console.error(`[${roleName}] ${role.model} failed (${kind}): ${error.message}`);
    res.writeHead(502, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: `eval proxy: ${kind}` }));
  }
}

async function passThrough(req, res, raw) {
  try {
    const upstream = await fetch(new URL(req.url, opt["ollama-url"]), {
      method: req.method,
      headers: { "content-type": req.headers["content-type"] || "application/json" },
      body: req.method === "GET" || req.method === "HEAD" ? undefined : raw,
    });
    res.writeHead(upstream.status, { "content-type": upstream.headers.get("content-type") || "application/json" });
    res.end(Buffer.from(await upstream.arrayBuffer()));
  } catch (error) {
    res.writeHead(502, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: `eval proxy: Ollama unreachable (${error.message})` }));
  }
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const raw = Buffer.concat(chunks).toString("utf8");
    const isGenerate = req.method === "POST" && req.url.split("?")[0] === "/api/generate";
    (isGenerate ? handleGenerate : passThrough)(req, res, raw).catch((error) => {
      console.error(`Unhandled: ${error.stack || error}`);
      if (!res.headersSent) res.writeHead(500);
      res.end();
    });
  });
});

// Loopback only: the proxy spends the API key for anyone who can reach it. Docker Desktop still routes
// host.docker.internal here, so the backend container uses http://host.docker.internal:<port>.
server.listen(Number(opt.port), "127.0.0.1", () => {
  console.log(`eval proxy on http://127.0.0.1:${opt.port}  short=${ROLES.short.model}  draft=${ROLES.draft.model}  log=${opt.log}`);
});
