#!/usr/bin/env node

import http from "node:http";
import { spawn } from "node:child_process";
import readline from "node:readline";
import { EventEmitter } from "node:events";
import fs from "node:fs/promises";
import path from "node:path";
import vm from "node:vm";
import zlib from "node:zlib";

const HOST = process.env.MINEDIT_BRIDGE_HOST || "127.0.0.1";
const PORT = Number(process.env.MINEDIT_BRIDGE_PORT || 8765);
const CODEX_BIN = process.env.CODEX_BIN || "codex";
const REQUEST_TIMEOUT_MS = Number(process.env.MINEDIT_CODEX_TIMEOUT_MS || 10 * 60 * 1000);
const RPC_TIMEOUT_MS = Number(process.env.MINEDIT_CODEX_RPC_TIMEOUT_MS || 30 * 1000);
const MAX_BODY_BYTES = Number(process.env.MINEDIT_BRIDGE_MAX_BODY_BYTES || 5 * 1024 * 1024);
const AGENT_MAX_REVISIONS = Math.max(1, Number(process.env.MINEDIT_AGENT_MAX_REVISIONS || 2));
const AGENT_JOB_TTL_MS = Number(process.env.MINEDIT_AGENT_JOB_TTL_MS || 30 * 60 * 1000);
const PREVIEW_DIR = process.env.MINEDIT_AGENT_PREVIEW_DIR || path.join(process.cwd(), ".minedit-agent-previews");
const MAX_SIMULATED_BLOCKS = Number(process.env.MINEDIT_AGENT_MAX_SIM_BLOCKS || 220000);
const AGENT_JOBS = new Map();
let nextAgentJobId = 1;

class BridgeError extends Error {
  constructor(status, message, details = null) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

class CodexAppServerSession {
  constructor() {
    this.nextId = 1;
    this.pending = new Map();
    this.events = new EventEmitter();
    this.dynamicToolHandlers = new Map();
    this.stderr = "";
    this.closed = false;

    this.proc = spawn(CODEX_BIN, ["app-server"], {
      stdio: ["pipe", "pipe", "pipe"],
    });

    this.proc.once("error", (error) => {
      this.failAll(new BridgeError(502, `Could not start Codex app-server with '${CODEX_BIN}': ${error.message}`));
    });

    this.proc.once("exit", (code, signal) => {
      this.closed = true;
      this.failAll(new BridgeError(502, `Codex app-server exited unexpectedly (${signal || code}). ${this.stderr}`.trim()));
    });

    this.proc.stderr.on("data", (chunk) => {
      this.stderr = (this.stderr + chunk.toString()).slice(-4000);
    });

    this.rl = readline.createInterface({ input: this.proc.stdout });
    this.rl.on("line", (line) => this.handleLine(line));
  }

  async initialize() {
    await this.request("initialize", {
      clientInfo: {
        name: "minedit_bridge",
        title: "Minedit Codex Bridge",
        version: "0.1.0",
      },
      capabilities: {
        experimentalApi: true,
      },
    }, RPC_TIMEOUT_MS);
    this.notify("initialized", {});
  }

  handleLine(line) {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      this.stderr = (this.stderr + "\nNon-JSON stdout: " + line).slice(-4000);
      return;
    }

    if (Object.prototype.hasOwnProperty.call(msg, "id") && Object.prototype.hasOwnProperty.call(msg, "method")) {
      this.handleServerRequest(msg);
      return;
    }

    if (Object.prototype.hasOwnProperty.call(msg, "id")) {
      const pending = this.pending.get(msg.id);
      if (!pending) {
        return;
      }
      this.pending.delete(msg.id);
      clearTimeout(pending.timeout);
      if (msg.error) {
        pending.reject(new BridgeError(502, `${pending.method} failed: ${msg.error.message || "Codex app-server error"}`, msg.error));
      } else {
        pending.resolve(msg.result);
      }
      return;
    }

    if (msg.method) {
      this.events.emit("notification", msg);
    }
  }

  handleServerRequest(msg) {
    if (msg.method === "item/tool/call") {
      this.handleDynamicToolCall(msg);
      return;
    }
    if (msg.method === "item/commandExecution/requestApproval" || msg.method === "item/fileChange/requestApproval") {
      this.send({ id: msg.id, result: { decision: "cancel" } });
      return;
    }
    if (msg.method === "tool/requestUserInput" || msg.method === "item/tool/requestUserInput") {
      this.send({ id: msg.id, result: { answers: {} } });
      return;
    }
    this.send({
      id: msg.id,
      error: {
        code: -32601,
        message: `Minedit bridge does not handle Codex server request '${msg.method}'.`,
      },
    });
  }

  registerDynamicToolHandler(threadId, handler) {
    this.dynamicToolHandlers.set(threadId, handler);
  }

  unregisterDynamicToolHandler(threadId) {
    this.dynamicToolHandlers.delete(threadId);
  }

  handleDynamicToolCall(msg) {
    const params = msg.params || {};
    const handler = this.dynamicToolHandlers.get(params.threadId);
    if (!handler) {
      this.send({
        id: msg.id,
        result: {
          success: false,
          contentItems: [{ type: "inputText", text: "No Minedit dynamic tool handler is registered for this thread." }],
        },
      });
      return;
    }

    Promise.resolve(handler(params))
      .then((result) => {
        this.send({ id: msg.id, result });
      })
      .catch((error) => {
        this.send({
          id: msg.id,
          result: {
            success: false,
            contentItems: [{ type: "inputText", text: `Minedit tool error: ${error.message || String(error)}` }],
          },
        });
      });
  }

  request(method, params = {}, timeoutMs = RPC_TIMEOUT_MS) {
    if (this.closed) {
      return Promise.reject(new BridgeError(502, "Codex app-server is not running."));
    }
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new BridgeError(504, `${method} timed out after ${Math.round(timeoutMs / 1000)}s.`));
      }, timeoutMs);
      this.pending.set(id, { method, resolve, reject, timeout });
      this.send({ method, id, params });
    });
  }

  notify(method, params = {}) {
    this.send({ method, params });
  }

  send(message) {
    this.proc.stdin.write(`${JSON.stringify(message)}\n`);
  }

  waitForNotification(predicate, timeoutMs) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.events.off("notification", listener);
        reject(new BridgeError(504, `Codex turn timed out after ${Math.round(timeoutMs / 1000)}s.`));
      }, timeoutMs);
      const listener = (msg) => {
        if (!predicate(msg)) {
          return;
        }
        clearTimeout(timeout);
        this.events.off("notification", listener);
        resolve(msg);
      };
      this.events.on("notification", listener);
    });
  }

  failAll(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.pending.clear();
    this.events.emit("notification", { method: "bridge/error", params: { error: error.message } });
  }

  close() {
    this.closed = true;
    this.rl.close();
    if (!this.proc.killed) {
      this.proc.kill("SIGTERM");
    }
  }
}

function normalizeCodexModel(model) {
  const trimmed = String(model || "").trim();
  return trimmed.startsWith("openai/") ? trimmed.slice("openai/".length) : trimmed;
}

function effortList(model) {
  return (model?.supportedReasoningEfforts || [])
    .map((entry) => entry?.reasoningEffort)
    .filter(Boolean);
}

function findModel(models, requested) {
  const normalized = normalizeCodexModel(requested);
  return models.find((model) => model.id === normalized || model.model === normalized || model.displayName === requested);
}

