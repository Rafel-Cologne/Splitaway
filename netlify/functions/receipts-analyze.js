// ============================================================
// Splitaway — Receipt Analysis Endpoint
// Каскад: Azure Document Intelligence → Claude Vision
// API-ключи НИКОГДА не попадают во frontend или APK.
//
// Env vars:
//   AZURE_DI_KEY      — ключ Azure Document Intelligence
//   AZURE_DI_ENDPOINT — https://<resource>.cognitiveservices.azure.com/
//   ANTHROPIC_API_KEY — ключ Claude (уже настроен)
// ============================================================

const CORS = {
  'Access-Control-Allow-Origin':  '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Content-Type': 'application/json'
};

const MAX_B64_CHARS = 11_000_000; // ~8 MB raw
const ALLOWED_TYPES  = ['image/jpeg', 'image/png', 'image/webp'];

// ── Утилита: безопасное извлечение значения из поля Azure ──
function azureVal(field) {
  if (!field) return null;
  return field.valueCurrency?.amount
      ?? field.valueNumber
      ?? field.valueString
      ?? field.valueDate
      ?? field.content
      ?? null;
}
function azureConf(field) { return field?.confidence ?? 0; }

// ============================================================
// АДАПТЕР: Azure Document Intelligence → SplitawayReceiptResult
// ============================================================
function adaptAzure(azureResult) {
  const doc = azureResult?.analyzeResult?.documents?.[0];
  if (!doc) return null;

  const f = doc.fields || {};

  // Позиции
  const rawItems = f.Items?.valueArray || [];
  const items = rawItems.map(item => {
    const obj   = item.valueObject || {};
    const name  = azureVal(obj.Description) || '';
    const qty   = azureVal(obj.Quantity)    ?? 1;
    const uPrice = obj.UnitPrice  ? azureVal(obj.UnitPrice)  : null;
    const tPrice = obj.TotalPrice ? azureVal(obj.TotalPrice) : null;

    // confidence = минимум из имени и цены (слабейшее звено)
    const conf = Math.min(
      azureConf(obj.Description),
      azureConf(obj.TotalPrice ?? obj.UnitPrice ?? {confidence: 0.5})
    );

    return {
      name,
      quantity:   qty,
      unitPrice:  uPrice,
      totalPrice: tPrice ?? (uPrice != null ? uPrice * qty : 0),
      confidence: conf,
      needsReview: conf < 0.6 || !name.trim(),
      rawText:    item.content || ''
    };
  }).filter(it => it.totalPrice > 0);

  // Итог и проверка суммы
  const total    = azureVal(f.Total)    ?? azureVal(f.Subtotal);
  const tax      = azureVal(f.TotalTax) ?? null;
  const subtotal = azureVal(f.Subtotal) ?? null;
  const warnings = [];

  if (total != null && items.length > 0) {
    const calcSum = items.reduce((s, i) => s + i.totalPrice, 0);
    if (Math.abs(calcSum - total) / total > 0.05) {
      warnings.push({
        code:       'TOTAL_MISMATCH',
        expected:   Math.round(total    * 100) / 100,
        calculated: Math.round(calcSum  * 100) / 100
      });
    }
  }

  // Валюта
  const currency = f.Total?.valueCurrency?.currencyCode
                ?? f.Subtotal?.valueCurrency?.currencyCode
                ?? 'EUR';

  return {
    provider:          'azure',
    merchant:          azureVal(f.MerchantName) || null,
    date:              f.TransactionDate?.valueDate  || null,
    currency,
    items,
    subtotal,
    tax,
    total,
    overallConfidence: doc.confidence ?? 0,
    warnings
  };
}

