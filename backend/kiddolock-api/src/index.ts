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
  const path = new URL(c.req.url).pathname;

  // Feedback + OTA version check are exempt - no HMAC needed (parent-facing, read-only/safe)
  if (path === '/api/feedback' || path === '/api/latest-version') {
    return await next();
  }

  const sig = c.req.header('X-Kiddo-Signature') || '';
  const ts = c.req.header('X-Kiddo-Timestamp') || '';

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
    const { deviceId, deviceName, status } = body;

    if (!deviceId) return c.json({ error: 'Missing deviceId' }, 400);

    // Upsert device with auto-detected device name (only set if currently empty)
    if (deviceName && typeof deviceName === 'string') {
      await c.env.DB.prepare(
        `INSERT INTO devices (device_id, device_name, last_heartbeat) VALUES (?, ?, CURRENT_TIMESTAMP)
         ON CONFLICT(device_id) DO UPDATE SET
           last_heartbeat = CURRENT_TIMESTAMP,
           device_name = COALESCE(NULLIF(device_name, ''), excluded.device_name)`
      ).bind(deviceId, deviceName).run();
    } else {
      await c.env.DB.prepare(
        `INSERT INTO devices (device_id, last_heartbeat) VALUES (?, CURRENT_TIMESTAMP)
         ON CONFLICT(device_id) DO UPDATE SET last_heartbeat = CURRENT_TIMESTAMP`
      ).bind(deviceId).run();
    }

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

// ============================================
// 📝 PARENT FEEDBACK
// ============================================
// Stores parent feedback (bug reports + feature requests) with device context + recent logs.
// We use this to prioritize fixes before each release.
app.post('/api/feedback', async (c) => {
  try {
    const body = await c.req.json();
    const { deviceId, text, category, rating, appVersion, device, android, timestamp, appState, recentLogs, screenshot, isAutoReport } = body;

    if (!text || text.length < 2) {
      return c.json({ error: 'Empty feedback text' }, 400);
    }

    // Ensure table exists (idempotent)
    await c.env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS feedback (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT,
        text TEXT NOT NULL,
        category TEXT,
        rating INTEGER,
        app_version TEXT,
        device_info TEXT,
        android_version TEXT,
        app_state TEXT,
        recent_logs TEXT,
        client_timestamp INTEGER,
        received_at TEXT DEFAULT CURRENT_TIMESTAMP
      )`
    ).run();

    // Migration: add screenshot + auto_report columns if they don't exist (idempotent, ignore errors)
    try { await c.env.DB.prepare(`ALTER TABLE feedback ADD COLUMN screenshot TEXT`).run(); } catch (_) {}
    try { await c.env.DB.prepare(`ALTER TABLE feedback ADD COLUMN is_auto_report INTEGER DEFAULT 0`).run(); } catch (_) {}

    // Screenshot guard: cap base64 at ~1.2MB so a huge image can't blow the D1 row limit.
    const safeScreenshot = (screenshot && screenshot.length < 1_200_000) ? screenshot : null;

    await c.env.DB.prepare(
      `INSERT INTO feedback
        (device_id, text, category, rating, app_version, device_info, android_version, app_state, recent_logs, client_timestamp, screenshot, is_auto_report)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      deviceId || 'anon',
      text,
      category || 'general',
      rating || 0,
      appVersion || '?',
      device || '?',
      android || '?',
      appState || '',
      (recentLogs || '').substring(0, 16000),
      timestamp || Date.now(),
      safeScreenshot,
      isAutoReport ? 1 : 0
    ).run();

    // 📧 Send an instant email to the parent via Resend (free tier).
    // Non-blocking best-effort: if email fails, feedback is still safely stored in D1.
    try {
      const resendKey = c.env.RESEND_API_KEY;
      const notifyTo = c.env.FEEDBACK_EMAIL || 'eanidger@gmail.com';
      if (resendKey) {
        const safe = (s: string) => (s || '').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        const headline = isAutoReport ? '🚨 דיווח אוטומטי על תקלה ב-KiddoLock' : '📝 פידבק חדש ב-KiddoLock';
        const subject = isAutoReport
          ? `🚨 דיווח אוטומטי: ${category || 'תקלה'} - KiddoLock`
          : `📝 פידבק חדש ב-KiddoLock (${category || 'כללי'})`;
        const logsBlock = recentLogs
          ? `<hr/><p><b>לוגים אחרונים:</b></p><pre style="background:#f4f4f4;padding:8px;font-size:11px;direction:ltr;text-align:left;overflow:auto;max-height:300px">${safe((recentLogs || '').substring(0, 6000))}</pre>`
          : '';
        const shotBlock = safeScreenshot
          ? `<hr/><p><b>צילום מסך:</b></p><img src="${safeScreenshot.startsWith('data:') ? safeScreenshot : 'data:image/jpeg;base64,' + safeScreenshot}" style="max-width:300px;border:1px solid #ccc"/>`
          : '';
        const emailHtml = `
          <div dir="rtl" style="font-family:Arial,sans-serif;text-align:right">
            <h2 style="color:${isAutoReport ? '#d32f2f' : '#333'}">${headline}</h2>
            <p><b>קטגוריה:</b> ${safe(category || 'כללי')}</p>
            ${isAutoReport ? '' : `<p><b>דירוג:</b> ${rating || '-'}</p>`}
            <p><b>גרסה:</b> ${safe(appVersion || '?')}</p>
            <p><b>מכשיר:</b> ${safe(device || '?')} (Android ${safe(android || '?')})</p>
            <hr/>
            <p style="font-size:16px;white-space:pre-wrap">${safe(text)}</p>
            ${shotBlock}
            ${logsBlock}
            <hr/>
            <p style="color:#888;font-size:12px">דאשבורד מלא: https://kiddolock-api.eanidger.workers.dev/admin/feedback?key=eitan_kiddo_master_2026</p>
          </div>`;
        await fetch('https://api.resend.com/emails', {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${resendKey}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            from: 'KiddoLock <onboarding@resend.dev>',
            to: [notifyTo],
            subject: subject,
            html: emailHtml
          })
        });
      }
    } catch (mailErr) {
      console.log('Resend email failed (feedback still saved):', mailErr);
    }

    return c.json({ ok: true, message: 'Feedback received - thanks!' });
  } catch (e: any) {
    return c.json({ error: e.message }, 500);
  }
});