function extractAgentText(turn, fallbackByItemId) {
  const items = turn?.items || [];
  const agentMessages = items.filter((item) => item?.type === "agentMessage" && typeof item.text === "string");
  const finalMessage = [...agentMessages].reverse().find((item) => item.phase === "final_answer");
  if (finalMessage) {
    return finalMessage.text;
  }
  if (agentMessages.length > 0) {
    return agentMessages[agentMessages.length - 1].text;
  }
  const fallback = [...fallbackByItemId.values()].join("").trim();
  return fallback || "";
}

async function withSession(fn) {
  const session = new CodexAppServerSession();
  try {
    await session.initialize();
    return await fn(session);
  } finally {
    session.close();
  }
}

async function readAccount(session) {
  return await session.request("account/read", { refreshToken: false }, RPC_TIMEOUT_MS);
}

async function listModels(session) {
  const result = await session.request("model/list", { limit: 200, includeHidden: true }, RPC_TIMEOUT_MS);
  return result?.data || [];
}

async function statusPayload() {
  return await withSession(async (session) => {
    const account = await readAccount(session);
    const models = await listModels(session);
    return {
      ok: true,
      requiresOpenaiAuth: Boolean(account?.requiresOpenaiAuth),
      needsLogin: Boolean(account?.requiresOpenaiAuth && !account?.account),
      account: account?.account || null,
      models: models.map((model) => ({
        id: model.id,
        model: model.model,
        displayName: model.displayName,
        defaultReasoningEffort: model.defaultReasoningEffort,
        supportedReasoningEfforts: effortList(model),
        hidden: Boolean(model.hidden),
        isDefault: Boolean(model.isDefault),
      })),
    };
  });
}

function createAgentJob() {
  cleanupAgentJobs();
  const id = `agent-${Date.now()}-${nextAgentJobId++}`;
  const job = {
    id,
    status: "running",
    events: [],
    nextSeq: 1,
    batches: [],
    nextBatchId: 1,
    text: "",
    summary: "",
    error: "",
    startedAt: Date.now(),
    finishedAt: 0,
  };
  AGENT_JOBS.set(id, job);
  return job;
}

function cleanupAgentJobs() {
  const now = Date.now();
  for (const [id, job] of AGENT_JOBS.entries()) {
    if (job.finishedAt && now - job.finishedAt > AGENT_JOB_TTL_MS) {
      AGENT_JOBS.delete(id);
    }
  }
}

function agentEvent(job, message) {
  const trimmed = String(message || "").replace(/\s+/g, " ").trim();
  if (!trimmed) {
    return;
  }
  job.events.push({
    seq: job.nextSeq++,
    at: new Date().toISOString(),
    message: trimmed.length > 240 ? `${trimmed.slice(0, 237)}...` : trimmed,
  });
  if (job.events.length > 160) {
    job.events.splice(0, job.events.length - 160);
  }
}

function agentBatch(job, code, simulation, summary) {
  job.batches.push({
    id: job.nextBatchId++,
    code,
    operationCount: simulation.stats.operationCount,
    nonAirBlocks: simulation.stats.nonAirBlocks,
    summary,
  });
  if (job.batches.length > 80) {
    job.batches.splice(0, job.batches.length - 80);
  }
}

function agentStatusPayload(job, afterSeq, afterBatch = 0) {
  return {
    ok: true,
    jobId: job.id,
    status: job.status,
    events: job.events.filter((event) => event.seq > afterSeq),
    batches: job.batches.filter((batch) => batch.id > afterBatch),
    text: job.status === "completed" ? job.text : "",
    summary: job.status === "completed" ? job.summary : "",
    error: job.status === "failed" ? job.error : "",
  };
}

function startAgentBuild(body) {
  const job = createAgentJob();
  agentEvent(job, "Minedit agent: job accepted by Codex bridge.");
  runAgentBuildJob(job, body).catch((error) => {
    job.status = "failed";
    job.error = error.message || "Codex agent build failed.";
    job.finishedAt = Date.now();
    agentEvent(job, `Minedit agent failed: ${job.error}`);
  });
  return { ok: true, jobId: job.id };
}

function startAgentStepByStepBuild(body) {
  const job = createAgentJob();
  agentEvent(job, "Minedit tool agent: job accepted by Codex bridge.");
  runAgentStepByStepBuildJob(job, body).catch((error) => {
    job.status = "failed";
    job.error = error.message || "Codex step-by-step agent build failed.";
    job.finishedAt = Date.now();
    agentEvent(job, `Minedit tool agent failed: ${job.error}`);
  });
  return { ok: true, jobId: job.id };
}

async function runAgentBuildJob(job, body) {
  const prompt = body?.prompt;
  const model = body?.model;
  const effort = body?.effort;
  const width = Number(body?.width);
  const depth = Number(body?.depth);
  if (!prompt || typeof prompt !== "string") {
    throw new BridgeError(400, "Missing string field: prompt");
  }
  if (!model || typeof model !== "string") {
    throw new BridgeError(400, "Missing string field: model");
  }
  if (!effort || typeof effort !== "string") {
    throw new BridgeError(400, "Missing string field: effort");
  }
  if (!Number.isInteger(width) || width <= 0 || !Number.isInteger(depth) || depth <= 0) {
    throw new BridgeError(400, "Missing valid integer fields: width and depth");
  }

  await fs.mkdir(PREVIEW_DIR, { recursive: true });
  await withSession(async (session) => {
    agentEvent(job, "Minedit agent: checking Codex login and model availability...");
    const account = await readAccount(session);
    if (account?.requiresOpenaiAuth && !account?.account) {
      throw new BridgeError(401, "Codex is not logged in. Run 'codex login' in a terminal, then restart the Minedit bridge.");
    }

    const models = await listModels(session);
    const modelEntry = findModel(models, model);
    if (!modelEntry) {
      const visible = models.filter((entry) => !entry.hidden).slice(0, 12).map((entry) => entry.model || entry.id).join(", ");
      throw new BridgeError(400, `Codex model '${normalizeCodexModel(model)}' was not found. Try /model gpt-5.5 or use /codex status. Available examples: ${visible || "none returned"}`);
    }

    const supportedEfforts = effortList(modelEntry);
    if (supportedEfforts.length > 0 && !supportedEfforts.includes(effort)) {
      throw new BridgeError(400, `Codex model '${modelEntry.model}' does not support reasoning effort '${effort}'. Supported: ${supportedEfforts.join(", ")}`);
    }

    const modelToUse = modelEntry.model || modelEntry.id;
    const threadResult = await session.request("thread/start", {
      model: modelToUse,
      cwd: process.cwd(),
      ephemeral: true,
      serviceName: "minedit-agent",
      approvalPolicy: "never",
      sandbox: "read-only",
      personality: "pragmatic",
      developerInstructions: agentDeveloperInstructions(),
    }, RPC_TIMEOUT_MS);

    const threadId = threadResult?.thread?.id;
    if (!threadId) {
      throw new BridgeError(502, "Codex app-server did not return a thread id.");
    }

    attachAgentProgress(session, threadId, job);
    agentEvent(job, `Minedit agent: generating initial draft with ${modelToUse} (${effort})...`);
    let text = await runCodexTurn(session, threadId, modelToUse, effort, [
      { type: "text", text: agentInitialPrompt(prompt) },
    ]);
    let code = extractCodeFromText(text);
    let simulation = simulateBuildCode(code, width, depth);
    agentEvent(job, `Minedit agent: draft generated, ${simulation.stats.operationCount} operations, ${simulation.stats.nonAirBlocks} preview blocks.`);

    let revision = 1;
    while (revision <= AGENT_MAX_REVISIONS) {
      const preview = await renderPreviewPng(simulation, width, depth, `${job.id}-preview-${revision}.png`);
      const report = validationReport(simulation);
      agentEvent(job, `Minedit agent: rendered preview ${revision}: ${path.basename(preview.path)}.`);
      if (simulation.issues.length > 0) {
        agentEvent(job, `Minedit agent: validator notes: ${compactIssueSummary(simulation.issues)}.`);
      } else {
        agentEvent(job, "Minedit agent: validator did not find obvious block-physics issues.");
      }

      agentEvent(job, `Minedit agent: sending preview ${revision} to Codex for visual review...`);
      text = await runCodexTurn(session, threadId, modelToUse, effort, [
        { type: "text", text: agentRevisionPrompt(prompt, code, report, revision) },
        { type: "localImage", path: preview.path },
      ]);
      code = extractCodeFromText(text);
      simulation = simulateBuildCode(code, width, depth);
      agentEvent(job, `Minedit agent: revision ${revision} returned, ${simulation.stats.operationCount} operations, ${simulation.stats.nonAirBlocks} preview blocks.`);

      if (!hasSevereIssues(simulation)) {
        break;
      }
      revision++;
    }

    const finalPreview = await renderPreviewPng(simulation, width, depth, `${job.id}-final.png`);
    job.text = code;
    job.summary = `Minedit agent: final preview ${path.basename(finalPreview.path)}, ${simulation.stats.nonAirBlocks} preview blocks, ${simulation.issues.length} validation notes.`;
    job.status = "completed";
    job.finishedAt = Date.now();
    agentEvent(job, job.summary);
  });
}

