import express from "express";
import cors from "cors";
import helmet from "helmet";
import rateLimit from "express-rate-limit";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import crypto from "node:crypto";
import { Pool } from "pg";
import fs from "node:fs/promises";
import path from "node:path";

const app = express();
const PORT = Number(process.env.PORT || 10000);
const JWT_SECRET = process.env.JWT_SECRET;
const DATABASE_URL = process.env.DATABASE_URL;

if (!JWT_SECRET) {
  console.error("JWT_SECRET is required");
  process.exit(1);
}
if (!DATABASE_URL) {
  console.error("DATABASE_URL is required");
  process.exit(1);
}

const pool = new Pool({
  connectionString: DATABASE_URL,
  ssl: process.env.PGSSLMODE === "disable" ? false : { rejectUnauthorized: false }
});

app.set("trust proxy", 1);
app.use(helmet({ crossOriginResourcePolicy: false }));
app.use(cors({
  origin: true,
  credentials: true
}));
app.use(express.json({ limit: "512kb" }));

const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 80,
  standardHeaders: true,
  legacyHeaders: false
});

const aiLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false
});

const publicDir = path.join(process.cwd(), "public");

function nowIso() {
  return new Date().toISOString();
}

function normalizeEmail(value) {
  return String(value || "").trim().toLowerCase();
}

function safeUser(user) {
  return { id: user.id, email: user.email, createdAt: user.created_at };
}

function issueToken(user) {
  return jwt.sign(
    { sub: user.id, email: user.email },
    JWT_SECRET,
    { expiresIn: process.env.JWT_EXPIRES_IN || "30d" }
  );
}

function authRequired(req, res, next) {
  const raw = req.headers.authorization || "";
  const token = raw.startsWith("Bearer ") ? raw.slice(7) : "";
  if (!token) return res.status(401).json({ error: "missing_token" });
  try {
    req.auth = jwt.verify(token, JWT_SECRET);
    next();
  } catch {
    return res.status(401).json({ error: "invalid_token" });
  }
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function sanitizeAlarmPayload(payload) {
  if (!isObject(payload)) throw new Error("alarm_payload_must_be_object");
  const out = { ...payload };

  if (typeof out.id !== "number") delete out.id;
  if (typeof out.hour === "number") out.hour = Math.max(0, Math.min(23, Math.trunc(out.hour)));
  if (typeof out.minute === "number") out.minute = Math.max(0, Math.min(59, Math.trunc(out.minute)));
  if (typeof out.label === "string") out.label = out.label.slice(0, 120);
  if (typeof out.group === "string") out.group = out.group.slice(0, 40);
  if (typeof out.profileName === "string") out.profileName = out.profileName.slice(0, 40);
  if (typeof out.volume === "number") out.volume = Math.max(0, Math.min(100, Math.trunc(out.volume)));
  if (typeof out.snoozeDurationMinutes === "number") {
    out.snoozeDurationMinutes = Math.max(1, Math.min(180, Math.trunc(out.snoozeDurationMinutes)));
  }
  if (typeof out.maxSnoozeCount === "number") {
    out.maxSnoozeCount = Math.max(0, Math.min(20, Math.trunc(out.maxSnoozeCount)));
  }
  if (typeof out.repeatDays === "string") {
    out.repeatDays = out.repeatDays.split(",").map(s => s.trim()).filter(Boolean);
  }
  const validDays = new Set(["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"]);
  out.repeatDays = Array.isArray(out.repeatDays)
    ? [...new Set(out.repeatDays.map(x => String(x).toUpperCase()).filter(x => validDays.has(x)))]
    : [];
  out.isEnabled = Boolean(out.isEnabled);
  return out;
}

async function audit(userId, action, entityType, entityId, source, detail = {}) {
  await pool.query(
    "INSERT INTO audit_log(user_id, action, entity_type, entity_id, source, detail) VALUES($1,$2,$3,$4,$5,$6)",
    [userId, action, entityType, entityId || null, source, JSON.stringify(detail)]
  );
}

async function bootstrap() {
  const schema = await fs.readFile(path.join(process.cwd(), "schema.sql"), "utf8");
  await pool.query(schema);
  await pool.query("SELECT 1");
  console.log("database ready");
}

app.get("/api/health", async (_req, res) => {
  try {
    await pool.query("SELECT 1");
    res.json({ ok: true, service: "alarmclockxtreme-cloud", time: nowIso() });
  } catch {
    res.status(503).json({ ok: false });
  }
});

app.post("/api/auth/register", authLimiter, async (req, res) => {
  try {
    const email = normalizeEmail(req.body?.email);
    const password = String(req.body?.password || "");
    if (!/^\S+@\S+\.\S+$/.test(email)) return res.status(400).json({ error: "invalid_email" });
    if (password.length < 8 || password.length > 128) return res.status(400).json({ error: "password_length" });

    const exists = await pool.query("SELECT id FROM users WHERE email=$1", [email]);
    if (exists.rowCount) return res.status(409).json({ error: "email_exists" });

    const id = crypto.randomUUID();
    const hash = await bcrypt.hash(password, 12);
    const result = await pool.query(
      "INSERT INTO users(id,email,password_hash) VALUES($1,$2,$3) RETURNING id,email,created_at",
      [id, email, hash]
    );
    const user = result.rows[0];
    await audit(user.id, "register", "user", user.id, "web");
    res.status(201).json({ token: issueToken(user), user: safeUser(user) });
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: "register_failed" });
  }
});

