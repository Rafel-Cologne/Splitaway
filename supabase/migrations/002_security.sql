-- ============================================================
-- Splitaway — Migration 002: Security Model
-- invite_token + admin_token + защищённые RLS + SECURITY DEFINER RPC
--
-- ЗАПУСТИТЬ В: app.supabase.com → SQL Editor
-- Безопасен для повторного запуска (IF NOT EXISTS / OR REPLACE).
-- ============================================================

-- pgcrypto для SHA-256 хэшей admin_token
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- 1. ТАБЛИЦА ТОКЕНОВ (недоступна anon-пользователям)
--    invite_token — секрет для участников (в URL)
--    admin_token_hash — хэш секрета организатора
-- ============================================================
CREATE TABLE IF NOT EXISTS trip_tokens (
  trip_id          TEXT PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
  invite_token     TEXT UNIQUE NOT NULL DEFAULT gen_random_uuid()::text,
  admin_token_hash TEXT,
  created_at       TIMESTAMPTZ DEFAULT now()
);

-- Включаем RLS и НЕ создаём политик — anon не имеет доступа вообще
ALTER TABLE trip_tokens ENABLE ROW LEVEL SECURITY;
-- (нет политик = нет доступа для anon)

-- Индекс для быстрой валидации токена
CREATE UNIQUE INDEX IF NOT EXISTS idx_trip_tokens_invite ON trip_tokens(invite_token);

-- Бэкфилл: создаём токены для уже существующих поездок
INSERT INTO trip_tokens (trip_id)
SELECT id FROM trips
WHERE id NOT IN (SELECT trip_id FROM trip_tokens)
ON CONFLICT (trip_id) DO NOTHING;

-- ============================================================
-- 2. ОБНОВЛЕНИЕ RLS — anon только SELECT
--    Все мутации идут через SECURITY DEFINER функции
-- ============================================================

-- TRIPS
DROP POLICY IF EXISTS trips_all    ON trips;
DROP POLICY IF EXISTS trips_insert ON trips;
DROP POLICY IF EXISTS trips_select ON trips;
DROP POLICY IF EXISTS trips_update ON trips;
DROP POLICY IF EXISTS trips_delete ON trips;
CREATE POLICY trips_select ON trips FOR SELECT TO anon USING (true);
-- INSERT/UPDATE/DELETE — только через RPC

-- MEMBERS
DROP POLICY IF EXISTS members_all    ON members;
DROP POLICY IF EXISTS members_select ON members;
DROP POLICY IF EXISTS members_insert ON members;
DROP POLICY IF EXISTS members_update ON members;
DROP POLICY IF EXISTS members_delete ON members;
CREATE POLICY members_select ON members FOR SELECT TO anon USING (true);

-- GROUPS
DROP POLICY IF EXISTS groups_all    ON groups;
DROP POLICY IF EXISTS groups_select ON groups;
DROP POLICY IF EXISTS groups_insert ON groups;
DROP POLICY IF EXISTS groups_update ON groups;
DROP POLICY IF EXISTS groups_delete ON groups;
CREATE POLICY groups_select ON groups FOR SELECT TO anon USING (true);

-- EXPENSES
DROP POLICY IF EXISTS expenses_all    ON expenses;
DROP POLICY IF EXISTS expenses_select ON expenses;
DROP POLICY IF EXISTS expenses_insert ON expenses;
DROP POLICY IF EXISTS expenses_update ON expenses;
DROP POLICY IF EXISTS expenses_delete ON expenses;
CREATE POLICY expenses_select ON expenses FOR SELECT TO anon USING (true);

-- TREASURY
DROP POLICY IF EXISTS treasury_all    ON treasury;
DROP POLICY IF EXISTS treasury_select ON treasury;
DROP POLICY IF EXISTS treasury_insert ON treasury;
DROP POLICY IF EXISTS treasury_update ON treasury;
DROP POLICY IF EXISTS treasury_delete ON treasury;
CREATE POLICY treasury_select ON treasury FOR SELECT TO anon USING (true);