// ============================================================
// АДАПТЕР: Claude Vision JSON → SplitawayReceiptResult
// ============================================================
function adaptClaude(claudeText) {
  try {
    const clean  = claudeText.replace(/```json|```/g, '').trim();
    const parsed = JSON.parse(clean);
    const raw    = parsed.items || [];

    const items = raw
      .filter(it => parseFloat(it.price) > 0 && parseFloat(it.price) < 9999)
      .map(it => ({
        name:       (it.name || '').trim(),
        quantity:   parseInt(it.qty)     || 1,
        unitPrice:  null,
        totalPrice: parseFloat(it.price) || 0,
        confidence: 0.70,   // Claude не даёт confidence — ставим условный 0.70
        needsReview: false,
        rawText:    ''
      }))
      .filter(it => it.name.length > 0);

    return {
      provider:          'claude',
      merchant:          null,
      date:              null,
      currency:          'EUR',
      items,
      subtotal:          null,
      tax:               null,
      total:             null,
      overallConfidence: 0.70,
      warnings:          []
    };
  } catch {
    return null;
  }
}

// ============================================================
// AZURE API — запуск анализа + опрос результата
// ============================================================
async function analyzeWithAzure(b64, mediaType, key, endpoint) {
  // Убрать trailing slash для корректного URL
  const base = endpoint.replace(/\/$/, '');
  const url  = `${base}/documentintelligence/documentModels/prebuilt-receipt:analyze?api-version=2024-11-30`;

  const startResp = await fetch(url, {
    method:  'POST',
    headers: {
      'Content-Type':              'application/json',
      'Ocp-Apim-Subscription-Key': key
    },
    body: JSON.stringify({ base64Source: b64 })
  });

  if (!startResp.ok) {
    const errBody = await startResp.text().catch(() => '');
    throw new Error(`Azure start failed ${startResp.status}: ${errBody.slice(0, 200)}`);
  }

  const operationUrl = startResp.headers.get('Operation-Location');
  if (!operationUrl) throw new Error('Azure: missing Operation-Location header');

  // Опрос до готовности (макс 8 с — Netlify timeout 10 с)
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    await new Promise(r => setTimeout(r, 1200));
    const pollResp = await fetch(operationUrl, {
      headers: { 'Ocp-Apim-Subscription-Key': key }
    });
    const result = await pollResp.json();
    if (result.status === 'succeeded') return result;
    if (result.status === 'failed') {
      throw new Error('Azure analysis failed: ' + JSON.stringify(result.error || {}));
    }
    // status === 'running' → продолжаем
  }
  throw new Error('Azure: polling timeout (8s)');
}