// ============================================
// 🔄 OTA UPDATE CHECK
// ============================================
// The app polls this to learn whether a newer APK is available. APKs are hosted on
// GitHub Releases (free). Update LATEST_* whenever a new release is published.
const LATEST_VERSION_NAME = '1.5.60';
const LATEST_VERSION_CODE = 70;
const LATEST_APK_URL = 'https://github.com/eanidger-coder/KiddoLock/releases/download/v1.5.60/KiddoLock-v1.5.60.apk';
const LATEST_CHANGELOG = 'תיקון ANR/קפיאה: הוסרו קריאות חוסמות (rootInActiveWindow) מ-thread ראשי. יציבות מלאה.';

app.get('/api/latest-version', (c) => {
  return c.json({
    versionName: LATEST_VERSION_NAME,
    versionCode: LATEST_VERSION_CODE,
    apkUrl: LATEST_APK_URL,
    changelog: LATEST_CHANGELOG,
    mandatory: false
  });
});


// ============================================
// 🚨 EMERGENCY ADMIN PANEL (no HMAC, uses ADMIN_KEY query)
// ============================================
const ADMIN_KEY = 'eitan_kiddo_master_2026';

const checkAdmin = (c: any) => {
  const key = c.req.query('key') || c.req.header('X-Admin-Key');
  if (key !== ADMIN_KEY) {
    return c.text('🔒 Unauthorized', 401);
  }
  return null;
};

// POST /admin/rename/:id - set a friendly device name
app.get('/admin/rename/:id', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  const id = c.req.param('id');
  const newName = c.req.query('name') || '';
  await c.env.DB.prepare(`UPDATE devices SET device_name = ? WHERE device_id = ?`).bind(newName, id).run();
  return c.redirect(`/admin?key=${ADMIN_KEY}`);
});

