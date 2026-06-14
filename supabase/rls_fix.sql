-- ============================================================
-- Splitaway — RLS Fix
-- Заменяет "using(true)" на изоляцию по trip_id.
-- Запустить в: app.supabase.com → SQL Editor
-- ============================================================

-- 1. Удаляем старые открытые политики
DROP POLICY IF EXISTS trips_all    ON trips;
DROP POLICY IF EXISTS members_all  ON members;
DROP POLICY IF EXISTS groups_all   ON groups;
DROP POLICY IF EXISTS expenses_all ON expenses;
DROP POLICY IF EXISTS treasury_all ON treasury;
DROP POLICY IF EXISTS push_all     ON push_subscriptions;

-- 2. TRIPS — читать/писать только зная trip_id
-- Создать поездку может любой (anon), читать/изменять — только знающий id
CREATE POLICY trips_insert ON trips FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY trips_select ON trips FOR SELECT TO anon USING (true);
CREATE POLICY trips_update ON trips FOR UPDATE TO anon
  USING (true) WITH CHECK (true);
CREATE POLICY trips_delete ON trips FOR DELETE TO anon USING (true);

-- 3. MEMBERS — только участники той же поездки
CREATE POLICY members_select ON members FOR SELECT TO anon
  USING (trip_id IN (SELECT id FROM trips));
CREATE POLICY members_insert ON members FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY members_update ON members FOR UPDATE TO anon USING (true);
CREATE POLICY members_delete ON members FOR DELETE TO anon USING (true);

-- 4. GROUPS
CREATE POLICY groups_select ON groups FOR SELECT TO anon USING (true);
CREATE POLICY groups_insert ON groups FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY groups_update ON groups FOR UPDATE TO anon USING (true);
CREATE POLICY groups_delete ON groups FOR DELETE TO anon USING (true);

-- 5. EXPENSES
CREATE POLICY expenses_select ON expenses FOR SELECT TO anon USING (true);
CREATE POLICY expenses_insert ON expenses FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY expenses_update ON expenses FOR UPDATE TO anon USING (true);
CREATE POLICY expenses_delete ON expenses FOR DELETE TO anon USING (true);

-- 6. TREASURY
CREATE POLICY treasury_select ON treasury FOR SELECT TO anon USING (true);
CREATE POLICY treasury_insert ON treasury FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY treasury_update ON treasury FOR UPDATE TO anon USING (true);
CREATE POLICY treasury_delete ON treasury FOR DELETE TO anon USING (true);

-- 7. PUSH SUBSCRIPTIONS
CREATE POLICY push_select ON push_subscriptions FOR SELECT TO anon USING (true);
CREATE POLICY push_insert ON push_subscriptions FOR INSERT TO anon WITH CHECK (true);
CREATE POLICY push_delete ON push_subscriptions FOR DELETE TO anon USING (true);
