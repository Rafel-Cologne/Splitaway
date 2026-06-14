// ============================================================
// Splitaway — Netlify Function: Anthropic Proxy
// Проксирует запросы к api.anthropic.com, скрывая API-ключ.
// ANTHROPIC_API_KEY хранится только в переменных окружения Netlify.
// ============================================================

exports.handler = async function (event) {
  // Принимаем только POST
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method Not Allowed' };
  }

  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    return {
      statusCode: 500,
      body: JSON.stringify({ error: 'ANTHROPIC_API_KEY not configured on server' })
    };
  }

  let body;
  try {
    body = JSON.parse(event.body);
  } catch {
    return { statusCode: 400, body: JSON.stringify({ error: 'Invalid JSON' }) };
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
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    };
  } catch (err) {
    console.error('[anthropic-proxy] Error:', err);
    return {
      statusCode: 502,
      body: JSON.stringify({ error: 'Proxy error', detail: err.message })
    };
  }
};
