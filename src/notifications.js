// ============================================================
// Splitaway — Push Notifications
// src/notifications.js
// ============================================================

// Замените на ваш VAPID public key (из .env или /api/vapid-public-key)
let VAPID_PUBLIC_KEY = null;

async function getVapidKey() {
  if (VAPID_PUBLIC_KEY) return VAPID_PUBLIC_KEY;
  try {
    const res = await fetch('/api/vapid-public-key');
    const { key } = await res.json();
    VAPID_PUBLIC_KEY = key;
    return key;
  } catch {
    return null;
  }
}

function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - base64String.length % 4) % 4);
  const base64  = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw     = window.atob(base64);
  return new Uint8Array([...raw].map(c => c.charCodeAt(0)));
}

// ---- SUBSCRIBE ----
export async function subscribePush(tripId, memberName) {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
    console.warn('[Push] Not supported');
    return null;
  }

  const vapidKey = await getVapidKey();
  if (!vapidKey) {
    console.warn('[Push] No VAPID key — running locally?');
    return null;
  }

  // Запрашиваем разрешение
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    console.log('[Push] Permission denied');
    return null;
  }

  try {
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(vapidKey)
    });

    const subJson = sub.toJSON();

    // Сохраняем подписку в Supabase
    const { getSupabase } = await import('./supabase-client.js');
    const sb = await getSupabase();
    await sb.from('push_subscriptions').upsert({
      trip_id:     tripId,
      member_name: memberName,
      endpoint:    subJson.endpoint,
      p256dh:      subJson.keys.p256dh,
      auth:        subJson.keys.auth
    }, { onConflict: 'endpoint' });

    localStorage.setItem('sw_push_subscribed', '1');
    console.log('[Push] Subscribed successfully');
    return sub;
  } catch (err) {
    console.error('[Push] Subscribe error:', err);
    return null;
  }
}

// ---- UNSUBSCRIBE ----
export async function unsubscribePush() {
  if (!('serviceWorker' in navigator)) return;
  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  if (sub) {
    await sub.unsubscribe();
    localStorage.removeItem('sw_push_subscribed');
    console.log('[Push] Unsubscribed');
  }
}

// ---- SEND PUSH (через наш сервер) ----
export async function sendPushToTrip(tripId, payload) {
  try {
    // Получаем все подписки для этой поездки
    const { getSupabase } = await import('./supabase-client.js');
    const sb = await getSupabase();
    const { data: subs } = await sb
      .from('push_subscriptions')
      .select('*')
      .eq('trip_id', tripId);

    if (!subs?.length) return;

    await fetch('/api/push/send', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ subscriptions: subs, payload })
    });
  } catch (err) {
    console.error('[Push] Send error:', err);
  }
}

// ---- LOCAL NOTIFICATION (работает без сервера) ----
export function showLocalNotification(title, body, options = {}) {
  if (!('Notification' in window)) return;
  if (Notification.permission !== 'granted') return;

  const reg = navigator.serviceWorker?.controller;
  if (reg) {
    navigator.serviceWorker.ready.then(r => {
      r.showNotification(title, {
        body,
        icon:  '/icons/icon-192.png',
        badge: '/icons/icon-72.png',
        tag:   options.tag || 'splitaway',
        data:  options.data || {},
        vibrate: [150, 75, 150],
        ...options
      });
    });
  } else {
    new Notification(title, { body, icon: '/icons/icon-192.png', ...options });
  }
}

// ---- CHECK STATUS ----
export function isPushEnabled() {
  return localStorage.getItem('sw_push_subscribed') === '1';
}

export async function getPushPermission() {
  if (!('Notification' in window)) return 'unsupported';
  return Notification.permission; // 'default' | 'granted' | 'denied'
}
