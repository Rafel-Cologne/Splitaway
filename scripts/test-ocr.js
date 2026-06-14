#!/usr/bin/env node
// ============================================================
// Splitaway — OCR A/B Test Script
// Отправляет чеки из папки в /api/receipts/analyze и выводит метрики.
//
// Использование:
//   node scripts/test-ocr.js ./test-receipts/
//   node scripts/test-ocr.js ./test-receipts/ --url=http://localhost:3000
//   node scripts/test-ocr.js ./test-receipts/ --url=https://splitaway.netlify.app
//
// Требования:
//   - В папке должны быть .jpg/.jpeg/.png файлы чеков
//   - Рядом с каждым чеком может быть .json с эталоном:
//       receipt.jpg → receipt.expected.json
//       { "total": 26.87, "itemCount": 1, "items": [{"name":"Super 95","price":26.87}] }
//
// Что НЕ коммитится в Git:
//   - Сами фотографии чеков (добавлены в .gitignore)
//   - Ключи API
//   - Файлы results/*.json с персональными данными
// ============================================================

const fs   = require('fs');
const path = require('path');

const args     = process.argv.slice(2);
const receiptsDir = args.find(a => !a.startsWith('--')) || './test-receipts';
const baseUrl  = (args.find(a => a.startsWith('--url=')) || '--url=https://splitaway.netlify.app').split('=')[1];
const endpoint = `${baseUrl}/api/receipts/analyze`;

// Максимальная ширина для resize (имитирует frontend)
const MAX_PX   = 1200;

if (!fs.existsSync(receiptsDir)) {
  console.error(`❌ Папка не найдена: ${receiptsDir}`);
  console.error(`   Создайте папку и положите в неё фотографии чеков (.jpg/.png)`);
  process.exit(1);
}

const files = fs.readdirSync(receiptsDir)
  .filter(f => /\.(jpg|jpeg|png)$/i.test(f))
  .sort();

if (!files.length) {
  console.error(`❌ Нет изображений в ${receiptsDir}`);
  process.exit(1);
}

console.log(`\n🧾 Splitaway OCR Test`);
console.log(`   Endpoint: ${endpoint}`);
console.log(`   Чеков:    ${files.length}\n`);

// Результаты для финальной таблицы
const results = [];

async function testReceipt(filename) {
  const fullPath = path.join(receiptsDir, filename);
  const ext      = path.extname(filename).toLowerCase();
  const mt       = ext === '.png' ? 'image/png' : 'image/jpeg';

  // Читаем эталон если есть
  const expectedPath = fullPath.replace(/\.(jpg|jpeg|png)$/i, '.expected.json');
  let expected = null;
  if (fs.existsSync(expectedPath)) {
    try { expected = JSON.parse(fs.readFileSync(expectedPath, 'utf8')); } catch {}
  }

  // Читаем и конвертируем в base64 (упрощённо — без Canvas resize)
  const b64 = fs.readFileSync(fullPath).toString('base64');

  const startMs = Date.now();
  let result, error;

  try {
    const resp = await fetch(endpoint, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ image: b64, mediaType: mt }),
      // Timeout 15s
      signal: AbortSignal.timeout ? AbortSignal.timeout(15000) : undefined
    });
    result = await resp.json();
  } catch (e) {
    error = e.message;
  }

  const ms = Date.now() - startMs;

  if (error || !result || result.error) {
    results.push({
      file:         filename,
      provider:     '—',
      items:        0,
      total:        null,
      totalOk:      null,
      countOk:      null,
      fakeLines:    null,
      confidence:   null,
      ms,
      error:        error || result?.error || 'Unknown'
    });
    console.log(`❌ ${filename.padEnd(30)} ${ms}ms  ERROR: ${error || result?.error}`);
    return;
  }

  const provider    = result.provider || '?';
  const itemCount   = result.items?.length || 0;
  const total       = result.total;
  const confidence  = result.overallConfidence ?? null;
  const warnings    = result.warnings || [];

  // Метрики против эталона
  let totalOk = null, countOk = null, fakeLines = null;
  if (expected) {
    if (expected.total != null && total != null) {
      totalOk = Math.abs(total - expected.total) < 0.10; // допуск 10 центов
    }
    if (expected.itemCount != null) {
      countOk = itemCount === expected.itemCount;
    }
    // Ложные строки = позиции с needsReview: true (не распознанные)
    fakeLines = (result.items || []).filter(i => i.needsReview).length;
  }

  results.push({ file: filename, provider, items: itemCount, total, totalOk, countOk, fakeLines, confidence, ms, warnings });

  // Сохранить обезличенный результат (без base64)
  const outDir = path.join(receiptsDir, 'results');
  if (!fs.existsSync(outDir)) fs.mkdirSync(outDir);
  const outPath = path.join(outDir, filename.replace(/\.(jpg|jpeg|png)$/i, '.result.json'));
  const sanitized = { ...result };
  delete sanitized.image; // на случай если вдруг попало
  fs.writeFileSync(outPath, JSON.stringify(sanitized, null, 2));

  const status = totalOk === null ? '🔷' : totalOk ? '✅' : '❌';
  console.log(
    `${status} ${filename.padEnd(30)} ` +
    `${provider.padEnd(7)} ` +
    `${String(itemCount).padStart(2)} поз  ` +
    `${total != null ? (total.toFixed(2)+' €').padStart(9) : '    —    '} ` +
    `conf=${confidence != null ? confidence.toFixed(2) : '—  '}  ` +
    `${ms}ms` +
    (warnings.length ? `  ⚠️ ${warnings.map(w=>w.code).join(',')}` : '')
  );
}