app.post("/api/auth/login", authLimiter, async (req, res) => {
  try {
    const email = normalizeEmail(req.body?.email);
    const password = String(req.body?.password || "");
    const result = await pool.query("SELECT id,email,password_hash,created_at FROM users WHERE email=$1", [email]);
    const user = result.rows[0];
    if (!user || !(await bcrypt.compare(password, user.password_hash))) {
      return res.status(401).json({ error: "invalid_credentials" });
    }
    await audit(user.id, "login", "user", user.id, "web_or_android");
    res.json({ token: issueToken(user), user: safeUser(user) });
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: "login_failed" });
  }
});

app.get("/api/me", authRequired, async (req, res) => {
  const result = await pool.query("SELECT id,email,created_at FROM users WHERE id=$1", [req.auth.sub]);
  if (!result.rowCount) return res.status(401).json({ error: "user_not_found" });
  res.json({ user: safeUser(result.rows[0]) });
});

app.post("/api/devices/register", authRequired, async (req, res) => {
  const deviceId = String(req.body?.deviceId || "").trim();
  if (!deviceId || deviceId.length > 128) return res.status(400).json({ error: "invalid_device_id" });
  const platform = String(req.body?.platform || "android").slice(0, 32);
  const appVersion = String(req.body?.appVersion || "").slice(0, 64);
  const pushToken = String(req.body?.pushToken || "").slice(0, 4096);

  await pool.query(
    `INSERT INTO devices(id,user_id,platform,app_version,push_token,last_seen_at)
     VALUES($1,$2,$3,$4,$5,NOW())
     ON CONFLICT(id) DO UPDATE SET user_id=EXCLUDED.user_id,platform=EXCLUDED.platform,
       app_version=EXCLUDED.app_version,push_token=EXCLUDED.push_token,last_seen_at=NOW()`,
    [deviceId, req.auth.sub, platform, appVersion, pushToken]
  );
  res.json({ ok: true });
});

app.get("/api/alarms", authRequired, async (req, res) => {
  try {
    const sinceRaw = String(req.query.since || "").trim();
    const since = sinceRaw ? new Date(sinceRaw) : new Date(0);
    if (Number.isNaN(since.getTime())) return res.status(400).json({ error: "invalid_since" });

    const result = await pool.query(
      `SELECT id,payload,version,updated_at,deleted_at
       FROM alarms
       WHERE user_id=$1 AND updated_at>$2
       ORDER BY updated_at ASC
       LIMIT 1000`,
      [req.auth.sub, since.toISOString()]
    );

    const cursor = result.rows.length
      ? result.rows[result.rows.length - 1].updated_at.toISOString()
      : since.toISOString();

    res.json({
      alarms: result.rows.map(r => ({
        id: r.id,
        payload: r.payload,
        version: Number(r.version),
        updatedAt: r.updated_at.toISOString(),
        deletedAt: r.deleted_at ? r.deleted_at.toISOString() : null
      })),
      cursor
    });
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: "alarm_list_failed" });
  }
});