// GET /admin/feedback - list parent feedback (newest first)
app.get('/admin/feedback', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  try {
    const res = await c.env.DB.prepare(
      `SELECT id, device_id, text, category, rating, app_version, device_info, android_version, app_state, recent_logs, received_at, screenshot, is_auto_report
       FROM feedback ORDER BY id DESC LIMIT 200`
    ).all();
    const rows = res.results || [];
    const html = `<!DOCTYPE html><html dir="rtl" lang="he"><head><meta charset="utf-8">
      <title>KiddoLock Feedback</title>
      <style>
        body{font-family:Arial,sans-serif;background:#0D0B1A;color:#fff;padding:20px;margin:0}
        h1{color:#00E5FF}
        .item{background:#1A1432;padding:14px;margin-bottom:12px;border-radius:8px;border-right:4px solid #00E5FF}
        .meta{font-size:12px;color:#B8B5C8;margin-bottom:8px}
        .text{font-size:16px;line-height:1.5}
        .state{font-size:11px;color:#9B6EFF;margin-top:6px;font-family:monospace}
        details{margin-top:8px}
        details summary{cursor:pointer;color:#FFD600;font-size:12px}
        pre{background:#000;padding:8px;border-radius:4px;font-size:10px;overflow-x:auto;color:#B8B5C8}
        .empty{text-align:center;padding:60px;color:#6B6780}
      </style></head><body>
      <h1>📝 פידבק מהורים (${rows.length} סה"כ)</h1>
      ${rows.length === 0 ? '<div class="empty">עדיין אין פידבק</div>' :
        rows.map((r: any) => `
          <div class="item" style="border-right-color:${r.is_auto_report ? '#FF4757' : '#00E5FF'}">
            <div class="meta">${r.is_auto_report ? '🚨 דיווח אוטומטי | ' : ''}#${r.id} | ${r.received_at} | v${r.app_version} | ${r.device_info} | Android ${r.android_version} | <b style="color:#FFD600">${(r.category || '').replace(/</g, '&lt;')}</b></div>
            <div class="text">${(r.text || '').replace(/</g, '&lt;')}</div>
            <div class="state">${(r.app_state || '').replace(/</g, '&lt;')}</div>
            ${r.screenshot ? `<details><summary>📷 צילום מסך</summary><img src="${(r.screenshot || '').startsWith('data:') ? r.screenshot : 'data:image/jpeg;base64,' + r.screenshot}" style="max-width:280px;border:1px solid #444;border-radius:6px;margin-top:8px"/></details>` : ''}
            ${r.recent_logs ? `<details><summary>📋 לוגים אחרונים</summary><pre>${(r.recent_logs || '').replace(/</g, '&lt;')}</pre></details>` : ''}
          </div>
        `).join('')
      }
      <p style="text-align:center;margin-top:30px"><a href="/admin?key=${ADMIN_KEY}" style="color:#00E5FF">חזרה ללוח הבקרה</a></p>
    </body></html>`;
    return c.html(html);
  } catch (e: any) {
    return c.text(`Error: ${e.message}`, 500);
  }
});

// POST /admin/delete/:id - remove a stale/dead device record from the panel
app.get('/admin/delete/:id', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  const id = c.req.param('id');
  try {
    // Order matters: delete dependent rows first to avoid FK constraint violations
    await c.env.DB.prepare(`DELETE FROM heartbeats WHERE device_id = ?`).bind(id).run();
    await c.env.DB.prepare(`DELETE FROM commands WHERE device_id = ?`).bind(id).run();
    await c.env.DB.prepare(`DELETE FROM kiddolock_configs WHERE device_id = ?`).bind(id).run();
    await c.env.DB.prepare(`DELETE FROM devices WHERE device_id = ?`).bind(id).run();
    return c.redirect(`/admin?key=${ADMIN_KEY}`);
  } catch (e: any) {
    return c.html(`<div style="padding:30px;background:#0d0b1a;color:#fff;font-family:Arial">
      <h2 style="color:#ff6b6b">שגיאה במחיקה</h2>
      <pre style="color:#ffa502">${e.message}</pre>
      <a href="/admin?key=${ADMIN_KEY}" style="color:#00e5ff">חזור</a>
    </div>`);
  }
});