async function runAgentStepByStepBuildJob(job, body) {
  const prompt = body?.prompt;
  const model = body?.model;
  const effort = body?.effort;
  const width = Number(body?.width);
  const depth = Number(body?.depth);
  if (!prompt || typeof prompt !== "string") {
    throw new BridgeError(400, "Missing string field: prompt");
  }
  if (!model || typeof model !== "string") {
    throw new BridgeError(400, "Missing string field: model");
  }
  if (!effort || typeof effort !== "string") {
    throw new BridgeError(400, "Missing string field: effort");
  }
  if (!Number.isInteger(width) || width <= 0 || !Number.isInteger(depth) || depth <= 0) {
    throw new BridgeError(400, "Missing valid integer fields: width and depth");
  }

  await fs.mkdir(PREVIEW_DIR, { recursive: true });
  await withSession(async (session) => {
    agentEvent(job, "Minedit tool agent: checking Codex login and model availability...");
    const account = await readAccount(session);
    if (account?.requiresOpenaiAuth && !account?.account) {
      throw new BridgeError(401, "Codex is not logged in. Run 'codex login' in a terminal, then restart the Minedit bridge.");
    }

    const models = await listModels(session);
    const modelEntry = findModel(models, model);
    if (!modelEntry) {
      const visible = models.filter((entry) => !entry.hidden).slice(0, 12).map((entry) => entry.model || entry.id).join(", ");
      throw new BridgeError(400, `Codex model '${normalizeCodexModel(model)}' was not found. Try /model gpt-5.5 or use /codex status. Available examples: ${visible || "none returned"}`);
    }

    const supportedEfforts = effortList(modelEntry);
    if (supportedEfforts.length > 0 && !supportedEfforts.includes(effort)) {
      throw new BridgeError(400, `Codex model '${modelEntry.model}' does not support reasoning effort '${effort}'. Supported: ${supportedEfforts.join(", ")}`);
    }

    const modelToUse = modelEntry.model || modelEntry.id;
    const threadResult = await session.request("thread/start", {
      model: modelToUse,
      cwd: process.cwd(),
      ephemeral: true,
      serviceName: "minedit-agent-tools",
      approvalPolicy: "never",
      sandbox: "read-only",
      personality: "pragmatic",
      developerInstructions: agentToolDeveloperInstructions(),
      dynamicTools: mineditDynamicTools(),
    }, RPC_TIMEOUT_MS);

    const threadId = threadResult?.thread?.id;
    if (!threadId) {
      throw new BridgeError(502, "Codex app-server did not return a thread id.");
    }

    attachAgentProgress(session, threadId, job);

    let simulation = emptySimulation();
    const codeSteps = [];
    let toolFinished = false;
    let finishSummary = "";

    session.registerDynamicToolHandler(threadId, async (params) => {
      const rawTool = params.tool || "";
      const tool = rawTool.startsWith("minedit.") ? rawTool.slice("minedit.".length) : rawTool;
      const namespace = params.namespace || "";
      const args = params.arguments && typeof params.arguments === "object" ? params.arguments : {};
      if (namespace && namespace !== "minedit") {
        return toolText(false, `Unknown dynamic tool namespace '${namespace}'. Use the minedit tools.`);
      }

      if (tool === "place_batch" || tool === "place_step") {
        const rawCode = typeof args.code === "string" ? args.code : "";
        if (!rawCode.trim()) {
          return toolText(false, `${tool} requires a non-empty code string containing function build(api).`);
        }
        const phase = typeof args.phase === "string" ? args.phase.trim() : "";
        if (!phase) {
          return toolText(false, `${tool} requires a short phase name like foundation, floor, walls, openings, roof, lighting, furniture, exterior_detail, or correction.`);
        }

        const code = extractCodeFromText(rawCode);
        simulation = simulateBuildCode(code, width, depth, simulation.grid);
        const batchNumber = codeSteps.length + 1;
        const summary = `step ${batchNumber} (${phase}): ${simulation.stats.apiCallCount} api calls, ${simulation.stats.operationCount} block writes, ${simulation.stats.nonAirBlocks} cumulative preview blocks`;
        codeSteps.push(`// step ${batchNumber}: ${phase}\n${code}`);
        agentBatch(job, code, simulation, summary);
        agentEvent(job, `Minedit tool agent: queued ${summary}.`);

        const preview = await renderPreviewPng(simulation, width, depth, `${job.id}-tool-${batchNumber}.png`);
        const report = validationReport(simulation);
        if (simulation.issues.length > 0) {
          agentEvent(job, `Minedit tool agent: validator notes: ${compactIssueSummary(simulation.issues)}.`);
        } else {
          agentEvent(job, "Minedit tool agent: validator did not find obvious block-physics issues.");
        }
        const sizeHint = simulation.stats.apiCallCount > 14
          ? "\nNote: this was a fairly large step. Prefer smaller phase steps next, such as only floor, only walls, only roof trim, only lighting, or only furniture."
          : "";
        return toolPreview(true, `Placed step ${batchNumber} (${phase}).${sizeHint}\n${report}`, preview.path);
      }

      if (tool === "render_preview") {
        const preview = await renderPreviewPng(simulation, width, depth, `${job.id}-tool-preview-${Date.now()}.png`);
        return toolPreview(true, validationReport(simulation), preview.path);
      }

      if (tool === "inspect_status") {
        return toolText(true, validationReport(simulation));
      }

      if (tool === "finish_build") {
        toolFinished = true;
        finishSummary = typeof args.summary === "string" ? args.summary.trim() : "";
        agentEvent(job, `Minedit tool agent: Codex marked build finished${finishSummary ? `: ${finishSummary}` : "."}`);
        return toolText(true, `Marked finished. Final visible preview blocks: ${simulation.stats.nonAirBlocks}.`);
      }

      return toolText(false, `Unknown Minedit tool '${tool}'. Available tools: place_step, place_batch, render_preview, inspect_status, finish_build.`);
    });

    try {
      agentEvent(job, `Minedit tool agent: starting one Codex agent turn with ${modelToUse} (${effort})...`);
      const text = await runCodexTurn(session, threadId, modelToUse, effort, [
        { type: "text", text: agentToolInitialPrompt(prompt, width, depth) },
      ]);
      job.text = codeSteps.join("\n\n");
      const finishedLabel = toolFinished ? "finished" : "ended";
      const finalNote = finishSummary || text.replace(/\s+/g, " ").trim();
      job.summary = `Minedit tool agent: Codex ${finishedLabel} after ${codeSteps.length} placement steps, ${simulation.stats.nonAirBlocks} preview blocks${finalNote ? `. ${finalNote}` : "."}`;
      job.status = "completed";
      job.finishedAt = Date.now();
      agentEvent(job, job.summary);
    } finally {
      session.unregisterDynamicToolHandler(threadId);
    }
  });
}