app.put("/api/alarms/:id", authRequired, async (req, res) => {
  try {
    const id = String(req.params.id || "").trim();
    if (!/^[a-f0-9-]{8,128}$/i.test(id)) return res.status(400).json({ error: "invalid_alarm_id" });

    const payload = sanitizeAlarmPayload(req.body?.payload);
    const expectedVersion = Number(req.body?.expectedVersion || 0);

    const current = await pool.query(
      "SELECT version,updated_at FROM alarms WHERE id=$1 AND user_id=$2",
      [id, req.auth.sub]
    );

    if (current.rowCount && expectedVersion && Number(current.rows[0].version) !== expectedVersion) {
      return res.status(409).json({
        error: "version_conflict",
        currentVersion: Number(current.rows[0].version),
        currentUpdatedAt: current.rows[0].updated_at.toISOString()
      });
    }

    const nextVersion = current.rowCount ? Number(current.rows[0].version) + 1 : 1;
    const result = await pool.query(
      `INSERT INTO alarms(id,user_id,payload,version,updated_at,deleted_at)
       VALUES($1,$2,$3,$4,NOW(),NULL)
       ON CONFLICT(id) DO UPDATE SET payload=EXCLUDED.payload,version=EXCLUDED.version,
         updated_at=NOW(),deleted_at=NULL
       RETURNING id,payload,version,updated_at,deleted_at`,
      [id, req.auth.sub, JSON.stringify(payload), nextVersion]
    );

    const row = result.rows[0];
    await audit(req.auth.sub, current.rowCount ? "update" : "create", "alarm", id, "api", { version: nextVersion });
    res.json({
      id: row.id,
      payload: row.payload,
      version: Number(row.version),
      updatedAt: row.updated_at.toISOString(),
      deletedAt: null
    });
  } catch (error) {
    console.error(error);
    res.status(400).json({ error: error.message === "alarm_payload_must_be_object" ? error.message : "alarm_write_failed" });
  }
});

app.delete("/api/alarms/:id", authRequired, async (req, res) => {
  const id = String(req.params.id || "").trim();
  const current = await pool.query(
    "SELECT version FROM alarms WHERE id=$1 AND user_id=$2",
    [id, req.auth.sub]
  );
  if (!current.rowCount) return res.status(404).json({ error: "alarm_not_found" });

  const nextVersion = Number(current.rows[0].version) + 1;
  const result = await pool.query(
    `UPDATE alarms SET version=$3,updated_at=NOW(),deleted_at=NOW()
     WHERE id=$1 AND user_id=$2
     RETURNING id,version,updated_at,deleted_at`,
    [id, req.auth.sub, nextVersion]
  );
  const row = result.rows[0];
  await audit(req.auth.sub, "delete", "alarm", id, "api", { version: nextVersion });
  res.json({
    id: row.id,
    version: Number(row.version),
    updatedAt: row.updated_at.toISOString(),
    deletedAt: row.deleted_at.toISOString()
  });
});

app.post("/api/alarms/batch", authRequired, async (req, res) => {
  const items = Array.isArray(req.body?.alarms) ? req.body.alarms : [];
  if (items.length > 100) return res.status(400).json({ error: "batch_too_large" });

  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const results = [];

    for (const item of items) {
      const id = String(item?.id || "").trim();
      if (!/^[a-f0-9-]{8,128}$/i.test(id)) continue;
      const payload = sanitizeAlarmPayload(item.payload);
      const current = await client.query(
        "SELECT version FROM alarms WHERE id=$1 AND user_id=$2",
        [id, req.auth.sub]
      );
      const expectedVersion = Number(item?.expectedVersion || 0);
      if (current.rowCount && expectedVersion && Number(current.rows[0].version) !== expectedVersion) {
        await client.query("ROLLBACK");
        return res.status(409).json({ error: "version_conflict", alarmId: id });
      }
      const nextVersion = current.rowCount ? Number(current.rows[0].version) + 1 : 1;
      const result = await client.query(
        `INSERT INTO alarms(id,user_id,payload,version,updated_at,deleted_at)
         VALUES($1,$2,$3,$4,NOW(),NULL)
         ON CONFLICT(id) DO UPDATE SET payload=EXCLUDED.payload,version=EXCLUDED.version,
           updated_at=NOW(),deleted_at=NULL
         RETURNING id,payload,version,updated_at,deleted_at`,
        [id, req.auth.sub, JSON.stringify(payload), nextVersion]
      );
      const row = result.rows[0];
      results.push({
        id: row.id,
        payload: row.payload,
        version: Number(row.version),
        updatedAt: row.updated_at.toISOString(),
        deletedAt: null
      });
    }

    await client.query("COMMIT");
    await audit(req.auth.sub, "batch_upsert", "alarm", null, "sync", { count: results.length });
    res.json({ alarms: results });
  } catch (error) {
    await client.query("ROLLBACK");
    console.error(error);
    res.status(400).json({ error: "batch_failed" });
  } finally {
    client.release();
  }
});

