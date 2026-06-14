// ============================================================
// Splitaway — Netlify Function: Anthropic Proxy
// Проксирует запросы к api.anthropic.com, скрывая API-ключ.
// ANTHROPIC_API_KEY хранится только в переменных окружения Netlify.
// ============================================================

// CORS-заголовки нужны для Capacitor WebView (Origin: https://localhost)
const CORS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Content-Type': 'application/json'
};

exports.handler = async function (event) {
  // OPTIONS preflight (браузер/Capacitor спрашивает разрешение перед POST)
  if (event.httpMethod === 'OPTIONS') {
    return { statusCode: 200, headers: CORS, body: '' };
  }

  // Принимаем только POST
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
  }

  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    return {
      statusCode: 500,
      headers: CORS,
      body: JSON.stringify({ error: 'ANTHROPIC_API_KEY not configured on server' })
    };
  }

  let body;
  try {
    body = JSON.parse(event.body);
  } catch {
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Invalid JSON' }) };
  }

  try {
    const response = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'Content-Type':      'application/json',
        'x-api-key':         apiKey,
        'anthropic-version': '2023-06-01'
      },
      body: JSON.stringify(body)
    });

    const data = await response.json();

    return {
      statusCode: response.status,
      headers: CORS,
      body: JSON.stringify(data)
    };
  } catch (err) {
    console.error('[anthropic-proxy] Error:', err);
    return {
      statusCode: 502,
      headers: CORS,
      body: JSON.stringify({ error: 'Proxy error', detail: err.message })
    };
  }
};
