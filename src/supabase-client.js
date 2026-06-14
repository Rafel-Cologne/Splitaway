// ============================================================
// Splitaway — Supabase Client
// src/supabase-client.js
// ============================================================

// ⚠️ Замените на ваши данные из https://app.supabase.com → Settings → API
const SUPABASE_URL     = 'https://ВАШИ_ДАННЫЕ.supabase.co';
const SUPABASE_ANON_KEY = 'ваш_anon_key_здесь';

// Динамически загружаем Supabase SDK
let _supabase = null;

async function getSupabase() {
  if (_supabase) return _supabase;
  if (!window.supabase) {
    await new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/dist/umd/supabase.js';
      s.onload = resolve; s.onerror = reject;
      document.head.appendChild(s);
    });
  }
  _supabase = window.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    realtime: { params: { eventsPerSecond: 10 } }
  });
  return _supabase;
}

// ============================================================
// DB HELPERS
// ============================================================

export async function loadTrip(tripId) {
  const sb = await getSupabase();
  const [{ data: trip }, { data: members }, { data: groups }, { data: expenses }, { data: treasury }] =
    await Promise.all([
      sb.from('trips').select('*').eq('id', tripId).single(),
      sb.from('members').select('*').eq('trip_id', tripId).order('created_at'),
      sb.from('groups').select('*').eq('trip_id', tripId).order('created_at'),
      sb.from('expenses').select('*').eq('trip_id', tripId).order('created_at'),
      sb.from('treasury').select('*').eq('trip_id', tripId).single()
    ]);
  return { trip, members, groups, expenses, treasury };
}

export async function createTrip(tripData) {
  const sb = await getSupabase();
  const { data, error } = await sb.from('trips').insert(tripData).select().single();
  if (error) throw error;
  return data;
}

export async function updateTrip(tripId, updates) {
  const sb = await getSupabase();
  const { error } = await sb.from('trips').update(updates).eq('id', tripId);
  if (error) throw error;
}

export async function addMember(memberData) {
  const sb = await getSupabase();
  const { data, error } = await sb.from('members').insert(memberData).select().single();
  if (error) throw error;
  return data;
}

export async function updateMember(memberId, updates) {
  const sb = await getSupabase();
  const { error } = await sb.from('members').update(updates).eq('id', memberId);
  if (error) throw error;
}

export async function deleteMember(memberId) {
  const sb = await getSupabase();
  const { error } = await sb.from('members').delete().eq('id', memberId);
  if (error) throw error;
}

export async function upsertGroup(groupData) {
  const sb = await getSupabase();
  const { data, error } = await sb.from('groups').upsert(groupData).select().single();
  if (error) throw error;
  return data;
}

export async function deleteGroup(groupId) {
  const sb = await getSupabase();
  const { error } = await sb.from('groups').delete().eq('id', groupId);
  if (error) throw error;
}

export async function addExpense(expenseData) {
  const sb = await getSupabase();
  const { data, error } = await sb.from('expenses').insert(expenseData).select().single();
  if (error) throw error;
  return data;
}

export async function deleteExpense(expenseId) {
  const sb = await getSupabase();
  const { error } = await sb.from('expenses').delete().eq('id', expenseId);
  if (error) throw error;
}

export async function updateTreasury(tripId, updates) {
  const sb = await getSupabase();
  const { error } = await sb.from('treasury').upsert({ trip_id: tripId, ...updates });
  if (error) throw error;
}

// ============================================================
// REALTIME SUBSCRIPTIONS
// ============================================================

let activeChannel = null;

export async function subscribeToTrip(tripId, callbacks) {
  const sb = await getSupabase();

  // Отписываемся от предыдущей подписки
  if (activeChannel) {
    await sb.removeChannel(activeChannel);
    activeChannel = null;
  }

  activeChannel = sb.channel(`trip:${tripId}`)
    .on('postgres_changes', {
      event: '*', schema: 'public', table: 'expenses',
      filter: `trip_id=eq.${tripId}`
    }, payload => {
      console.log('[RT] expenses:', payload.eventType);
      callbacks.onExpense?.(payload);
    })
    .on('postgres_changes', {
      event: '*', schema: 'public', table: 'members',
      filter: `trip_id=eq.${tripId}`
    }, payload => {
      console.log('[RT] members:', payload.eventType);
      callbacks.onMember?.(payload);
    })
    .on('postgres_changes', {
      event: '*', schema: 'public', table: 'groups',
      filter: `trip_id=eq.${tripId}`
    }, payload => {
      console.log('[RT] groups:', payload.eventType);
      callbacks.onGroup?.(payload);
    })
    .on('postgres_changes', {
      event: '*', schema: 'public', table: 'treasury',
      filter: `trip_id=eq.${tripId}`
    }, payload => {
      console.log('[RT] treasury:', payload.eventType);
      callbacks.onTreasury?.(payload);
    })
    .subscribe(status => {
      console.log('[RT] Status:', status);
      callbacks.onStatus?.(status);
    });

  return activeChannel;
}

export async function unsubscribe() {
  if (!activeChannel) return;
  const sb = await getSupabase();
  await sb.removeChannel(activeChannel);
  activeChannel = null;
}

// ============================================================
// OFFLINE QUEUE (IndexedDB)
// ============================================================

const IDB_NAME = 'splitaway-offline';
const IDB_STORE = 'pending';

function openIDB() {
  return new Promise((res, rej) => {
    const req = indexedDB.open(IDB_NAME, 1);
    req.onupgradeneeded = e => e.target.result.createObjectStore(IDB_STORE, { keyPath: 'id', autoIncrement: true });
    req.onsuccess = e => res(e.target.result);
    req.onerror = e => rej(e.target.error);
  });
}

export async function queueOfflineAction(action) {
  const db = await openIDB();
  const tx = db.transaction(IDB_STORE, 'readwrite');
  tx.objectStore(IDB_STORE).add({ ...action, ts: Date.now() });
  // Register background sync
  if ('serviceWorker' in navigator && 'SyncManager' in window) {
    const reg = await navigator.serviceWorker.ready;
    await reg.sync.register('sync-expenses').catch(() => {});
  }
}

export async function flushOfflineQueue() {
  const db = await openIDB();
  const tx = db.transaction(IDB_STORE, 'readwrite');
  const store = tx.objectStore(IDB_STORE);
  const all = await new Promise((res, rej) => {
    const req = store.getAll();
    req.onsuccess = e => res(e.target.result);
    req.onerror  = e => rej(e.target.error);
  });

  for (const item of all) {
    try {
      if (item.type === 'add_expense')    await addExpense(item.data);
      if (item.type === 'delete_expense') await deleteExpense(item.data.id);
      if (item.type === 'update_treasury') await updateTreasury(item.data.tripId, item.data.updates);
      store.delete(item.id);
    } catch (e) {
      console.warn('[Offline] Could not flush item:', item.id, e);
    }
  }
}

// При восстановлении соединения — сбрасываем очередь
window.addEventListener('online', () => {
  console.log('[Network] Online — flushing offline queue');
  flushOfflineQueue();
});
