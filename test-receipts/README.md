# Тестовые чеки для OCR

Положи сюда фотографии чеков для A/B теста.

## Формат

- Файлы: `.jpg` / `.jpeg` / `.png`
- Рядом с каждым чеком можно создать эталон `<name>.expected.json`:

```json
{
  "total": 26.87,
  "itemCount": 1,
  "items": [
    { "name": "Super 95", "price": 26.87 }
  ]
}
```

## Запуск теста

```bash
# Против production Netlify
node scripts/test-ocr.js ./test-receipts/

# Против локального сервера
node scripts/test-ocr.js ./test-receipts/ --url=http://localhost:3000
```

## Важно

- Фотографии **не коммитятся в Git** (добавлены в .gitignore)
- Результаты из папки `results/` тоже не коммитятся
- Не кладите сюда чеки с персональными данными без согласия владельца
