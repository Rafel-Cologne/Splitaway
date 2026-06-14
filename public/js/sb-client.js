// ============================================================
// Splitaway — Supabase Client
// Глобальный клиент (без ES modules) — подключается через <script>
// Зависит от: sb-config.js (SUPABASE_URL, SUPABASE_ANON_KEY)
// ============================================================

let _sbClient = null;

/**
 * Возвращает инициализированный Supabase client.
 * При первом вызове загружает SDK из CDN и создаёт клиент.
 * Возвращает null если credentials не заполнены.
 */
async function getSupabase() {
  if (_sbClient) return _sbClient;

  // Проверяем, что credentials заполнены
  if (
    typeof SUPABASE_URL === 'undefined' ||
    SUPABASE_URL.includes('ВАШИ_ДАННЫЕ') ||
    typeof SUPABASE_ANON_KEY === 'undefined' ||
    SUPABASE_ANON_KEY.includes('ваш_anon_key')
  ) {
    console.warn('[SB] ⚠️ Supabase не настроен. Заполните public/js/sb-config.js');
    return null;
  }

  // Загружаем Supabase SDK из CDN если ещё не загружен
  if (!window.supabase) {
    await new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/dist/umd/supabase.js';
      s.onload = resolve;
      s.onerror = () => reject(new Error('Не удалось загрузить Supabase SDK'));
      document.head.appendChild(s);
    });
  }

  _sbClient = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    realtime: { params: { eventsPerSecond: 10 } }
  });

  console.log('[SB] ✅ Supabase client initialized →', SUPABASE_URL);
  return _sbClient;
}
