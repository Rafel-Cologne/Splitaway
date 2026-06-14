// ============================================================
// Splitaway — Node.js Server
// Обрабатывает push-уведомления через web-push
// ============================================================

require('dotenv').config();
const express = require('express');
const cors    = require('cors');
const webpush = require('web-push');
const path    = require('path');

const app  = express();
const PORT = process.env.PORT || 3000;

// ---- Middleware ----
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// ---- VAPID ----
if (process.env.VAPID_PUBLIC_KEY && process.env.VAPID_PRIVATE_KEY) {
  webpush.setVapidDetails(
    process.env.VAPID_SUBJECT || 'mailto:admin@splitaway.netlify.app',
    process.env.VAPID_PUBLIC_KEY,
    process.env.VAPID_PRIVATE_KEY
  );
  console.log('✅ VAPID configured');
} else {
  console.warn('⚠️  VAPID keys not set — push notifications disabled');
}

// ---- Routes ----

// Anthropic API proxy (локальный аналог Netlify Function)
app.post('/api/anthropic', async (req, res) => {
  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    return res.status(500).json({ error: 'ANTHROPIC_API_KEY не настроен в .env' });
  }
  try {
    const upstream = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01'
      },
      body: JSON.stringify(req.body)
    });
    const data = await upstream.json();
    res.status(upstream.status).json(data);
  } catch (err) {
    console.error('[Anthropic proxy]', err.message);
    res.status(502).json({ error: err.message });
  }
});

// VAPID public key для клиента
app.get('/api/vapid-public-key', (req, res) => {
  res.json({ key: process.env.VAPID_PUBLIC_KEY || null });
});

// Отправить push всем подписчикам поездки
// P0-3: проверяем invite_token через Supabase перед отправкой
app.post('/api/push/send', async (req, res) => {
  const { subscriptions, payload, invite_token } = req.body;

  // Обязательная авторизация: invite_token должен быть передан
  if (!invite_token || typeof invite_token !== 'string' || invite_token.trim() === '') {
    console.warn('[Push] Rejected: missing invite_token');
    return res.status(401).json({ error: 'invite_token required' });
  }

  // Проверяем токен через Supabase REST API
  const supabaseUrl  = process.env.SUPABASE_URL;
  const supabaseKey  = process.env.SUPABASE_SERVICE_KEY || process.env.SUPABASE_ANON_KEY;
  if (supabaseUrl && supabaseKey) {
    try {
      const check = await fetch(
        `${supabaseUrl}/rest/v1/trip_tokens?invite_token=eq.${encodeURIComponent(invite_token)}&select=trip_id`,
        { headers: { apikey: supabaseKey, Authorization: `Bearer ${supabaseKey}` } }
      );
      const rows = await check.json();
      if (!Array.isArray(rows) || rows.length === 0) {
        console.warn('[Push] Rejected: invalid invite_token');
        return res.status(403).json({ error: 'invalid invite_token' });
      }
    } catch (e) {
      // Если Supabase недоступен — логируем, но не блокируем (деградация)
      console.warn('[Push] Token check failed (Supabase unavailable):', e.message);
    }
  }

  if (!subscriptions?.length) return res.json({ sent: 0 });

  const results = await Promise.allSettled(
    subscriptions.map(sub =>
      webpush.sendNotification(
        { endpoint: sub.endpoint, keys: { p256dh: sub.p256dh, auth: sub.auth } },
        JSON.stringify(payload)
      )
    )
  );

  const sent = results.filter(r => r.status === 'fulfilled').length;
  const failed = results.length - sent;
  console.log(`Push: ${sent} sent, ${failed} failed`);
  res.json({ sent, failed });
});

// SPA fallback
app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, () => {
  console.log(`\n🚀 Splitaway server running at http://localhost:${PORT}`);
  console.log(`   Open http://localhost:${PORT} in your browser\n`);
});
