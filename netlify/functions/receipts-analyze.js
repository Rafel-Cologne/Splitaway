// ============================================================
// Splitaway — Receipt Analysis Endpoint
// Провайдер: Eagle Doc OCR (единственный — без каскада)
// API-ключ НИКОГДА не попадает во frontend или APK.
//
// Env vars:
//   EAGLE_DOC_API_KEY — ключ Eagle Doc OCR API
// ============================================================

const CORS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Content-Type': 'application/json'
};

const MAX_B64_CHARS     = 11_000_000; // ~8 MB raw
const ALLOWED_TYPES     = ['image/jpeg', 'image/png', 'image/webp'];
const EAGLE_DOC_ENDPOINT = 'https://api.eagledoc.io/v1/receipts';

// ============================================================
// АДАПТЕР: Eagle Doc → SplitawayReceiptResult
// ============================================================
function normalizeEagleDocReceipt(raw) {
  const data = raw?.data || raw;
  if (!data) return null;

  const rawItems = data.line_items || data.items || [];
  const items = rawItems
    .map(it => {
      const name   = (it.name || it.description || '').trim();
      const qty    = Number(it.quantity || it.qty || 1);
      const uPrice = it.unit_price  != null ? Number(it.unit_price)  : null;
      const tPrice = it.total_price != null ? Number(it.total_price)
                   : uPrice != null ? uPrice * qty : 0;
      const conf   = Number(it.confidence ?? 0.75);
      return {
        name,
        quantity:    qty,
        unitPrice:   uPrice,
        totalPrice:  tPrice,
        confidence:  conf,
        needsReview: conf < 0.6 || !name,
        rawText:     it.raw_text || ''
      };
    })
    .filter(it => it.totalPrice > 0 && it.name);

  const total    = data.total_amount != null ? Number(data.total_amount) : null;
  const subtotal = data.subtotal     != null ? Number(data.subtotal)     : null;
  const tax      = data.tax_amount   != null ? Number(data.tax_amount)   : null;
  const warnings = [];

  if (total != null && items.length > 0) {
    const calcSum = items.reduce((s, i) => s + i.totalPrice, 0);
    if (Math.abs(calcSum - total) / total > 0.05) {
      warnings.push({
        code:       'TOTAL_MISMATCH',
        expected:   Math.round(total   * 100) / 100,
        calculated: Math.round(calcSum * 100) / 100
      });
    }
  }

  if (items.length === 0 && total != null) {
    warnings.push({ code: 'NO_ITEMS_FOUND_TOTAL_KNOWN', total });
  }

  return {
    provider:          'eagledoc',
    merchant:          data.merchant_name || data.merchant || null,
    date:              data.date || data.transaction_date || null,
    currency:          data.currency || 'EUR',
    items,
    subtotal,
    tax,
    total,
    overallConfidence: Number(data.confidence ?? (items.length ? 0.75 : 0)),
    warnings
  };
}

// ============================================================
// EAGLE DOC API — запрос
// ============================================================
async function analyzeWithEagleDoc(b64, mediaType, apiKey) {
  const resp = await fetch(EAGLE_DOC_ENDPOINT, {
    method:  'POST',
    headers: {
      'Content-Type':  'application/json',
      'Authorization': `Bearer ${apiKey}`
    },
    body: JSON.stringify({ image: b64, mime_type: mediaType, type: 'receipt' })
  });

  if (!resp.ok) {
    const errBody = await resp.text().catch(() => '');
    throw new Error(`Eagle Doc ${resp.status}: ${errBody.slice(0, 200)}`);
  }

  return resp.json();
}

// ============================================================
// MAIN HANDLER
// ============================================================
exports.handler = async function(event) {
  if (event.httpMethod === 'OPTIONS') {
    return { statusCode: 200, headers: CORS, body: '' };
  }
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
  }

  let body;
  try { body = JSON.parse(event.body); }
  catch {
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Invalid JSON' }) };
  }

  const { image: b64, mediaType = 'image/jpeg' } = body;

  if (!b64 || typeof b64 !== 'string') {
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Missing image (base64 string required)' }) };
  }
  if (b64.length > MAX_B64_CHARS) {
    return { statusCode: 413, headers: CORS, body: JSON.stringify({ error: 'Image too large (max ~8 MB)' }) };
  }
  if (!ALLOWED_TYPES.includes(mediaType)) {
    return { statusCode: 415, headers: CORS, body: JSON.stringify({ error: `Unsupported type: ${mediaType}. Use jpeg/png/webp` }) };
  }

  const apiKey = process.env.EAGLE_DOC_API_KEY;
  if (!apiKey) {
    return {
      statusCode: 500,
      headers: CORS,
      body: JSON.stringify({ error: 'OCR not configured (EAGLE_DOC_API_KEY missing)' })
    };
  }

  try {
    console.log('[receipts-analyze] Provider: Eagle Doc');
    const raw        = await analyzeWithEagleDoc(b64, mediaType, apiKey);
    const normalized = normalizeEagleDocReceipt(raw);

    if (!normalized) {
      throw new Error('Eagle Doc returned unrecognizable response');
    }

    console.log(`[receipts-analyze] Eagle Doc OK — ${normalized.items.length} items, confidence=${normalized.overallConfidence.toFixed(2)}`);
    return { statusCode: 200, headers: CORS, body: JSON.stringify(normalized) };

  } catch (e) {
    console.error('[receipts-analyze] Eagle Doc failed:', e.message);
    return {
      statusCode: 502,
      headers: CORS,
      body: JSON.stringify({
        provider:          'none',
        merchant:          null,
        date:              null,
        currency:          'EUR',
        items:             [],
        subtotal:          null,
        tax:               null,
        total:             null,
        overallConfidence: 0,
        warnings:          [{ code: 'PROVIDER_FAILED', detail: e.message }],
        error:             'OCR provider failed'
      })
    };
  }
};