-- PUSH SUBSCRIPTIONS
DROP POLICY IF EXISTS push_all    ON push_subscriptions;
DROP POLICY IF EXISTS push_select ON push_subscriptions;
DROP POLICY IF EXISTS push_insert ON push_subscriptions;
DROP POLICY IF EXISTS push_delete ON push_subscriptions;
CREATE POLICY push_select ON push_subscriptions FOR SELECT TO anon USING (true);

-- ============================================================
-- 3. УНИКАЛЬНЫЙ ИНДЕКС members(trip_id, name)
-- ============================================================
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'members_trip_id_name_key'
  ) THEN
    ALTER TABLE members ADD CONSTRAINT members_trip_id_name_key UNIQUE (trip_id, name);
  END IF;
END $$;

-- ============================================================
-- 4. ДОПОЛНИТЕЛЬНЫЕ ИНДЕКСЫ
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_members_trip_id     ON members(trip_id);
CREATE INDEX IF NOT EXISTS idx_groups_trip_id      ON groups(trip_id);
CREATE INDEX IF NOT EXISTS idx_expenses_trip_id    ON expenses(trip_id);
CREATE INDEX IF NOT EXISTS idx_expenses_created_at ON expenses(created_at);
CREATE INDEX IF NOT EXISTS idx_push_trip_id        ON push_subscriptions(trip_id);

-- ============================================================
-- 5. ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ (внутренние, не для anon)
-- ============================================================

-- Валидация invite_token → возвращает trip_id
CREATE OR REPLACE FUNCTION _sa_validate_invite(p_invite_token TEXT)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_trip_id TEXT;
BEGIN
  SELECT trip_id INTO v_trip_id FROM trip_tokens WHERE invite_token = p_invite_token;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'invalid_invite_token' USING ERRCODE = 'P0001';
  END IF;
  RETURN v_trip_id;
END;
$$;

-- Валидация admin_token (bcrypt hash)
CREATE OR REPLACE FUNCTION _sa_validate_admin(p_trip_id TEXT, p_admin_token TEXT)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_hash TEXT;
BEGIN
  SELECT admin_token_hash INTO v_hash FROM trip_tokens WHERE trip_id = p_trip_id;
  -- NULL hash = старая поездка без защиты (backward compat) — пропускаем
  IF v_hash IS NULL THEN RETURN; END IF;
  IF encode(digest(p_admin_token, 'sha256'), 'hex') != v_hash THEN
    RAISE EXCEPTION 'invalid_admin_token' USING ERRCODE = 'P0002';
  END IF;
END;
$$;