// GET /admin — HTML control panel
app.get('/admin', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  const showAll = c.req.query('show') === 'all';
  // Active = heartbeat within last 7 days. Default view hides ancient/dead devices.
  const sql = showAll
    ? `SELECT d.device_id, d.device_name, d.registered_at, d.last_heartbeat,
              (SELECT status_json FROM heartbeats h WHERE h.device_id = d.device_id ORDER BY h.created_at DESC LIMIT 1) as last_status
       FROM devices d ORDER BY d.last_heartbeat DESC LIMIT 200`
    : `SELECT d.device_id, d.device_name, d.registered_at, d.last_heartbeat,
              (SELECT status_json FROM heartbeats h WHERE h.device_id = d.device_id ORDER BY h.created_at DESC LIMIT 1) as last_status
       FROM devices d
       WHERE d.last_heartbeat >= datetime('now','-7 days') ORDER BY d.last_heartbeat DESC LIMIT 50`;
  const { results: devices } = await c.env.DB.prepare(sql).all();
  const rows = (devices as any[]).map((d) => {
    const idShort = (d.device_id as string).substring(0,8);
    let fp: any = {};
    try {
      const status = JSON.parse(d.last_status || '{}');
      fp = status.fingerprint || {};
    } catch {}
    const ownerLine = (fp.ownerEmail || fp.ownerName)
      ? `<div style="color:#00e676;font-size:13px;margin-top:2px">👤 ${fp.ownerName || ''} ${fp.ownerEmail ? '<span style="color:#aaa">' + fp.ownerEmail + '</span>' : ''}</div>`
      : '<div style="color:#888;font-size:11px;margin-top:2px">👤 בעלים לא ידוע (חסרה הרשאת GET_ACCOUNTS)</div>';
    const phoneDesc = (fp.manufacturer && fp.model)
      ? `<small style="color:#aaa">📱 ${fp.manufacturer} ${fp.model} · Android ${fp.androidVersion || '?'} · ${fp.batteryPercent >= 0 ? fp.batteryPercent + '%🔋' : ''}</small>`
      : '<small style="color:#666">📡 לא קיבל heartbeat עדיין</small>';
    const namePart = d.device_name
      ? `<strong style="color:#00e5ff">${d.device_name}</strong>${ownerLine}<br>${phoneDesc}<br><span style="color:#666;font-size:10px">${idShort}…</span>`
      : `${ownerLine}<br>${phoneDesc}<br><span style="color:#888;font-family:monospace;font-size:11px">${idShort}…</span>`;
    return `
    <tr>
      <td>${namePart}</td>
      <td>${d.last_heartbeat}</td>
      <td>
        <form action="/admin/rename/${d.device_id}" method="get" style="display:inline">
          <input type="hidden" name="key" value="${ADMIN_KEY}">
          <input type="text" name="name" placeholder="שם ידידותי..." value="${d.device_name || ''}" style="background:#0d0b1a;color:#fff;border:1px solid #555;padding:4px 8px;border-radius:4px;width:140px">
          <button type="submit" style="background:#00e5ff;color:#0d0b1a;border:none;padding:4px 10px;border-radius:4px;cursor:pointer;font-weight:bold">שמור</button>
        </form>
      </td>
      <td>
        <a href="/admin/kill-device/${d.device_id}?key=${ADMIN_KEY}" style="color:#ff6b6b">🛑 Kill</a>
        |
        <a href="/admin/disable-kids/${d.device_id}?key=${ADMIN_KEY}" style="color:#ffa502">⏸</a>
        |
        <a href="/admin/uninstall/${d.device_id}?key=${ADMIN_KEY}" style="color:#cc0000">⚠️</a>
        |
        <a href="/admin/delete/${d.device_id}?key=${ADMIN_KEY}" style="color:#666;font-size:11px" title="מחק רשומה">🗑️</a>
      </td>
    </tr>`;
  }).join('');
  const filterLink = showAll
    ? `<a href="/admin?key=${ADMIN_KEY}" style="color:#00e5ff">📱 הצג רק פעילים (7 ימים אחרונים)</a>`
    : `<a href="/admin?key=${ADMIN_KEY}&show=all" style="color:#888">🗂️ הצג את כל ההיסטוריה</a>`;
  const titleSuffix = showAll ? '(כל ההיסטוריה)' : '(פעילים בלבד, 7 ימים אחרונים)';
  const html = `<!DOCTYPE html>
<html dir="rtl" lang="he"><head><meta charset="utf-8"><title>KiddoLock Emergency Admin</title>
<style>
body{font-family:Arial,sans-serif;background:#0d0b1a;color:#fff;padding:20px;direction:rtl}
h1{color:#00e5ff}
.btn{display:inline-block;padding:14px 22px;margin:8px;border-radius:10px;color:#fff;text-decoration:none;font-weight:bold;font-size:15px}
.kill{background:#FF1744}
.disable{background:#FFA502}
.uninstall{background:#cc0000}
table{width:100%;border-collapse:collapse;margin-top:20px;background:#1a1432;border-radius:8px;overflow:hidden}
th,td{padding:10px;border-bottom:1px solid #333;text-align:right}
th{background:#2b1d52}
a{text-decoration:none}
.warn{background:#2b1d52;padding:14px;border-radius:8px;border:1px solid #ffa502;margin:15px 0}
</style></head><body>
<h1>🛡️ KiddoLock Emergency Control Panel</h1>
<div class="warn">⚠️ הכפתורים האדומים שולחים פקודות חירום לכל המכשירים במסד הנתונים. השתמש בזהירות.</div>
<a class="btn kill" href="/admin/kill-all?key=${ADMIN_KEY}">🚨 KILL ALL DEVICES (Emergency Bypass + Open App)</a>
<a class="btn disable" href="/admin/disable-kids-all?key=${ADMIN_KEY}">⏸️ DISABLE KIDS MODE on all</a>
<a class="btn uninstall" href="/admin/uninstall-all?key=${ADMIN_KEY}">⚠️ FORCE UNINSTALL all</a>
<h2>📱 מכשירים ${titleSuffix} (${(devices as any[]).length})</h2>
<p>${filterLink}</p>
<table><thead><tr><th>📱 שם / Device</th><th>פעילות אחרונה</th><th>שם ידידותי</th><th>פעולות</th></tr></thead>
<tbody>${rows}</tbody></table>
<p style="margin-top:30px;color:#888;font-size:12px">🔄 רענן: <a href="/admin?key=${ADMIN_KEY}" style="color:#00e5ff">/admin</a></p>
</body></html>`;
  return c.html(html);
});