function normalizeAiJson(raw) {
  try {
    const text = typeof raw === "string" ? raw : JSON.stringify(raw);
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function callAi(messages, tools) {
  const key = process.env.AI_API_KEY;
  if (!key) return null;
  const base = (process.env.AI_BASE_URL || "https://api.openai.com/v1").replace(/\/$/, "");
  const model = process.env.AI_MODEL || "gpt-4o-mini";

  const response = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "authorization": `Bearer ${key}`
    },
    body: JSON.stringify({
      model,
      temperature: 0.1,
      messages,
      tools
    })
  });

  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`AI provider failed: ${response.status} ${detail.slice(0, 500)}`);
  }
  return response.json();
}

const alarmTools = [
  {
    type: "function",
    function: {
      name: "list_alarms",
      description: "List the user's cloud alarms before making edits when an alarm must be found by label/time.",
      parameters: { type: "object", properties: {}, additionalProperties: false }
    }
  },
  {
    type: "function",
    function: {
      name: "create_alarm",
      description: "Create a new alarm. Use the Android Alarm payload shape. Preserve any supplied advanced fields.",
      parameters: {
        type: "object",
        properties: {
          alarm: { type: "object", additionalProperties: true }
        },
        required: ["alarm"],
        additionalProperties: false
      }
    }
  },
  {
    type: "function",
    function: {
      name: "update_alarm",
      description: "Update one alarm by cloud id. patch may contain any Alarm model fields. Use list_alarms first if id is unknown.",
      parameters: {
        type: "object",
        properties: {
          id: { type: "string" },
          patch: { type: "object", additionalProperties: true }
        },
        required: ["id", "patch"],
        additionalProperties: false
      }
    }
  },
  {
    type: "function",
    function: {
      name: "delete_alarm",
      description: "Delete one alarm by cloud id. Use list_alarms first if id is unknown.",
      parameters: {
        type: "object",
        properties: {
          id: { type: "string" }
        },
        required: ["id"],
        additionalProperties: false
      }
    }
  }
];

