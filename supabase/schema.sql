-- ============================================================
-- Splitaway — Supabase Database Schema
-- Выполните в Supabase SQL Editor: https://app.supabase.com
-- ============================================================

-- Включаем расширение для UUID
create extension if not exists "uuid-ossp";

-- ============================================================
-- ТАБЛИЦЫ
-- ============================================================

-- Поездки
create table if not exists trips (
  id          text primary key,
  name        text not null,
  dest        text,
  created_at  timestamptz default now(),
  updated_at  timestamptz default now()
);

-- Участники
create table if not exists members (
  id          uuid primary key default uuid_generate_v4(),
  trip_id     text not null references trips(id) on delete cascade,
  name        text not null,
  gender      text default 'm' check (gender in ('m','f')),
  is_admin    boolean default false,
  created_at  timestamptz default now()
);

-- Группы (пары и семьи)
create table if not exists groups (
  id          uuid primary key default uuid_generate_v4(),
  trip_id     text not null references trips(id) on delete cascade,
  type        text not null check (type in ('couple','family')),
  label       text,
  members     text[] not null default '{}',
  created_at  timestamptz default now()
);

-- Расходы
create table if not exists expenses (
  id            uuid primary key default uuid_generate_v4(),
  trip_id       text not null references trips(id) on delete cascade,
  name          text not null,
  amount        numeric(10,2) not null,
  comment       text,
  date          date,
  paid_by       text not null,
  participants  text[] not null default '{}',
  from_treasury boolean default false,
  items         jsonb default '[]',
  created_by    text,
  created_at    timestamptz default now()
);

-- Касса
create table if not exists treasury (
  id          uuid primary key default uuid_generate_v4(),
  trip_id     text not null unique references trips(id) on delete cascade,
  total       numeric(10,2) default 0,
  spent       numeric(10,2) default 0,
  updated_at  timestamptz default now()
);

-- Push подписки участников
create table if not exists push_subscriptions (
  id          uuid primary key default uuid_generate_v4(),
  trip_id     text not null references trips(id) on delete cascade,
  member_name text not null,
  endpoint    text not null unique,
  p256dh      text not null,
  auth        text not null,
  created_at  timestamptz default now()
);

-- ============================================================
-- REALTIME — включаем для всех таблиц
-- ============================================================
alter table trips      replica identity full;
alter table members    replica identity full;
alter table groups     replica identity full;
alter table expenses   replica identity full;
alter table treasury   replica identity full;

-- Добавляем таблицы в realtime publication
do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime' and tablename = 'expenses'
  ) then
    alter publication supabase_realtime add table trips, members, groups, expenses, treasury;
  end if;
end $$;

-- ============================================================
-- ROW LEVEL SECURITY (RLS)
-- ============================================================
alter table trips               enable row level security;
alter table members             enable row level security;
alter table groups              enable row level security;
alter table expenses            enable row level security;
alter table treasury            enable row level security;
alter table push_subscriptions  enable row level security;

-- Политики — все могут читать и писать (идентификация по trip_id)
-- В продакшене можно ужесточить через JWT
create policy "trips_all"    on trips    for all using (true) with check (true);
create policy "members_all"  on members  for all using (true) with check (true);
create policy "groups_all"   on groups   for all using (true) with check (true);
create policy "expenses_all" on expenses for all using (true) with check (true);
create policy "treasury_all" on treasury for all using (true) with check (true);
create policy "push_all"     on push_subscriptions for all using (true) with check (true);

-- ============================================================
-- ФУНКЦИИ
-- ============================================================

-- Автообновление updated_at
create or replace function update_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql;

create trigger trips_updated_at
  before update on trips
  for each row execute function update_updated_at();

create trigger treasury_updated_at
  before update on treasury
  for each row execute function update_updated_at();