function agentDeveloperInstructions() {
  return [
    "You are an agentic Minecraft build designer for Minedit.",
    "Your only deliverable is Rhino-compatible JavaScript defining function build(api).",
    "The bridge will execute your build(api) function in a simulator, render local preview images, and send them back for visual review.",
    "Use the previews to improve shape, detail, scale, roof orientation, fluid containment, supports, and obvious Minecraft mistakes.",
    "Do not ask the user questions. Do not use markdown in final code. Do not use OS commands unless explicitly required by the user.",
    "Return only one JavaScript function named build when asked for code.",
  ].join(" ");
}

function agentToolDeveloperInstructions() {
  return [
    "You are an autonomous Minecraft build agent for Minedit.",
    "Use the provided minedit dynamic tools to build, inspect, render previews, and finish. Do not merely describe a plan.",
    "Call minedit.place_step to apply one construction phase at a time to the live Minecraft world.",
    "Keep placement steps narrow and legible: foundation, floor, walls, openings, roof frame, roof detail, lighting, furniture, exterior detail, landscaping, correction.",
    "Do not combine the whole building into one or two giant tool calls. A normal build should use many small placement steps.",
    "Call minedit.render_preview or minedit.inspect_status between major phases before deciding the next action.",
    "Keep acting until the build is coherent, detailed, lit, navigable, and matches the user request, then call minedit.finish_build and provide a concise final message.",
    "Do not ask the user questions. Do not use OS commands. Do not output raw build code as your final answer.",
  ].join(" ");
}

function agentInitialPrompt(prompt) {
  return `${prompt}

Agent mode:
- Produce the first complete draft of build(api).
- The bridge will render it into a local image and send it back to you for visual review.
- Do not use api.replaceLine or api.clearLine; this is build mode on a blank cleared footprint.
- Return raw JavaScript only.`;
}

function agentToolInitialPrompt(prompt, width, depth) {
  return `${prompt}

Tool-driven Minedit agent mode:
- The selected footprint is ${width} x ${depth}. Coordinates are relative to the selected footprint; y=0 is the selected base height.
- Minecraft will clear the selected footprint when your first valid place_step tool call arrives, so do not spend effort clearing terrain.
- Act autonomously by calling the minedit tools. Place the build one phase at a time, inspect/render, then continue.
- For each place_step call, provide a Rhino-compatible JavaScript function build(api) using api.set and api.fill.
- Each placement step should usually cover one phase only: foundation, floor, walls, door/window openings, roof frame, roof detail, lighting, furniture, exterior detail, landscaping, or a correction.
- Do not place floor + walls + roof + furniture in one call. The point is to work like an agent making visible progress step by step.
- Prefer roughly 3-12 high-level api calls per placement step. A single fill may cover a whole floor or wall plane; that is fine.
- Do not use api.replaceLine or api.clearLine; this is build mode on a blank cleared footprint.
- Avoid repeating previous blocks unless you are intentionally correcting or replacing them.
- Check previews for weak shape, sparse walls, empty interiors, bad door orientation, blocked paths, roof gaps/inversion, unsupported decorations, bad slab/trapdoor/fence orientation, and fluid overflow.
- When you are satisfied, call minedit.finish_build with a short summary, then answer briefly.`;
}

function mineditDynamicTools() {
  return [
    {
      namespace: "minedit",
      name: "place_step",
      description: "Apply one focused construction phase to the live selected footprint and receive a cumulative rendered preview plus validation report. Use this repeatedly for phases like floor, walls, openings, roof, lighting, furniture, exterior details, and corrections.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        required: ["phase", "code"],
        properties: {
          phase: {
            type: "string",
            description: "Short phase name, for example foundation, floor, walls, openings, roof_frame, roof_detail, lighting, furniture, exterior_detail, landscaping, or correction.",
          },
          code: {
            type: "string",
            description: "Rhino-compatible JavaScript defining function build(api). Keep this to one construction phase. Use api.set(x,y,z,block,states?) and api.fill(x1,y1,z1,x2,y2,z2,block,options?).",
          },
          note: {
            type: "string",
            description: "Short private note about what this step is intended to add or fix.",
          },
        },
      },
    },
    {
      namespace: "minedit",
      name: "place_batch",
      description: "Compatibility alias for place_step. Prefer place_step and keep each call to one construction phase.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        required: ["phase", "code"],
        properties: {
          phase: {
            type: "string",
            description: "Short phase name, for example foundation, floor, walls, openings, roof_frame, roof_detail, lighting, furniture, exterior_detail, landscaping, or correction.",
          },
          code: {
            type: "string",
            description: "Rhino-compatible JavaScript defining function build(api). Keep this to one construction phase.",
          },
          note: {
            type: "string",
          },
        },
      },
    },
    {
      namespace: "minedit",
      name: "render_preview",
      description: "Render the current cumulative build state without placing new blocks. Use this when you want to visually inspect before deciding the next batch.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        properties: {},
      },
    },
    {
      namespace: "minedit",
      name: "inspect_status",
      description: "Return the current cumulative validation report and build statistics without rendering a new image.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        properties: {},
      },
    },
    {
      namespace: "minedit",
      name: "finish_build",
      description: "Mark the build as complete after you have placed enough batches and inspected the result.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        required: ["summary"],
        properties: {
          summary: {
            type: "string",
            description: "Brief summary of the finished build and any caveats.",
          },
        },
      },
    },
  ];
}

function toolText(success, text) {
  return {
    success,
    contentItems: [{ type: "inputText", text }],
  };
}

async function toolPreview(success, text, imagePath) {
  const image = await fs.readFile(imagePath);
  return {
    success,
    contentItems: [
      { type: "inputText", text },
      { type: "inputImage", imageUrl: `data:image/png;base64,${image.toString("base64")}` },
    ],
  };
}

function agentRevisionPrompt(originalPrompt, currentCode, report, revision) {
  return `You are reviewing your Minecraft build draft in Minedit agent mode.

The attached image is a lightweight isometric render of the current generated build, not a perfect Minecraft screenshot. Colors are approximate, but the shape, block placement, roof massing, fluid positions, and visible gaps should be useful.

Original user request and build API instructions:
${originalPrompt}

Validator and preview report:
${report}

Review the image and the report. Improve the build if it looks sparse, ugly, incorrectly shaped, has weird roof orientation, air gaps, unsupported decorations, fluid overflow risk, bad scale, or misses the user's prompt. Keep what is already good. You may rewrite the function if that is cleaner.

Current draft code:
${currentCode}

Return only the revised complete JavaScript function:
function build(api) { ... }

This is visual review pass ${revision}.`;
}