// ============================================================
// CLAUDE VISION — напрямую (не через anthropic-proxy)
// ============================================================
async function analyzeWithClaude(b64, mediaType, apiKey) {
  const prompt = `You are a receipt parser. Extract purchased items from this receipt photo.
Receipt may be in German, Russian, Italian, French, Spanish, English or other language.
This could be a restaurant bill, supermarket receipt, or gas station receipt.

IMPORTANT RULES:
- price = the monetary amount in EUR/USD/RUB (realistic: 0.50–500)
- NEVER use document numbers, receipt numbers, article codes, or serial numbers as prices
- Gas station: "*000001 Super 95  26,87 EUR" → name="Super 95", price=26.87
- qty=1 unless explicit quantity is shown (e.g. "3x Coffee", "2 Bier")
- price = total for that line (qty × unit price)

EXCLUDE completely (do not add to output):
- Totals, subtotals, grand totals (Gesamt, Total, Summe, Итого, Gesamtbetrag)
- Taxes (MwSt, TVA, IVA, НДС, USt, Steuer)
- Receipt/document numbers (Beleg-Nr, Bon-Nr, Kassenbon, Rechnungsnr, Transaction, Trace)
- Payment method lines (VISA, Mastercard, EC-Karte, Cash, Bar, Debit, Contactless)
- Tip, service charge, Trinkgeld
- Store name, address, street, zip code, phone, website, email
- Date, time, terminal ID, cashier name, table number
- Thank-you messages, loyalty points

Return ONLY valid JSON, no markdown, no explanation:
{"items":[{"name":"item name in original language","price":0.00,"qty":1}]}`;

  const resp = await fetch('https://api.anthropic.com/v1/messages', {
    method:  'POST',
    headers: {
      'Content-Type':      'application/json',
      'x-api-key':         apiKey,
      'anthropic-version': '2023-06-01'
    },
    body: JSON.stringify({
      model:      'claude-3-5-haiku-20241022',
      max_tokens: 2000,
      messages: [{ role: 'user', content: [
        { type: 'image', source: { type: 'base64', media_type: mediaType, data: b64 } },
        { type: 'text',  text: prompt }
      ]}]
    })
  });

  const data = await resp.json();
  if (data.error) throw new Error(`Claude API: ${data.error.message}`);
  return data.content?.[0]?.text || '';
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

  // ── Парсинг тела ──
  let body;
  try { body = JSON.parse(event.body); }
  catch {
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Invalid JSON' }) };
  }

  const { image: b64, mediaType = 'image/jpeg' } = body;

  // ── Валидация ──
  if (!b64 || typeof b64 !== 'string') {
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Missing image (base64 string required)' }) };
  }
  if (b64.length > MAX_B64_CHARS) {
    return { statusCode: 413, headers: CORS, body: JSON.stringify({ error: 'Image too large (max ~8 MB)' }) };
  }
  if (!ALLOWED_TYPES.includes(mediaType)) {
    return { statusCode: 415, headers: CORS, body: JSON.stringify({ error: `Unsupported type: ${mediaType}. Use jpeg/png/webp` }) };
  }

  // ── Попытка 1: Azure Document Intelligence ──
  const azureKey      = process.env.AZURE_DI_KEY;
  const azureEndpoint = process.env.AZURE_DI_ENDPOINT;

  if (azureKey && azureEndpoint) {
    try {
      console.log('[receipts-analyze] Provider: Azure DI');
      const azureResult  = await analyzeWithAzure(b64, mediaType, azureKey, azureEndpoint);
      const normalized   = adaptAzure(azureResult);
      if (normalized && normalized.items.length > 0) {
        console.log(`[receipts-analyze] Azure OK — ${normalized.items.length} items, confidence=${normalized.overallConfidence.toFixed(2)}`);
        return { statusCode: 200, headers: CORS, body: JSON.stringify(normalized) };
      }
      // Azure вернул 0 позиций — fallback (например, фото не чека)
      console.warn('[receipts-analyze] Azure: 0 items → falling back to Claude');
    } catch (e) {
      console.warn('[receipts-analyze] Azure failed:', e.message);
    }
  } else {
    console.log('[receipts-analyze] Azure not configured (AZURE_DI_KEY/ENDPOINT missing) — using Claude');
  }

  // ── Попытка 2: Claude Vision ──
  const claudeKey = process.env.ANTHROPIC_API_KEY;
  if (!claudeKey) {
    return {
      statusCode: 500,
      headers: CORS,
      body: JSON.stringify({ error: 'No OCR provider configured (set AZURE_DI_KEY or ANTHROPIC_API_KEY)' })
    };
  }

  try {
    console.log('[receipts-analyze] Provider: Claude Vision');
    const claudeText = await analyzeWithClaude(b64, mediaType, claudeKey);
    const normalized = adaptClaude(claudeText);
    if (normalized && normalized.items.length > 0) {
      console.log(`[receipts-analyze] Claude OK — ${normalized.items.length} items`);
      return { statusCode: 200, headers: CORS, body: JSON.stringify(normalized) };
    }
    throw new Error('Claude returned empty items');
  } catch (e) {
    console.error('[receipts-analyze] Claude failed:', e.message);
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
        warnings:          [{ code: 'ALL_PROVIDERS_FAILED', detail: e.message }],
        error:             'All OCR providers failed'
      })
    };
  }
};