-- ============================================================
-- 6. RPC: Создать поездку
--    Возвращает { invite_token, trip_id }
-- ============================================================
CREATE OR REPLACE FUNCTION create_trip(
  p_trip_id            TEXT,
  p_name               TEXT,
  p_dest               TEXT        DEFAULT NULL,
  p_admin_token_hash   TEXT        DEFAULT NULL,
  p_members            JSONB       DEFAULT '[]',
  p_treasury_total     NUMERIC     DEFAULT 0,
  p_treasury_contributions JSONB   DEFAULT '[]'
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_invite_token TEXT;
BEGIN
  -- Создаём поездку
  INSERT INTO trips (id, name, dest)
  VALUES (p_trip_id, p_name, p_dest)
  ON CONFLICT (id) DO NOTHING;

  -- Создаём токены
  INSERT INTO trip_tokens (trip_id, admin_token_hash)
  VALUES (p_trip_id, p_admin_token_hash)
  ON CONFLICT (trip_id) DO NOTHING
  RETURNING invite_token INTO v_invite_token;

  -- Если запись уже была (конфликт) — берём существующий токен
  IF v_invite_token IS NULL THEN
    SELECT invite_token INTO v_invite_token FROM trip_tokens WHERE trip_id = p_trip_id;
  END IF;

  -- Вставляем участников
  IF jsonb_array_length(p_members) > 0 THEN
    INSERT INTO members (trip_id, name, gender, is_admin)
    SELECT p_trip_id,
           m->>'name',
           COALESCE(m->>'gender', 'm'),
           COALESCE((m->>'is_admin')::boolean, false)
    FROM jsonb_array_elements(p_members) AS m
    ON CONFLICT (trip_id, name)
    DO UPDATE SET gender = EXCLUDED.gender, is_admin = EXCLUDED.is_admin;
  END IF;

  -- Вставляем кассу
  INSERT INTO treasury (trip_id, total, spent, contributions)
  VALUES (p_trip_id, p_treasury_total, 0, p_treasury_contributions)
  ON CONFLICT (trip_id)
  DO UPDATE SET total = EXCLUDED.total, contributions = EXCLUDED.contributions;

  RETURN jsonb_build_object('invite_token', v_invite_token, 'trip_id', p_trip_id);
END;
$$;

-- ============================================================
-- 7. RPC: Полная синхронизация данных поездки
--    Все мутации в одном валидированном вызове
-- ============================================================
CREATE OR REPLACE FUNCTION sync_trip_data(
  p_invite_token TEXT,
  p_data         JSONB
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_trip_id      TEXT;
  v_members      JSONB;
  v_groups       JSONB;
  v_expenses     JSONB;
  v_treasury     JSONB;
  v_trip         JSONB;
  v_member_names TEXT[];
  v_group_ids    UUID[];
  v_expense_ids  UUID[];
BEGIN
  -- Валидируем токен
  v_trip_id := _sa_validate_invite(p_invite_token);

  v_trip     := p_data->'trip';
  v_members  := COALESCE(p_data->'members',  '[]');
  v_groups   := COALESCE(p_data->'groups',   '[]');
  v_expenses := COALESCE(p_data->'expenses', '[]');
  v_treasury := p_data->'treasury';

  -- 1. Обновляем поездку
  UPDATE trips SET
    name       = COALESCE(v_trip->>'name', name),
    dest       = v_trip->>'dest',
    updated_at = now()
  WHERE id = v_trip_id;

  -- 2. Участники: upsert + удалить выбывших
  IF jsonb_array_length(v_members) > 0 THEN
    INSERT INTO members (trip_id, name, gender, is_admin)
    SELECT v_trip_id,
           m->>'name',
           COALESCE(m->>'gender','m'),
           COALESCE((m->>'is_admin')::boolean, false)
    FROM jsonb_array_elements(v_members) AS m
    ON CONFLICT (trip_id, name)
    DO UPDATE SET gender = EXCLUDED.gender, is_admin = EXCLUDED.is_admin;

    SELECT array_agg(m->>'name')
    INTO v_member_names
    FROM jsonb_array_elements(v_members) AS m;

    DELETE FROM members
    WHERE trip_id = v_trip_id AND name != ALL(v_member_names);
  ELSE
    DELETE FROM members WHERE trip_id = v_trip_id;
  END IF;

  -- 3. Группы: upsert + удалить удалённые
  IF jsonb_array_length(v_groups) > 0 THEN
    INSERT INTO groups (id, trip_id, type, label, members)
    SELECT
      (g->>'id')::uuid,
      v_trip_id,
      g->>'type',
      g->>'label',
      ARRAY(SELECT jsonb_array_elements_text(g->'members'))
    FROM jsonb_array_elements(v_groups) AS g
    ON CONFLICT (id)
    DO UPDATE SET
      type    = EXCLUDED.type,
      label   = EXCLUDED.label,
      members = EXCLUDED.members;

    SELECT array_agg((g->>'id')::uuid)
    INTO v_group_ids
    FROM jsonb_array_elements(v_groups) AS g;

    DELETE FROM groups
    WHERE trip_id = v_trip_id AND id != ALL(v_group_ids);
  ELSE
    DELETE FROM groups WHERE trip_id = v_trip_id;
  END IF;

  -- 4. Расходы: upsert + удалить удалённые
  IF jsonb_array_length(v_expenses) > 0 THEN
    INSERT INTO expenses (id, trip_id, name, amount, comment, date,
                          paid_by, participants, from_treasury, items, created_by)
    SELECT
      (e->>'id')::uuid,
      v_trip_id,
      e->>'name',
      (e->>'amount')::numeric,
      NULLIF(e->>'comment',''),
      NULLIF(e->>'date','')::date,
      e->>'paid_by',
      ARRAY(SELECT jsonb_array_elements_text(e->'participants')),
      COALESCE((e->>'from_treasury')::boolean, false),
      COALESCE(e->'items', '[]'),
      NULLIF(e->>'created_by','')
    FROM jsonb_array_elements(v_expenses) AS e
    ON CONFLICT (id)
    DO UPDATE SET
      name          = EXCLUDED.name,
      amount        = EXCLUDED.amount,
      comment       = EXCLUDED.comment,
      date          = EXCLUDED.date,
      paid_by       = EXCLUDED.paid_by,
      participants  = EXCLUDED.participants,
      from_treasury = EXCLUDED.from_treasury,
      items         = EXCLUDED.items;

    SELECT array_agg((e->>'id')::uuid)
    INTO v_expense_ids
    FROM jsonb_array_elements(v_expenses) AS e;

    DELETE FROM expenses
    WHERE trip_id = v_trip_id AND id != ALL(v_expense_ids);
  ELSE
    DELETE FROM expenses WHERE trip_id = v_trip_id;
  END IF;

  -- 5. Касса
  IF v_treasury IS NOT NULL THEN
    INSERT INTO treasury (trip_id, total, spent, contributions)
    VALUES (
      v_trip_id,
      COALESCE((v_treasury->>'total')::numeric, 0),
      COALESCE((v_treasury->>'spent')::numeric, 0),
      COALESCE(v_treasury->'contributions', '[]')
    )
    ON CONFLICT (trip_id) DO UPDATE SET
      total         = EXCLUDED.total,
      spent         = EXCLUDED.spent,
      contributions = EXCLUDED.contributions,
      updated_at    = now();
  END IF;

  RETURN '{"ok":true}';
EXCEPTION WHEN OTHERS THEN
  RETURN jsonb_build_object('error', SQLERRM, 'code', SQLSTATE);
END;
$$;

-- ============================================================
-- 8. RPC: Удалить поездку (только для администратора)
-- ============================================================
CREATE OR REPLACE FUNCTION admin_delete_trip(
  p_invite_token TEXT,
  p_admin_token  TEXT
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_trip_id TEXT;
BEGIN
  v_trip_id := _sa_validate_invite(p_invite_token);
  PERFORM _sa_validate_admin(v_trip_id, p_admin_token);
  DELETE FROM trips WHERE id = v_trip_id;
  RETURN '{"ok":true}';
END;
$$;

-- ============================================================
-- 9. RPC: Push-подписки через токен
-- ============================================================
CREATE OR REPLACE FUNCTION upsert_push_subscription(
  p_invite_token TEXT,
  p_member_name  TEXT,
  p_endpoint     TEXT,
  p_p256dh       TEXT,
  p_auth         TEXT
) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_trip_id TEXT;
BEGIN
  v_trip_id := _sa_validate_invite(p_invite_token);
  INSERT INTO push_subscriptions (trip_id, member_name, endpoint, p256dh, auth)
  VALUES (v_trip_id, p_member_name, p_endpoint, p_p256dh, p_auth)
  ON CONFLICT (endpoint) DO UPDATE SET
    member_name = EXCLUDED.member_name,
    p256dh      = EXCLUDED.p256dh,
    auth        = EXCLUDED.auth;
  RETURN '{"ok":true}';
END;
$$;

-- ============================================================
-- Права: anon может вызывать RPC (но не напрямую таблицы)
-- ============================================================
GRANT EXECUTE ON FUNCTION create_trip          TO anon;
GRANT EXECUTE ON FUNCTION sync_trip_data       TO anon;
GRANT EXECUTE ON FUNCTION admin_delete_trip    TO anon;
GRANT EXECUTE ON FUNCTION upsert_push_subscription TO anon;