function attachAgentProgress(session, threadId, job) {
  const summaryBuffers = new Map();
  session.events.on("notification", (msg) => {
    if (msg.params?.threadId !== threadId) {
      return;
    }
    if (msg.method === "item/started") {
      const item = msg.params.item;
      if (item?.type === "plan") {
        agentEvent(job, "Codex agent: planning...");
      } else if (item?.type === "reasoning") {
        agentEvent(job, "Codex agent: thinking...");
      } else if (item?.type === "commandExecution") {
        agentEvent(job, `Codex agent: running ${Array.isArray(item.command) ? item.command.join(" ") : "a command"}...`);
      }
      return;
    }
    if (msg.method === "item/reasoning/summaryTextDelta") {
      const itemId = msg.params.itemId || "reasoning";
      const next = (summaryBuffers.get(itemId) || "") + (msg.params.delta || "");
      if (next.length >= 180 || /[.!?]\s$/.test(next)) {
        agentEvent(job, `Codex agent: ${next}`);
        summaryBuffers.set(itemId, "");
      } else {
        summaryBuffers.set(itemId, next);
      }
    }
  });
}

async function runCodexTurn(session, threadId, model, effort, input) {
  const deltasByItemId = new Map();
  const completedAgentMessages = new Map();
  const listener = (msg) => {
    if (msg.params?.threadId !== threadId) {
      return;
    }
    if (msg.method === "item/agentMessage/delta") {
      const itemId = msg.params.itemId;
      deltasByItemId.set(itemId, (deltasByItemId.get(itemId) || "") + (msg.params.delta || ""));
    }
    if (msg.method === "item/completed") {
      const item = msg.params.item;
      if (item?.type === "agentMessage" && typeof item.text === "string") {
        completedAgentMessages.set(item.id || String(completedAgentMessages.size), item.text);
      }
    }
  };
  session.events.on("notification", listener);
  try {
    const turnResult = await session.request("turn/start", {
      threadId,
      input,
      model,
      effort,
      approvalPolicy: "never",
      summary: "concise",
    }, RPC_TIMEOUT_MS);

    const turnId = turnResult?.turn?.id;
    if (!turnId) {
      throw new BridgeError(502, "Codex app-server did not return a turn id.");
    }

    let completed = null;
    if (turnResult.turn.status === "completed") {
      completed = { params: { turn: turnResult.turn } };
    } else {
      completed = await session.waitForNotification(
        (msg) => msg.method === "turn/completed" && msg.params?.threadId === threadId && msg.params?.turn?.id === turnId,
        REQUEST_TIMEOUT_MS,
      );
    }

    const turn = completed.params.turn;
    if (turn.status === "failed") {
      throw new BridgeError(502, turn.error?.message || "Codex turn failed.", turn.error || null);
    }
    if (turn.status !== "completed") {
      throw new BridgeError(502, `Codex turn ended with status '${turn.status}'.`);
    }

    const text = extractAgentText(turn, completedAgentMessages.size > 0 ? completedAgentMessages : deltasByItemId).trim();
    if (!text) {
      throw new BridgeError(502, "Codex completed but returned no agent message text.");
    }
    return text;
  } finally {
    session.events.off("notification", listener);
  }
}

function extractCodeFromText(text) {
  let trimmed = String(text || "").trim();
  const codeTagStart = trimmed.indexOf("<code>");
  const codeTagEnd = trimmed.indexOf("</code>", codeTagStart + 6);
  if (codeTagStart >= 0 && codeTagEnd > codeTagStart) {
    trimmed = trimmed.slice(codeTagStart + 6, codeTagEnd).trim();
  }

  const fence = trimmed.indexOf("```");
  if (fence >= 0) {
    const lineEnd = trimmed.indexOf("\n", fence);
    const endFence = trimmed.indexOf("```", lineEnd + 1);
    if (lineEnd >= 0 && endFence > lineEnd) {
      trimmed = trimmed.slice(lineEnd + 1, endFence).trim();
    }
  }
  trimmed = trimmed.split(/\r?\n/).filter((line) => !line.trim().startsWith("```")).join("\n").trim();

  const functionIndex = trimmed.indexOf("function build");
  if (functionIndex < 0) {
    throw new BridgeError(502, "Codex did not return a function named build(api).");
  }
  const braceIndex = trimmed.indexOf("{", functionIndex);
  if (braceIndex < 0) {
    throw new BridgeError(502, "Codex returned an incomplete build function.");
  }

  let depth = 0;
  let inSingle = false;
  let inDouble = false;
  let escaped = false;
  for (let i = braceIndex; i < trimmed.length; i++) {
    const ch = trimmed[i];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (ch === "\\") {
      escaped = true;
      continue;
    }
    if (ch === "'" && !inDouble) {
      inSingle = !inSingle;
      continue;
    }
    if (ch === '"' && !inSingle) {
      inDouble = !inDouble;
      continue;
    }
    if (inSingle || inDouble) {
      continue;
    }
    if (ch === "{") {
      depth++;
    } else if (ch === "}") {
      depth--;
      if (depth === 0) {
        return trimmed.slice(functionIndex, i + 1).trim();
      }
    }
  }
  throw new BridgeError(502, "Codex returned a build function with unbalanced braces.");
}

function emptySimulation() {
  return {
    grid: new Map(),
    blocks: [],
    issues: [],
    stats: {
      apiCallCount: 0,
      operationCount: 0,
      nonAirBlocks: 0,
      minY: 0,
      maxY: 0,
    },
  };
}

function cloneGrid(source) {
  const grid = new Map();
  if (!source) {
    return grid;
  }
  for (const [key, block] of source.entries()) {
    grid.set(key, {
      x: block.x,
      y: block.y,
      z: block.z,
      id: block.id,
      states: { ...(block.states || {}) },
    });
  }
  return grid;
}