async function executeAiTool(userId, name, args) {
  if (name === "list_alarms") {
    const r = await pool.query(
      "SELECT id,payload,version,updated_at,deleted_at FROM alarms WHERE user_id=$1 AND deleted_at IS NULL ORDER BY updated_at DESC",
      [userId]
    );
    return r.rows.map(x => ({
      id: x.id,
      version: Number(x.version),
      updatedAt: x.updated_at.toISOString(),
      payload: x.payload
    }));
  }

  if (name === "create_alarm") {
    const payload = sanitizeAlarmPayload(args.alarm);
    const id = crypto.randomUUID();
    const result = await pool.query(
      "INSERT INTO alarms(id,user_id,payload,version) VALUES($1,$2,$3,1) RETURNING id,payload,version,updated_at",
      [id, userId, JSON.stringify(payload)]
    );
    await audit(userId, "create", "alarm", id, "ai");
    const row = result.rows[0];
    return { id: row.id, payload: row.payload, version: 1, updatedAt: row.updated_at.toISOString() };
  }

  const id = String(args.id || "");
  const current = await pool.query(
    "SELECT id,payload,version FROM alarms WHERE id=$1 AND user_id=$2 AND deleted_at IS NULL",
    [id, userId]
  );
  if (!current.rowCount) throw new Error("alarm_not_found");

  if (name === "update_alarm") {
    const merged = sanitizeAlarmPayload({ ...current.rows[0].payload, ...args.patch });
    const nextVersion = Number(current.rows[0].version) + 1;
    const result = await pool.query(
      "UPDATE alarms SET payload=$3,version=$4,updated_at=NOW(),deleted_at=NULL WHERE id=$1 AND user_id=$2 RETURNING id,payload,version,updated_at",
      [id, userId, JSON.stringify(merged), nextVersion]
    );
    await audit(userId, "update", "alarm", id, "ai", { patchKeys: Object.keys(args.patch || {}) });
    const row = result.rows[0];
    return { id: row.id, payload: row.payload, version: Number(row.version), updatedAt: row.updated_at.toISOString() };
  }

  if (name === "delete_alarm") {
    const nextVersion = Number(current.rows[0].version) + 1;
    const result = await pool.query(
      "UPDATE alarms SET version=$3,updated_at=NOW(),deleted_at=NOW() WHERE id=$1 AND user_id=$2 RETURNING id,version,updated_at,deleted_at",
      [id, userId, nextVersion]
    );
    await audit(userId, "delete", "alarm", id, "ai");
    const row = result.rows[0];
    return { id: row.id, version: Number(row.version), deletedAt: row.deleted_at.toISOString() };
  }

  throw new Error("unknown_tool");
}

app.post("/api/ai/command", authRequired, aiLimiter, async (req, res) => {
  const command = String(req.body?.command || "").trim();
  if (!command || command.length > 2000) return res.status(400).json({ error: "invalid_command" });

  const system = `You are the AlarmClockXtreme alarm assistant. You control ONLY this authenticated user's alarms.
You may create, update, delete, or list alarms using the provided tools.
Never invent an alarm id; list first when necessary.
For create/update, use valid Android Alarm model property names. Use 24-hour hour/minute.
Keep unrelated advanced fields unchanged when updating.
After tool execution, summarize exactly what changed in friendly language.`;

  try {
    if (!process.env.AI_API_KEY) {
      return res.status(200).json({
        mode: "fallback",
        message: "AI provider is not configured on the server. Set AI_API_KEY, AI_BASE_URL and AI_MODEL in Render environment variables."
      });
    }

    const messages = [
      { role: "system", content: system },
      { role: "user", content: command }
    ];

    let finalText = "";
    let executed = [];
    for (let round = 0; round < 4; round++) {
      const response = await callAi(messages, alarmTools);
      const choice = response?.choices?.[0];
      const message = choice?.message;
      if (!message) throw new Error("invalid_ai_response");

      if (message.content) finalText = message.content;
      if (!message.tool_calls?.length) break;

      messages.push(message);
      for (const toolCall of message.tool_calls) {
        const args = normalizeAiJson(toolCall.function?.arguments) || {};
        const result = await executeAiTool(req.auth.sub, toolCall.function.name, args);
        executed.push({ tool: toolCall.function.name, result });
        messages.push({
          role: "tool",
          tool_call_id: toolCall.id,
          content: JSON.stringify(result)
        });
      }
    }

    res.json({ mode: "ai", message: finalText || "Done.", executed });
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: "ai_command_failed", detail: error.message });
  }
});

app.get("/api/audit", authRequired, async (req, res) => {
  const result = await pool.query(
    "SELECT action,entity_type,entity_id,source,detail,created_at FROM audit_log WHERE user_id=$1 ORDER BY created_at DESC LIMIT 100",
    [req.auth.sub]
  );
  res.json({
    events: result.rows.map(r => ({
      action: r.action,
      entityType: r.entity_type,
      entityId: r.entity_id,
      source: r.source,
      detail: r.detail,
      createdAt: r.created_at.toISOString()
    }))
  });
});

app.use(express.static(publicDir, { extensions: ["html"] }));
app.get("/{*splat}", (_req, res) => res.sendFile(path.join(publicDir, "index.html")));

bootstrap()
  .then(() => {
    app.listen(PORT, "0.0.0.0", () => console.log(`AlarmClockXtreme Cloud listening on ${PORT}`));
  })
  .catch(error => {
    console.error("startup failed", error);
    process.exit(1);
  });