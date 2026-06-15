// ============================================================
// Splitaway — Service Worker v3.0
// Стратегии:
//   index.html + JS → stale-while-revalidate (офлайн + свежесть)
//   Иконки, манифест, офлайн-страница → stale-while-revalidate
//   API (supabase, eagle doc, anthropic) → network-only (не кэшируем)
// v3.0: локальный lucide.js, qrcode.min.js; убран CDN-кэш; нет Google Fonts
// ============================================================

const SW_VERSION   = '3.0';
const CACHE_STATIC = `splitaway-static-${SW_VERSION}`;

// Ресурсы, которые кэшируем при установке (все локальные — нет CDN)
const PRECACHE_URLS = [
  '/',
  '/index.html',
  '/offline.html',
  '/manifest.json',
  '/js/lucide.js',
  '/js/qrcode.min.js',
  '/js/supabase.js',
  '/js/sb-config.js',
  '/js/sb-client.js',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
];

// URL-паттерны, которые НИКОГДА не кэшируем (API, реалтайм, финансовые данные)
const SKIP_CACHE = [
  'supabase.co',
  'anthropic.com',
  'eagledoc',
  '/api/',
  'netlify/functions',
];

// ============================================================
// INSTALL — кэшируем ядро приложения
// ============================================================
self.addEventListener('install', event => {
  console.log(`[SW ${SW_VERSION}] Installing...`);
  event.waitUntil(
    caches.open(CACHE_STATIC)
      .then(cache => cache.addAll(PRECACHE_URLS))
      .catch(err => console.warn('[SW] Precache partial fail:', err))
      .then(() => self.skipWaiting())
  );
});

// ============================================================
// ACTIVATE — удаляем ВСЕ старые кэши
// ============================================================
self.addEventListener('activate', event => {
  console.log(`[SW ${SW_VERSION}] Activating...`);
  const CURRENT = [CACHE_STATIC];
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => !CURRENT.includes(k)).map(k => {
          console.log('[SW] Deleting old cache:', k);
          return caches.delete(k);
        })
      ))
      .then(() => self.clients.claim())
  );
});

// ============================================================
// FETCH — роутер стратегий кэширования
// ============================================================
self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;

  const url = new URL(event.request.url);

  // 1. API и реалтайм — пропускаем, только сеть
  if (SKIP_CACHE.some(p => event.request.url.includes(p))) return;

  // 2. index.html и корень — stale-while-revalidate
  //    Отдаём кэш сразу, обновляем в фоне
  const isShell = url.pathname === '/' ||
                  url.pathname === '/index.html' ||
                  url.pathname.endsWith('/');

  if (isShell || url.pathname.startsWith('/js/')) {
    event.respondWith(staleWhileRevalidate(event.request, CACHE_STATIC));
    return;
  }

  // 3. Иконки, манифест, страницы — stale-while-revalidate из статики
  const isLocal = url.origin === self.location.origin;
  if (isLocal) {
    event.respondWith(staleWhileRevalidate(event.request, CACHE_STATIC));
    return;
  }

  // 4. Остальные внешние запросы — только сеть (не кэшируем)
  return;
});

// ---- Стратегия: отдать кэш немедленно + обновить в фоне ----
async function staleWhileRevalidate(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);

  // Обновляем в фоне (не ждём)
  const fetchPromise = fetch(request)
    .then(response => {
      if (response && response.ok) {
        cache.put(request, response.clone());
      }
      return response;
    })
    .catch(() => null);

  // Если есть кэш — отдаём сразу; иначе ждём сети
  return cached || await fetchPromise || offlineFallback(request);
}

// ---- Фолбэк при офлайне ----
async function offlineFallback(request) {
  if (request.headers.get('accept')?.includes('text/html')) {
    // Для HTML-запросов отдаём главную страницу из кэша
    const cache = await caches.open(CACHE_STATIC);
    const shell = await cache.match('/') || await cache.match('/index.html');
    if (shell) return shell;
  }
  return new Response(
    JSON.stringify({ error: 'offline' }),
    { status: 503, headers: { 'Content-Type': 'application/json' } }
  );
}

// ============================================================
// BACKGROUND SYNC — уведомляем страницу запустить sbSync()
// Очередь операций хранится в localStorage на стороне клиента.
// SW только сигнализирует что сеть восстановлена.
// ============================================================
self.addEventListener('sync', event => {
  if (event.tag === 'sync-expenses') {
    event.waitUntil(notifyClientsToSync());
  }
});

async function notifyClientsToSync() {
  const clients = await self.clients.matchAll({
    type: 'window',
    includeUncontrolled: true
  });
  clients.forEach(client => {
    client.postMessage({ type: 'SW_SYNC_READY' });
  });
  console.log(`[SW] Notified ${clients.length} clients to sync`);
}

// ============================================================
// PUSH NOTIFICATIONS
// ============================================================
self.addEventListener('push', event => {
  let data = {
    title: 'Splitaway',
    body: 'Новое обновление в поездке',
    icon: '/icons/icon-192.png'
  };
  try { data = { ...data, ...event.data.json() }; } catch {}

  event.waitUntil(
    self.registration.showNotification(data.title, {
      body:    data.body,
      icon:    data.icon || '/icons/icon-192.png',
      badge:   '/icons/icon-72.png',
      tag:     data.tag || 'splitaway',
      data:    data.url ? { url: data.url } : {},
      actions: [
        { action: 'open',    title: '👁 Открыть' },
        { action: 'dismiss', title: '✕ Закрыть'  }
      ],
      vibrate: [200, 100, 200],
      requireInteraction: false
    })
  );
});

// ============================================================
// NOTIFICATION CLICK
// ============================================================
self.addEventListener('notificationclick', event => {
  event.notification.close();
  if (event.action === 'dismiss') return;

  const url = event.notification.data?.url || '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      .then(clients => {
        const existing = clients.find(c => c.url.includes(self.location.origin));
        if (existing) { existing.focus(); existing.navigate(url); }
        else self.clients.openWindow(url);
      })
  );
});
