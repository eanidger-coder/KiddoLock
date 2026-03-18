import { Hono } from 'hono';
import { cors } from 'hono/cors';

type Bindings = {
  DB: D1Database;
  HMAC_SECRET: string;
};

const app = new Hono<{ Bindings: Bindings }>();

// CORS
app.use('*', cors());

// ============================================
// HMAC Signature Middleware
// ============================================
async function verifySignature(
  timestamp: string,
  path: string,
  signature: string,
  secret: string
): Promise<boolean> {
  const encoder = new TextEncoder();
  const data = `${timestamp}:${path}`;
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, encoder.encode(data));
  const hex = Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
  return hex === signature;
}

const signatureMiddleware = async (c: any, next: any) => {
  const sig = c.req.header('X-Kiddo-Signature') || '';
  const ts = c.req.header('X-Kiddo-Timestamp') || '';
  const path = new URL(c.req.url).pathname;

  if (!sig || !ts) {
    return c.json({ error: 'Missing signature headers' }, 401);
  }

  // Reject if timestamp is older than 5 minutes
  const now = Date.now();
  const reqTime = parseInt(ts, 10);
  if (isNaN(reqTime) || Math.abs(now - reqTime) > 5 * 60 * 1000) {
    return c.json({ error: 'Request expired' }, 401);
  }

  const valid = await verifySignature(ts, path, sig, c.env.HMAC_SECRET);
  if (!valid) {
    return c.json({ error: 'Invalid signature' }, 403);
  }

  await next();
};

// Apply signature verification to all API routes
app.use('/api/*', signatureMiddleware);

// ============================================
// HEALTH CHECK (no auth)
// ============================================
app.get('/', (c) => c.json({ status: 'ok', service: 'kiddolock-api', version: '1.0.0' }));

// ============================================
// HEARTBEAT
// ============================================
app.post('/api/heartbeat', async (c) => {
  try {
    const body = await c.req.json();
    const { deviceId, status } = body;

    if (!deviceId) return c.json({ error: 'Missing deviceId' }, 400);

    // Upsert device
    await c.env.DB.prepare(
      `INSERT INTO devices (device_id, last_heartbeat) VALUES (?, CURRENT_TIMESTAMP)
       ON CONFLICT(device_id) DO UPDATE SET last_heartbeat = CURRENT_TIMESTAMP`
    ).bind(deviceId).run();

    // Log heartbeat
    await c.env.DB.prepare(
      `INSERT INTO heartbeats (device_id, status_json) VALUES (?, ?)`
    ).bind(deviceId, JSON.stringify(status || {})).run();

    // Fetch pending commands
    const { results: commands } = await c.env.DB.prepare(
      `SELECT id, command_type, payload FROM commands
       WHERE device_id = ? AND status = 'pending' ORDER BY created_at ASC LIMIT 10`
    ).bind(deviceId).all();

    // Mark fetched commands as delivered
    if (commands && commands.length > 0) {
      const ids = commands.map((cmd: any) => cmd.id);
      await c.env.DB.prepare(
        `UPDATE commands SET status = 'delivered', executed_at = CURRENT_TIMESTAMP
         WHERE id IN (${ids.map(() => '?').join(',')})`
      ).bind(...ids).run();
    }

    return c.json({ ok: true, commands: commands || [] });
  } catch (e: any) {
    return c.json({ error: e.message }, 500);
  }
});

// ============================================
// KIDDOLOCK SYNC API
// ============================================

// POST /api/sync — upload config from device
app.post('/api/sync', async (c) => {
  try {
    const body = await c.req.json();
    const { deviceId, pinHash, config } = body;

    if (!deviceId) return c.json({ error: 'Missing deviceId' }, 400);

    // Upsert device
    await c.env.DB.prepare(
      `INSERT INTO devices (device_id) VALUES (?)
       ON CONFLICT(device_id) DO UPDATE SET last_heartbeat = CURRENT_TIMESTAMP`
    ).bind(deviceId).run();

    // Upsert config
    await c.env.DB.prepare(
      `INSERT INTO kiddolock_configs (device_id, pin_hash, config_json, last_updated)
       VALUES (?, ?, ?, CURRENT_TIMESTAMP)
       ON CONFLICT(device_id) DO UPDATE SET
         pin_hash = excluded.pin_hash,
         config_json = excluded.config_json,
         last_updated = CURRENT_TIMESTAMP`
    ).bind(deviceId, pinHash || '', JSON.stringify(config || {})).run();

    return c.json({ ok: true, message: 'Config synced successfully' });
  } catch (e: any) {
    return c.json({ error: e.message }, 500);
  }
});

// GET /api/sync/:deviceId — download config to device
app.get('/api/sync/:deviceId', async (c) => {
  try {
    const deviceId = c.req.param('deviceId');

    const row = await c.env.DB.prepare(
      `SELECT pin_hash, config_json, last_updated FROM kiddolock_configs WHERE device_id = ?`
    ).bind(deviceId).first();

    if (!row) {
      return c.json({ found: false, message: 'No config found for this device' }, 404);
    }

    return c.json({
      found: true,
      pinHash: row.pin_hash,
      config: JSON.parse(row.config_json as string || '{}'),
      lastUpdated: row.last_updated,
    });
  } catch (e: any) {
    return c.json({ error: e.message }, 500);
  }
});

// POST /api/command — queue a remote command for a device
app.post('/api/command', async (c) => {
  try {
    const body = await c.req.json();
    const { deviceId, commandType, payload } = body;

    if (!deviceId || !commandType) {
      return c.json({ error: 'Missing deviceId or commandType' }, 400);
    }

    await c.env.DB.prepare(
      `INSERT INTO commands (device_id, command_type, payload) VALUES (?, ?, ?)`
    ).bind(deviceId, commandType, payload || '').run();

    return c.json({ ok: true, message: 'Command queued' });
  } catch (e: any) {
    return c.json({ error: e.message }, 500);
  }
});

export default app;