// Последовательный запуск (не параллельный — не нагружаем API)
(async () => {
  for (const f of files) {
    await testReceipt(f);
    await new Promise(r => setTimeout(r, 500)); // 500ms между запросами
  }

  // ── Итоговая таблица метрик ──
  console.log('\n' + '─'.repeat(72));
  console.log('📊 ИТОГОВЫЕ МЕТРИКИ');
  console.log('─'.repeat(72));

  const ok    = results.filter(r => !r.error);
  const total = results.length;

  if (ok.length) {
    const avgMs   = Math.round(ok.reduce((s,r) => s+r.ms, 0) / ok.length);
    const avgConf = ok.filter(r=>r.confidence!=null).reduce((s,r)=>s+r.confidence,0) / ok.filter(r=>r.confidence!=null).length;

    const withExpected = ok.filter(r => r.totalOk !== null);
    const totalAccuracy = withExpected.length
      ? (withExpected.filter(r => r.totalOk).length / withExpected.length * 100).toFixed(0) + '%'
      : 'н/д (нет эталонов)';
    const countAccuracy = ok.filter(r => r.countOk !== null).length
      ? (ok.filter(r => r.countOk).length / ok.filter(r => r.countOk !== null).length * 100).toFixed(0) + '%'
      : 'н/д';

    console.log(`  Всего чеков:          ${total}`);
    console.log(`  Успешно:              ${ok.length}`);
    console.log(`  Ошибки:               ${total - ok.length}`);
    console.log(`  Точность суммы итога: ${totalAccuracy}`);
    console.log(`  Точность кол-ва поз:  ${countAccuracy}`);
    console.log(`  Среднее время:        ${avgMs} мс`);
    console.log(`  Средний confidence:   ${isNaN(avgConf) ? '—' : avgConf.toFixed(2)}`);
    console.log(`  Ложные строки avg:    ${(ok.filter(r=>r.fakeLines!=null).reduce((s,r)=>s+(r.fakeLines||0),0)/Math.max(1,ok.filter(r=>r.fakeLines!=null).length)).toFixed(1)}`);

    // По провайдерам
    const byProvider = {};
    ok.forEach(r => { byProvider[r.provider] = (byProvider[r.provider]||0)+1; });
    console.log(`  Провайдеры:           ${Object.entries(byProvider).map(([k,v])=>`${k}=${v}`).join(', ')}`);
  }

  console.log('─'.repeat(72));
  console.log(`\n💾 Результаты сохранены в ${path.join(receiptsDir, 'results')}/ (без фото и ключей)\n`);
})();