// Helper: queue command for one or all devices
async function queueCommand(c: any, deviceId: string | null, commandType: string) {
  if (deviceId) {
    await c.env.DB.prepare(
      `INSERT INTO commands (device_id, command_type, payload) VALUES (?, ?, '')`
    ).bind(deviceId, commandType).run();
    return 1;
  } else {
    const r = await c.env.DB.prepare(
      `INSERT INTO commands (device_id, command_type, payload)
       SELECT device_id, ?, '' FROM devices`
    ).bind(commandType).run();
    return (r as any).meta?.changes || 0;
  }
}

// One-device commands
app.get('/admin/kill-device/:id', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  const id = c.req.param('id');
  await queueCommand(c, id, 'EMERGENCY_KILL_SWITCH');
  // Look up last heartbeat to give the user honest feedback about whether the device is reachable
  const dev: any = await c.env.DB.prepare(
    `SELECT device_name, last_heartbeat FROM devices WHERE device_id = ?`
  ).bind(id).first();
  const lastHB = dev?.last_heartbeat || 'מעולם לא';
  const isFresh = dev?.last_heartbeat && (new Date(dev.last_heartbeat as any).getTime() > Date.now() - 30 * 60 * 1000);
  const liveBadge = isFresh
    ? '<div style="background:#00c853;padding:8px 12px;border-radius:6px;margin:10px 0">✅ המכשיר פעיל - הפקודה תתבצע תוך 15 דקות.</div>'
    : '<div style="background:#FFA502;padding:8px 12px;border-radius:6px;margin:10px 0">⚠️ המכשיר לא דיווח לאחרונה. הפקודה ממתינה ותתבצע ברגע שהוא יחזור לאינטרנט. אם הוא במצב טיסה או כבוי, היא תחכה.</div>';
  return c.html(`<div style="padding:30px;background:#0d0b1a;color:#fff;font-family:Arial;direction:rtl">
    <h2 style="color:#0f0">✅ פקודת KILL נשלחה</h2>
    <p>מכשיר: <strong>${dev?.device_name || id.substring(0,8) + '...'}</strong></p>
    <p>Heartbeat אחרון: ${lastHB}</p>
    ${liveBadge}
    <p style="margin-top:20px"><a href="/admin?key=${ADMIN_KEY}" style="background:#00e5ff;color:#0d0b1a;padding:10px 20px;border-radius:6px;text-decoration:none;font-weight:bold">חזור לדשבורד</a></p>
  </div>`);
});
app.get('/admin/disable-kids/:id', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  await queueCommand(c, c.req.param('id'), 'DISABLE_PROTECTION');
  return c.html(`<p style="font-family:Arial;padding:20px;background:#0d0b1a;color:#0f0">✅ Disable Kids Mode queued. <a href="/admin?key=${ADMIN_KEY}" style="color:#00e5ff">חזור</a></p>`);
});
app.get('/admin/uninstall/:id', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  await queueCommand(c, c.req.param('id'), 'EMERGENCY_KILL_SWITCH');
  await queueCommand(c, c.req.param('id'), 'DISABLE_PROTECTION');
  return c.html(`<p style="font-family:Arial;padding:20px;background:#0d0b1a;color:#0f0">✅ Uninstall sequence queued. <a href="/admin?key=${ADMIN_KEY}" style="color:#00e5ff">חזור</a></p>`);
});