function simulateBuildCode(code, width, depth, initialGrid = null) {
  const grid = cloneGrid(initialGrid);
  const issues = [];
  let apiCallCount = 0;
  let operationCount = 0;
  let outOfBounds = 0;
  let simulatedWrites = 0;

  const key = (x, y, z) => `${x},${y},${z}`;
  const setBlock = (x, y, z, blockId, states = {}) => {
    operationCount++;
    x = Math.floor(Number(x));
    y = Math.floor(Number(y));
    z = Math.floor(Number(z));
    if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) {
      issues.push({ severity: "error", message: "A block operation used non-finite coordinates." });
      return;
    }
    if (x < 0 || x >= width || z < 0 || z >= depth) {
      outOfBounds++;
      return;
    }
    simulatedWrites++;
    if (simulatedWrites > MAX_SIMULATED_BLOCKS) {
      throw new BridgeError(502, `Generated build is too large to preview safely (${MAX_SIMULATED_BLOCKS}+ simulated writes).`);
    }
    const id = normalizeBlockId(blockId);
    if (isAir(id)) {
      grid.delete(key(x, y, z));
      return;
    }
    grid.set(key(x, y, z), { x, y, z, id, states: stringMap(states) });
  };

  const api = {
    width,
    depth,
    getWidth: () => width,
    getDepth: () => depth,
    set: (x, y, z, blockId, states = {}) => {
      apiCallCount++;
      setBlock(x, y, z, blockId, states);
    },
    fill: (x1, y1, z1, x2, y2, z2, blockId, options = {}) => {
      apiCallCount++;
      const minX = Math.floor(Math.min(Number(x1), Number(x2)));
      const maxX = Math.floor(Math.max(Number(x1), Number(x2)));
      const minY = Math.floor(Math.min(Number(y1), Number(y2)));
      const maxY = Math.floor(Math.max(Number(y1), Number(y2)));
      const minZ = Math.floor(Math.min(Number(z1), Number(z2)));
      const maxZ = Math.floor(Math.max(Number(z1), Number(z2)));
      const mode = typeof options?.mode === "string" ? options.mode : "replace";
      const stateSource = typeof options?.states === "object" ? options.states : options;
      const states = stringMap(stateSource);
      const volume = Math.max(0, maxX - minX + 1) * Math.max(0, maxY - minY + 1) * Math.max(0, maxZ - minZ + 1);
      if (volume > MAX_SIMULATED_BLOCKS) {
        throw new BridgeError(502, `Generated fill is too large to preview safely (${volume} blocks).`);
      }

      for (let y = minY; y <= maxY; y++) {
        for (let z = minZ; z <= maxZ; z++) {
          for (let x = minX; x <= maxX; x++) {
            const boundary = x === minX || x === maxX || y === minY || y === maxY || z === minZ || z === maxZ;
            if ((mode === "hollow" || mode === "outline") && !boundary) {
              continue;
            }
            const k = key(x, y, z);
            if (mode === "keep" && grid.has(k)) {
              continue;
            }
            if (mode === "clear" && !grid.has(k)) {
              continue;
            }
            setBlock(x, y, z, blockId, states);
          }
        }
      }
    },
    replaceLine: () => {
      issues.push({ severity: "warning", message: "Generated code used replaceLine in build-agent mode; replaceLine is ignored in blank build previews." });
    },
    clearLine: () => {
      issues.push({ severity: "warning", message: "Generated code used clearLine in build-agent mode; clearLine is ignored in blank build previews." });
    },
  };

  const sandbox = { api, Math };
  vm.createContext(sandbox);
  try {
    vm.runInContext(`${code}\n;build(api);`, sandbox, { timeout: 1800 });
  } catch (error) {
    throw new BridgeError(502, `Generated JavaScript failed in preview simulator: ${error.message}`);
  }

  if (outOfBounds > 0) {
    issues.push({ severity: "error", message: `${outOfBounds} block writes were outside the selected X/Z footprint and will be skipped.` });
  }

  const blocks = [...grid.values()];
  const stats = {
    apiCallCount,
    operationCount,
    nonAirBlocks: blocks.length,
    minY: blocks.length ? Math.min(...blocks.map((block) => block.y)) : 0,
    maxY: blocks.length ? Math.max(...blocks.map((block) => block.y)) : 0,
  };
  issues.push(...validateBlocks(grid, width, depth));
  return { grid, blocks, issues, stats };
}

function validateBlocks(grid, width, depth) {
  const issues = [];
  const key = (x, y, z) => `${x},${y},${z}`;
  const hasBlock = (x, y, z) => grid.has(key(x, y, z));
  const blockAt = (x, y, z) => grid.get(key(x, y, z));
  let unsupportedPlants = 0;
  let unsupportedDecor = 0;
  let edgeFluids = 0;
  let floatingBlocks = 0;

  for (const block of grid.values()) {
    if (isPlantLike(block.id)) {
      const below = blockAt(block.x, block.y - 1, block.z);
      if (!below || !isPlantSupport(below.id)) {
        unsupportedPlants++;
      }
    }
    if (isSupportSensitive(block.id)) {
      const below = blockAt(block.x, block.y - 1, block.z);
      const above = blockAt(block.x, block.y + 1, block.z);
      if (!below && !above) {
        unsupportedDecor++;
      }
    }
    if (isFluid(block.id) && (block.x <= 0 || block.z <= 0 || block.x >= width - 1 || block.z >= depth - 1)) {
      edgeFluids++;
    }
    if (!isFluid(block.id) && !isPlantLike(block.id) && block.y > 0 && !hasBlock(block.x, block.y - 1, block.z) && !isLikelyAllowedFloating(block.id)) {
      floatingBlocks++;
    }
  }

  if (unsupportedPlants > 0) {
    issues.push({ severity: "error", message: `${unsupportedPlants} plant-like blocks may not have valid support below.` });
  }
  if (unsupportedDecor > 0) {
    issues.push({ severity: "warning", message: `${unsupportedDecor} support-sensitive decorative blocks may not have support.` });
  }
  if (edgeFluids > 0) {
    issues.push({ severity: "error", message: `${edgeFluids} water/lava blocks are on the footprint edge and may overflow outside the build.` });
  }
  if (floatingBlocks > 12) {
    issues.push({ severity: "warning", message: `${floatingBlocks} non-air blocks appear to float without support. Some may be intentional roof/canopy blocks.` });
  }
  if (grid.size === 0) {
    issues.push({ severity: "error", message: "The preview contains no non-air blocks." });
  }
  return issues;
}

function validationReport(simulation) {
  const lines = [
    `Stats: apiCalls=${simulation.stats.apiCallCount}, blockWrites=${simulation.stats.operationCount}, visible/non-air preview blocks=${simulation.stats.nonAirBlocks}, y=${simulation.stats.minY}..${simulation.stats.maxY}.`,
  ];
  if (simulation.issues.length === 0) {
    lines.push("Validator: no obvious block-physics issues found.");
  } else {
    lines.push("Validator notes:");
    for (const issue of simulation.issues.slice(0, 16)) {
      lines.push(`- ${issue.severity}: ${issue.message}`);
    }
  }
  return lines.join("\n");
}

function compactIssueSummary(issues) {
  if (issues.length === 0) {
    return "none";
  }
  return issues.slice(0, 3).map((issue) => issue.message).join(" ");
}

function hasSevereIssues(simulation) {
  return simulation.issues.some((issue) => issue.severity === "error");
}

async function renderPreviewPng(simulation, width, depth, filename) {
  const visible = visibleBlocks(simulation.grid, simulation.blocks).slice(0, 30000);
  const canvasWidth = 1024;
  const canvasHeight = 768;
  const image = newImage(canvasWidth, canvasHeight, [204, 222, 241]);
  fillRect(image, 0, Math.floor(canvasHeight * 0.66), canvasWidth, Math.ceil(canvasHeight * 0.34), [108, 151, 93]);

  if (visible.length > 0) {
    const tileW = Math.max(8, Math.min(28, Math.floor(900 / Math.max(width + depth, 1))));
    const tileH = Math.max(4, Math.floor(tileW / 2));
    const blockH = Math.max(5, tileH);
    const projected = visible.map((block) => projectBlock(block, tileW, tileH, blockH));
    const minX = Math.min(...projected.map((point) => point.x - tileW));
    const maxX = Math.max(...projected.map((point) => point.x + tileW));
    const minY = Math.min(...projected.map((point) => point.y - tileH));
    const maxY = Math.max(...projected.map((point) => point.y + tileH + blockH));
    const offsetX = Math.floor((canvasWidth - (maxX - minX)) / 2 - minX);
    const offsetY = Math.floor((canvasHeight - (maxY - minY)) / 2 - minY + 20);

    visible.sort((a, b) => {
      const da = a.x + a.z + a.y * 0.15;
      const db = b.x + b.z + b.y * 0.15;
      return da === db ? a.y - b.y : da - db;
    });

    for (const block of visible) {
      drawCube(image, block, tileW, tileH, blockH, offsetX, offsetY);
    }
  }

  const filePath = path.resolve(PREVIEW_DIR, filename);
  await fs.writeFile(filePath, encodePng(image.width, image.height, image.data));
  return { path: filePath };
}

