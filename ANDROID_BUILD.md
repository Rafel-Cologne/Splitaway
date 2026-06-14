# Splitaway — Сборка Android (.aab)

## Что нужно установить

| Инструмент | Где взять |
|---|---|
| Node.js 18+ | nodejs.org |
| JDK 17 | adoptium.net |
| Android Studio | developer.android.com/studio |
| Android SDK (API 34) | через Android Studio → SDK Manager |

---

## 1. Установить зависимости

```bash
npm install
```

Это установит `@capacitor/core`, `@capacitor/cli`, `@capacitor/android`, `@capacitor/app`.

---

## 2. Инициализировать Capacitor (только первый раз)

```bash
npx cap init Splitaway com.splitaway.app --web-dir public
```

---

## 3. Добавить Android платформу (только первый раз)

```bash
npx cap add android
```

Создаст папку `android/` с нативным проектом.

---

## 4. Настроить AndroidManifest.xml

После `npx cap add android` открой файл:
```
android/app/src/main/AndroidManifest.xml
```

Добавь внутрь `<manifest>` (после существующих `<uses-permission>`):

```xml
<!-- Интернет -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Камера (сканирование чеков) -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />

<!-- Push-уведомления (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Вибрация -->
<uses-permission android:name="android.permission.VIBRATE" />
```

В секцию `<activity>` (внутрь существующего `<intent-filter>`) добавь обработчик App Links:

```xml
<!-- Deep Link: https://splitaway.netlify.app/?trip=XXX&token=UUID -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https"
          android:host="splitaway.netlify.app" />
</intent-filter>

<!-- Кастомная схема: splitaway://trip?id=XXX&token=UUID -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="splitaway" android:host="trip" />
</intent-filter>
```

---

## 5. Синхронизировать веб-файлы → android/

```bash
npx cap sync android
```

Запускать после каждого изменения `public/`.

---

## 6. Открыть в Android Studio

```bash
npx cap open android
```

---

## 7. Подписать релизную сборку

### 7.1 Создать keystore (один раз)

```bash
keytool -genkey -v \
  -keystore splitaway-release.jks \
  -alias splitaway \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Сохрани `splitaway-release.jks` в БЕЗОПАСНОМ месте — без него нельзя обновить приложение в Play Store.

### 7.2 Добавить в `android/app/build.gradle`

```groovy
android {
    ...
    signingConfigs {
        release {
            storeFile     file("../../splitaway-release.jks")
            storePassword "YOUR_STORE_PASSWORD"
            keyAlias      "splitaway"
            keyPassword   "YOUR_KEY_PASSWORD"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled false
        }
    }
}
```

### 7.3 Собрать AAB

```bash
cd android
./gradlew bundleRelease
```

Готовый AAB: `android/app/build/outputs/bundle/release/app-release.aab`

---

## 8. App Links — верификация домена

Файл `public/.well-known/assetlinks.json` уже создан.

Нужно подставить реальный SHA-256 fingerprint твоего подписного ключа:

```bash
keytool -list -v \
  -keystore splitaway-release.jks \
  -alias splitaway \
  | grep "SHA256"
```

Скопируй вывод (формат `AA:BB:CC:...`) и вставь в `assetlinks.json`.

После деплоя на Netlify проверь: `https://splitaway.netlify.app/.well-known/assetlinks.json`

---

## 9. Отладка на устройстве

```bash
# Запустить на подключённом Android устройстве
npx cap run android

# Или через Android Studio: Run → Run 'app'
```

Chrome DevTools для отладки WebView:
`chrome://inspect` → выбрать устройство → Inspect

---

## Цикл разработки

```bash
# 1. Изменил public/index.html или другие файлы
# 2. Синхронизируй
npx cap sync android

# 3. Запусти на устройстве
npx cap run android
```

---

## Структура после npx cap add android

```
splitaway/
├── android/                    ← нативный Android проект
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml   ← РЕДАКТИРОВАТЬ (permissions + intents)
│   │   │   └── assets/public/        ← автозаполняется npx cap sync
│   │   └── build.gradle              ← РЕДАКТИРОВАТЬ (signing)
│   └── build.gradle
├── capacitor.config.ts         ✅ создан
├── public/
│   └── .well-known/
│       └── assetlinks.json     ✅ создан (нужен для App Links)
└── package.json                ✅ обновлён
```
