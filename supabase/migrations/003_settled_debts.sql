-- ============================================================
-- Splitaway — Migration 003: settledDebts + treasury.holder
-- Добавляет два поля в таблицу treasury:
--   holder TEXT        — кто держит кассу (имя участника)
--   settled_debts JSONB — погашённые долги [{id,from,to,amount,date}]
-- Обновляет sync_trip_data RPC для поддержки новых полей.
-- ============================================================

-- Добавляем колонки (IF NOT EXISTS безопасен при повторном запуске)
ALTER TABLE treasury
  ADD COLUMN IF NOT EXISTS holder        TEXT    DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS settled_debts JSONB   DEFAULT '[]';

-- ============================================================
-- Обновляем sync_trip_data — добавляем holder и settled_debts
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

  -- 5. Касса: включая holder и settled_debts
  IF v_treasury IS NOT NULL THEN
    INSERT INTO treasury (trip_id, total, spent, contributions, holder, settled_debts)
    VALUES (
      v_trip_id,
      COALESCE((v_treasury->>'total')::numeric, 0),
      COALESCE((v_treasury->>'spent')::numeric, 0),
      COALESCE(v_treasury->'contributions', '[]'),
      NULLIF(v_treasury->>'holder', ''),
      COALESCE(v_treasury->'settled_debts', '[]')
    )
    ON CONFLICT (trip_id) DO UPDATE SET
      total         = EXCLUDED.total,
      spent         = EXCLUDED.spent,
      contributions = EXCLUDED.contributions,
      holder        = EXCLUDED.holder,
      settled_debts = EXCLUDED.settled_debts,
      updated_at    = now();
  END IF;

  RETURN '{"ok":true}';
EXCEPTION WHEN OTHERS THEN
  RETURN jsonb_build_object('error', SQLERRM, 'code', SQLSTATE);
END;
$$;
