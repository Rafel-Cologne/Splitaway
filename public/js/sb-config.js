// ============================================================
// Splitaway — Supabase Configuration
// Заполните ваши данные из https://app.supabase.com
// Settings → API → Project URL  и  Project API keys → anon (public)
// ============================================================

const SUPABASE_URL      = 'https://tixuwwseeoufxhsnwhej.supabase.co';
const SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRpeHV3d3NlZW91Znhoc253aGVqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM0ODQwODAsImV4cCI6MjA4OTA2MDA4MH0.7A2cDqN62korSvngfA7o8YwoDhV-LLpw6jfzAHAMG_I';

// AI-распознавание чеков работает через backend (/api/anthropic).
// API-ключ хранится только на сервере (переменная окружения ANTHROPIC_API_KEY).
// НЕ вставляйте ключ сюда — этот файл публично доступен.