function visibleBlocks(grid, blocks) {
  const key = (x, y, z) => `${x},${y},${z}`;
  return blocks.filter((block) =>
    !grid.has(key(block.x, block.y + 1, block.z))
    || !grid.has(key(block.x + 1, block.y, block.z))
    || !grid.has(key(block.x - 1, block.y, block.z))
    || !grid.has(key(block.x, block.y, block.z + 1))
    || !grid.has(key(block.x, block.y, block.z - 1))
  );
}

function projectBlock(block, tileW, tileH, blockH) {
  return {
    x: (block.x - block.z) * tileW / 2,
    y: (block.x + block.z) * tileH / 2 - block.y * blockH,
  };
}

function drawCube(image, block, tileW, tileH, blockH, offsetX, offsetY) {
  const projected = projectBlock(block, tileW, tileH, blockH);
  const sx = projected.x + offsetX;
  const sy = projected.y + offsetY;
  const base = blockColor(block.id);
  const top = shade(base, 1.18);
  const left = shade(base, 0.82);
  const right = shade(base, 0.96);
  drawPolygon(image, [
    [sx - tileW / 2, sy + tileH / 2],
    [sx, sy + tileH],
    [sx, sy + tileH + blockH],
    [sx - tileW / 2, sy + tileH / 2 + blockH],
  ], left);
  drawPolygon(image, [
    [sx + tileW / 2, sy + tileH / 2],
    [sx, sy + tileH],
    [sx, sy + tileH + blockH],
    [sx + tileW / 2, sy + tileH / 2 + blockH],
  ], right);
  drawPolygon(image, [
    [sx, sy],
    [sx + tileW / 2, sy + tileH / 2],
    [sx, sy + tileH],
    [sx - tileW / 2, sy + tileH / 2],
  ], top);
}

function newImage(width, height, color) {
  const data = Buffer.alloc(width * height * 3);
  for (let i = 0; i < data.length; i += 3) {
    data[i] = color[0];
    data[i + 1] = color[1];
    data[i + 2] = color[2];
  }
  return { width, height, data };
}

function fillRect(image, x, y, width, height, color) {
  const x1 = Math.max(0, Math.floor(x));
  const y1 = Math.max(0, Math.floor(y));
  const x2 = Math.min(image.width, Math.ceil(x + width));
  const y2 = Math.min(image.height, Math.ceil(y + height));
  for (let py = y1; py < y2; py++) {
    for (let px = x1; px < x2; px++) {
      setPixel(image, px, py, color);
    }
  }
}

function drawPolygon(image, points, color) {
  const minX = Math.max(0, Math.floor(Math.min(...points.map((point) => point[0]))));
  const maxX = Math.min(image.width - 1, Math.ceil(Math.max(...points.map((point) => point[0]))));
  const minY = Math.max(0, Math.floor(Math.min(...points.map((point) => point[1]))));
  const maxY = Math.min(image.height - 1, Math.ceil(Math.max(...points.map((point) => point[1]))));
  for (let y = minY; y <= maxY; y++) {
    for (let x = minX; x <= maxX; x++) {
      if (pointInPolygon(x + 0.5, y + 0.5, points)) {
        setPixel(image, x, y, color);
      }
    }
  }
}

function pointInPolygon(x, y, points) {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const xi = points[i][0];
    const yi = points[i][1];
    const xj = points[j][0];
    const yj = points[j][1];
    const intersects = ((yi > y) !== (yj > y)) && (x < (xj - xi) * (y - yi) / ((yj - yi) || 1) + xi);
    if (intersects) {
      inside = !inside;
    }
  }
  return inside;
}

function setPixel(image, x, y, color) {
  if (x < 0 || y < 0 || x >= image.width || y >= image.height) {
    return;
  }
  const index = (y * image.width + x) * 3;
  image.data[index] = color[0];
  image.data[index + 1] = color[1];
  image.data[index + 2] = color[2];
}

function encodePng(width, height, rgb) {
  const rowBytes = width * 3 + 1;
  const raw = Buffer.alloc(rowBytes * height);
  for (let y = 0; y < height; y++) {
    raw[y * rowBytes] = 0;
    rgb.copy(raw, y * rowBytes + 1, y * width * 3, (y + 1) * width * 3);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk("IHDR", Buffer.concat([u32(width), u32(height), Buffer.from([8, 2, 0, 0, 0])])),
    pngChunk("IDAT", zlib.deflateSync(raw)),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

function pngChunk(type, data) {
  const typeBuffer = Buffer.from(type, "ascii");
  return Buffer.concat([u32(data.length), typeBuffer, data, u32(crc32(Buffer.concat([typeBuffer, data])))]);
}

function u32(value) {
  const buffer = Buffer.alloc(4);
  buffer.writeUInt32BE(value >>> 0, 0);
  return buffer;
}

let CRC_TABLE = null;
function crc32(buffer) {
  if (!CRC_TABLE) {
    CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      }
      return c >>> 0;
    });
  }
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function normalizeBlockId(blockId) {
  const trimmed = String(blockId || "air").trim();
  return trimmed.includes(":") ? trimmed : `minecraft:${trimmed}`;
}

function stringMap(value) {
  const result = {};
  if (!value || typeof value !== "object") {
    return result;
  }
  for (const [key, entry] of Object.entries(value)) {
    if (entry == null || typeof entry === "object") {
      continue;
    }
    if (key !== "mode") {
      result[key] = String(entry);
    }
  }
  return result;
}

function isAir(id) {
  return id === "minecraft:air" || id === "air";
}

function isFluid(id) {
  return id.includes("water") || id.includes("lava");
}

function isPlantLike(id) {
  return /flower|tulip|daisy|dandelion|poppy|grass|fern|sapling|mushroom|roots|crop|wheat|carrot|potato|beetroot|sugar_cane|bamboo|cactus/.test(id);
}

function isPlantSupport(id) {
  return /grass_block|dirt|coarse_dirt|podzol|rooted_dirt|moss_block|farmland|sand|red_sand|mud|mycelium|nylium/.test(id);
}

function isSupportSensitive(id) {
  return /torch|lantern|ladder|rail|button|pressure_plate|lever|vine|chain|carpet/.test(id);
}

function isLikelyAllowedFloating(id) {
  return /leaves|glass|pane|fence|wall|chain|lantern|torch|stairs|slab/.test(id);
}