// All-devices commands
app.get('/admin/kill-all', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  const n = await queueCommand(c, null, 'EMERGENCY_KILL_SWITCH');
  const fresh: any = await c.env.DB.prepare(
    `SELECT COUNT(*) as cnt FROM devices WHERE last_heartbeat >= datetime('now','-30 minutes')`
  ).first();
  const freshCnt = fresh?.cnt || 0;
  return c.html(`<div style="padding:30px;background:#0d0b1a;color:#fff;font-family:Arial;direction:rtl">
    <h2 style="color:#0f0">✅ KILL ALL נשלח ל-${n} מכשירים</h2>
    <div style="background:${freshCnt > 0 ? '#00c853' : '#FFA502'};padding:10px;border-radius:6px;margin:15px 0">
      ${freshCnt > 0 
        ? `✅ ${freshCnt} מכשירים פעילים בחצי שעה האחרונה - יקבלו את הפקודה תוך 15 דקות.`
        : '⚠️ אין מכשירים פעילים כעת. הפקודות ממתינות בתור ויתבצעו ברגע שמכשיר יחזור לאינטרנט.'}
    </div>
    <p style="margin-top:20px"><a href="/admin?key=${ADMIN_KEY}" style="background:#00e5ff;color:#0d0b1a;padding:10px 20px;border-radius:6px;text-decoration:none;font-weight:bold">חזור לדשבורד</a></p>
  </div>`);
});
app.get('/admin/disable-kids-all', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  const n = await queueCommand(c, null, 'DISABLE_PROTECTION');
  return c.html(`<p style="font-family:Arial;padding:20px;background:#0d0b1a;color:#0f0">✅ Disable Kids Mode queued for ${n} devices. <a href="/admin?key=${ADMIN_KEY}" style="color:#00e5ff">חזור</a></p>`);
});
app.get('/admin/uninstall-all', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  await queueCommand(c, null, 'EMERGENCY_KILL_SWITCH');
  const n = await queueCommand(c, null, 'DISABLE_PROTECTION');
  return c.html(`<p style="font-family:Arial;padding:20px;background:#0d0b1a;color:#0f0">✅ Uninstall sequence queued for ${n} devices. <a href="/admin?key=${ADMIN_KEY}" style="color:#00e5ff">חזור</a></p>`);
});

// Quick health endpoint that returns whether there are pending kill commands - useful for emergency status
app.get('/admin/status', async (c) => {
  const auth = checkAdmin(c); if (auth) return auth;
  const { results } = await c.env.DB.prepare(
    `SELECT command_type, COUNT(*) as n FROM commands WHERE status='pending' GROUP BY command_type`
  ).all();
  return c.json({ pending: results });
});


export default app;
