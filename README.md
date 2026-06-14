# ✈️ Splitaway — Умное деление расходов

PWA-приложение для совместных поездок: делите расходы, сканируйте чеки, синхронизируйтесь в реальном времени.

---

## 🚀 Быстрый старт (без бэкенда)

```bash
# 1. Установите зависимости
npm install

# 2. Запустите Live Server
npm run dev

# 3. Откройте http://localhost:3000
```

> Без Supabase — данные хранятся в **localStorage** браузера.  
> Делитесь кодом поездки с участниками вручную.

---

## 🗄️ Настройка Supabase (реальный бэкенд + синхронизация)

### 1. Создайте проект на Supabase
Перейдите на [app.supabase.com](https://app.supabase.com) → New Project

### 2. Создайте таблицы
Откройте **SQL Editor** и выполните:
```sql
-- Вставьте содержимое файла supabase/schema.sql
```

### 3. Получите ключи
**Settings → API** → скопируйте:
- `URL`
- `anon key` (public)
- `service_role key` (secret — только для сервера)

### 4. Настройте переменные окружения
```bash
cp .env.example .env
# Откройте .env и заполните значения
```

### 5. Подключите Supabase в коде
В `public/index.html` найдите:
```js
const USE_SUPABASE = false; // ← Измените на true
```

В `src/supabase-client.js` замените:
```js
const SUPABASE_URL      = 'https://ВАШ_ПРОЕКТ.supabase.co';
const SUPABASE_ANON_KEY = 'ваш_anon_key';
```

### 6. Запустите сервер
```bash
npm start
# → http://localhost:3000
```

---

## 🔔 Настройка Push-уведомлений

### 1. Сгенерируйте VAPID ключи
```bash
node -e "
const wp = require('web-push');
const keys = wp.generateVAPIDKeys();
console.log('VAPID_PUBLIC_KEY=' + keys.publicKey);
console.log('VAPID_PRIVATE_KEY=' + keys.privateKey);
"
```

### 2. Добавьте в `.env`
```
VAPID_PUBLIC_KEY=ваш_public_key
VAPID_PRIVATE_KEY=ваш_private_key
VAPID_SUBJECT=mailto:ваш@email.com
```

### 3. Запустите Node.js сервер (не Live Server)
```bash
npm start
```

> Push-уведомления требуют **HTTPS** или **localhost**.  
> Для деплоя используйте Vercel, Railway, Render или VPS.

---

## 📱 Установка как PWA (на телефон)

1. Откройте приложение в браузере
2. **iOS Safari**: Поделиться → Добавить на экран Home
3. **Android Chrome**: Меню → Установить приложение

---

## 🏗️ Структура проекта

```
splitway/
├── public/               ← Статика (всё, что видит браузер)
│   ├── index.html        ← Главное приложение (весь UI + логика)
│   ├── sw.js             ← Service Worker (офлайн + push)
│   ├── offline.html      ← Страница для офлайн-режима
│   ├── manifest.json     ← PWA манифест
│   └── icons/            ← Иконки (72px до 512px)
├── src/
│   ├── supabase-client.js ← Supabase DB + Realtime + офлайн-очередь
│   └── notifications.js   ← Push-уведомления (subscribe/send)
├── supabase/
│   └── schema.sql        ← SQL схема базы данных
├── server.js             ← Express сервер (push endpoint)
├── package.json
├── .env.example
└── README.md
```

---

## 🖼️ Иконки (добавьте сами)

Создайте иконки в папке `public/icons/`:
- `icon-72.png`, `icon-96.png`, `icon-128.png`, `icon-144.png`
- `icon-152.png`, `icon-192.png`, `icon-384.png`, `icon-512.png`

Используйте [PWA Builder Image Generator](https://www.pwabuilder.com/imageGenerator) или любой редактор.

---

## 🚢 Деплой (Vercel — бесплатно)

```bash
# Установите Vercel CLI
npm i -g vercel

# Деплой
vercel --prod
```

> Добавьте переменные из `.env` в Vercel Dashboard → Settings → Environment Variables

---

## 📋 Функционал

| Функция | Статус |
|---------|--------|
| Создание поездки + участники | ✅ |
| Пары и семьи (объединение) | ✅ |
| Добавление расходов | ✅ |
| Касса | ✅ |
| Сканер чека (Claude Vision) | ✅ |
| Меню + выбор заказов | ✅ |
| Балансы + долги | ✅ |
| Детализация долга | ✅ |
| QR-приглашение | ✅ |
| PWA (офлайн) | ✅ |
| Supabase Realtime | 🔧 Настройте .env |
| Push-уведомления | 🔧 Нужен HTTPS |
| Переименование участников | ✅ |
| Удаление поездки | ✅ |

---

## 🛠️ VS Code расширения (рекомендуются)

Установятся автоматически при открытии проекта:
- **Live Server** — запуск без Node.js
- **Prettier** — форматирование кода
- **Supabase** — работа с БД прямо в редакторе

---

*Splitaway v1.0 — разработано с ❤️ для комфортных совместных путешествий*