function blockColor(id) {
  if (isFluid(id)) return id.includes("lava") ? [230, 96, 34] : [64, 128, 222];
  if (id.includes("grass_block")) return [92, 143, 61];
  if (id.includes("dirt") || id.includes("mud")) return [112, 82, 55];
  if (id.includes("sand") || id.includes("birch") || id.includes("end_stone")) return [209, 190, 125];
  if (id.includes("stone") || id.includes("andesite") || id.includes("cobble") || id.includes("tuff")) return [132, 132, 132];
  if (id.includes("deepslate") || id.includes("blackstone") || id.includes("basalt")) return [65, 66, 72];
  if (id.includes("brick") || id.includes("terracotta")) return [157, 83, 65];
  if (id.includes("planks") || id.includes("log") || id.includes("wood") || id.includes("stairs") || id.includes("slab")) return [139, 98, 55];
  if (id.includes("glass")) return [151, 203, 218];
  if (id.includes("leaf") || id.includes("leaves") || id.includes("moss")) return [74, 133, 63];
  if (id.includes("flower") || id.includes("tulip") || id.includes("poppy")) return [218, 80, 96];
  if (id.includes("quartz") || id.includes("white")) return [218, 216, 205];
  if (id.includes("red")) return [166, 60, 52];
  if (id.includes("blue")) return [65, 92, 174];
  if (id.includes("green")) return [74, 135, 78];
  if (id.includes("yellow")) return [205, 177, 64];
  return [155, 145, 125];
}

function shade(color, factor) {
  return color.map((channel) => Math.max(0, Math.min(255, Math.round(channel * factor))));
}

async function completeWithCodex({ prompt, model, effort }) {
  if (!prompt || typeof prompt !== "string") {
    throw new BridgeError(400, "Missing string field: prompt");
  }
  if (!model || typeof model !== "string") {
    throw new BridgeError(400, "Missing string field: model");
  }
  if (!effort || typeof effort !== "string") {
    throw new BridgeError(400, "Missing string field: effort");
  }

  return await withSession(async (session) => {
    const account = await readAccount(session);
    if (account?.requiresOpenaiAuth && !account?.account) {
      throw new BridgeError(401, "Codex is not logged in. Run 'codex login' in a terminal, then restart the Minedit bridge.");
    }

    const models = await listModels(session);
    const modelEntry = findModel(models, model);
    if (!modelEntry) {
      const visible = models.filter((entry) => !entry.hidden).slice(0, 12).map((entry) => entry.model || entry.id).join(", ");
      throw new BridgeError(400, `Codex model '${normalizeCodexModel(model)}' was not found. Try /model gpt-5.5 or use /codex status. Available examples: ${visible || "none returned"}`);
    }

    const supportedEfforts = effortList(modelEntry);
    if (supportedEfforts.length > 0 && !supportedEfforts.includes(effort)) {
      throw new BridgeError(400, `Codex model '${modelEntry.model}' does not support reasoning effort '${effort}'. Supported: ${supportedEfforts.join(", ")}`);
    }

    const modelToUse = modelEntry.model || modelEntry.id;
    const threadResult = await session.request("thread/start", {
      model: modelToUse,
      cwd: process.cwd(),
      ephemeral: true,
      serviceName: "minedit",
      approvalPolicy: "never",
      sandbox: "read-only",
      personality: "pragmatic",
      developerInstructions: "You are a text-only Minecraft builder-code generator for Minedit. Do not inspect files, run commands, or use tools. Return only the requested JavaScript build(api) function or a fenced JavaScript block containing it.",
    }, RPC_TIMEOUT_MS);

    const threadId = threadResult?.thread?.id;
    if (!threadId) {
      throw new BridgeError(502, "Codex app-server did not return a thread id.");
    }

    const deltasByItemId = new Map();
    const completedAgentMessages = new Map();
    session.events.on("notification", (msg) => {
      if (msg.method === "item/agentMessage/delta" && msg.params?.threadId === threadId) {
        const itemId = msg.params.itemId;
        deltasByItemId.set(itemId, (deltasByItemId.get(itemId) || "") + (msg.params.delta || ""));
      }
      if (msg.method === "item/completed" && msg.params?.threadId === threadId) {
        const item = msg.params.item;
        if (item?.type === "agentMessage" && typeof item.text === "string") {
          completedAgentMessages.set(item.id || String(completedAgentMessages.size), item.text);
        }
      }
    });

    const turnResult = await session.request("turn/start", {
      threadId,
      input: [{ type: "text", text: prompt }],
      model: modelToUse,
      effort,
      approvalPolicy: "never",
    }, RPC_TIMEOUT_MS);

    const turnId = turnResult?.turn?.id;
    if (!turnId) {
      throw new BridgeError(502, "Codex app-server did not return a turn id.");
    }

    let completed = null;
    if (turnResult.turn.status === "completed") {
      completed = { params: { turn: turnResult.turn } };
    } else {
      completed = await session.waitForNotification(
        (msg) => msg.method === "turn/completed" && msg.params?.threadId === threadId && msg.params?.turn?.id === turnId,
        REQUEST_TIMEOUT_MS,
      );
    }

    const turn = completed.params.turn;
    if (turn.status === "failed") {
      throw new BridgeError(502, turn.error?.message || "Codex turn failed.", turn.error || null);
    }
    if (turn.status !== "completed") {
      throw new BridgeError(502, `Codex turn ended with status '${turn.status}'.`);
    }

    const text = extractAgentText(turn, completedAgentMessages.size > 0 ? completedAgentMessages : deltasByItemId).trim();
    if (!text) {
      throw new BridgeError(502, "Codex completed but returned no agent message text.");
    }

    return {
      ok: true,
      provider: "codex-app-server",
      model: modelToUse,
      effort,
      text,
    };
  });
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (Buffer.byteLength(body) > MAX_BODY_BYTES) {
        reject(new BridgeError(413, "Request body is too large."));
        req.destroy();
      }
    });
    req.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (error) {
        reject(new BridgeError(400, `Invalid JSON body: ${error.message}`));
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

async function handle(req, res) {
  const url = new URL(req.url || "/", `http://${HOST}:${PORT}`);
  if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
    sendJson(res, 200, { ok: true, service: "minedit-codex-bridge" });
    return;
  }
  if (req.method === "GET" && url.pathname === "/status") {
    sendJson(res, 200, await statusPayload());
    return;
  }
  if (req.method === "POST" && url.pathname === "/complete") {
    const body = await readJsonBody(req);
    sendJson(res, 200, await completeWithCodex(body));
    return;
  }
  if (req.method === "POST" && url.pathname === "/agent-build/start") {
    const body = await readJsonBody(req);
    sendJson(res, 200, startAgentBuild(body));
    return;
  }
  if (req.method === "POST" && url.pathname === "/agent-build/step-by-step/start") {
    const body = await readJsonBody(req);
    sendJson(res, 200, startAgentStepByStepBuild(body));
    return;
  }
  if (req.method === "GET" && url.pathname === "/agent-build/status") {
    const jobId = url.searchParams.get("id") || "";
    const afterSeq = Number(url.searchParams.get("after") || 0);
    const afterBatch = Number(url.searchParams.get("afterBatch") || 0);
    const job = AGENT_JOBS.get(jobId);
    if (!job) {
      throw new BridgeError(404, "Unknown Minedit agent job id.");
    }
    sendJson(res, 200, agentStatusPayload(
      job,
      Number.isFinite(afterSeq) ? afterSeq : 0,
      Number.isFinite(afterBatch) ? afterBatch : 0,
    ));
    return;
  }
  sendJson(res, 404, { ok: false, error: "Not found. Use GET /status, POST /complete, POST /agent-build/start, POST /agent-build/step-by-step/start, or GET /agent-build/status." });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    const status = error instanceof BridgeError ? error.status : 500;
    sendJson(res, status, {
      ok: false,
      error: error.message || "Bridge error.",
      details: error.details || null,
    });
  });
});

server.listen(PORT, HOST, () => {
  console.log(`Minedit Codex bridge listening on http://${HOST}:${PORT}`);
  console.log(`Using Codex binary: ${CODEX_BIN}`);
});
