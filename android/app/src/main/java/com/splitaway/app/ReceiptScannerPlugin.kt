package com.splitaway.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ReceiptScannerPlugin"
private const val REQUEST_SCAN = 9901

/**
 * ReceiptScannerPlugin — Capacitor плагин для нативного сканирования чеков.
 *
 * Поток:
 * 1. JS вызывает scanReceipt()
 * 2. Запускается ML Kit Document Scanner (автообрезка + исправление перспективы)
 * 3. handleOnActivityResult получает JPEG URI
 * 4. MlKitReceiptOcrEngine выполняет Text Recognition
 * 5. ReceiptParser разбирает структуру чека
 * 6. call.resolve(result) возвращает JSON в WebView
 *
 * Нет сетевых запросов. Нет платных API. Полностью локально.
 */
@CapacitorPlugin(name = "ReceiptScanner")
class ReceiptScannerPlugin : Plugin() {

    @Volatile private var pendingCall: PluginCall? = null

    // Движок OCR через интерфейс — легко заменить на FuturePaddleReceiptOcrEngine
    private val ocrEngine: ReceiptOcrEngine by lazy {
        MlKitReceiptOcrEngine(context)
    }

    // ─────────────────────────────────────────────────────────────
    // ШАГ 1: Запустить ML Kit Document Scanner
    // ─────────────────────────────────────────────────────────────

    @PluginMethod
    fun scanReceipt(call: PluginCall) {
        call.setKeepAlive(true)   // держим call живым пока activity не вернёт результат
        pendingCall = call
        Log.d(TAG, "scanReceipt: starting ML Kit Document Scanner [engine=${ocrEngine.engineName}]")

        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)  // full = crop + perspective + contrast
            .setGalleryImportAllowed(true)   // кнопка «Из галереи» внутри сканера
            .setPageLimit(1)                 // только один лист (чек)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                try {
                    activity.startIntentSenderForResult(intentSender, REQUEST_SCAN, null, 0, 0, 0)
                    Log.d(TAG, "startIntentSenderForResult OK")
                } catch (e: Exception) {
                    Log.e(TAG, "startIntentSenderForResult failed", e)
                    pendingCall = null
                    call.setKeepAlive(false)
                    call.reject("LAUNCH_FAILED", e.message)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "getStartScanIntent failed: ${e.message}")
                pendingCall = null
                call.setKeepAlive(false)
                call.reject("SCANNER_UNAVAILABLE", e.message)
            }
    }

    // ─────────────────────────────────────────────────────────────
    // ШАГ 2: Получить результат от Document Scanner
    // ─────────────────────────────────────────────────────────────

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "handleOnActivityResult: req=$requestCode res=$resultCode call=${pendingCall != null}")

        if (requestCode != REQUEST_SCAN) return

        val call = pendingCall ?: run {
            Log.w(TAG, "pendingCall is null — ignoring result")
            return
        }
        pendingCall = null
        call.setKeepAlive(false)

        // Пользователь нажал «Назад»
        if (resultCode == Activity.RESULT_CANCELED) {
            call.reject("USER_CANCELLED", "Отменено пользователем")
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            call.reject("SCAN_FAILED", "resultCode=$resultCode")
            return
        }

        val pages = GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages
        Log.d(TAG, "Document Scanner pages: ${pages?.size}")
        if (pages.isNullOrEmpty()) {
            call.reject("NO_PAGES", "Сканер не вернул страниц")
            return
        }

        val imageUri: Uri = pages[0].imageUri
        Log.d(TAG, "Got imageUri: $imageUri")

        // ШАГ 3+4+5: OCR + парсинг в фоновом потоке
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runOcrAndResolve(call, imageUri)
            } catch (e: Exception) {
                Log.e(TAG, "OCR coroutine crashed", e)
                call.reject("OCR_CRASH", e.message ?: "unknown error")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ШАГИ 3-5: OCR + парсинг через движок + resolve
    // ─────────────────────────────────────────────────────────────

    private suspend fun runOcrAndResolve(call: PluginCall, imageUri: Uri) {
        // Движок сам декодирует bitmap, запускает OCR и ReceiptParser
        val ocrResult = ocrEngine.recognize(
            imageUri    = imageUri,
            imageWidth  = 0,   // будет определено из bitmap
            imageHeight = 0
        )

        // Преобразуем JSONObject → JSObject (безопасно, с null-guard)
        val resultJs = safeJsonToJSObject(ocrResult.parsedResult)

        val ret = JSObject()
        ret.put("success",     true)
        ret.put("imageUri",    imageUri.toString())
        ret.put("imageWidth",  ocrResult.imageWidth)
        ret.put("imageHeight", ocrResult.imageHeight)
        ret.put("rawText",     ocrResult.rawText)
        ret.put("result",      resultJs)
        ret.put("engine",      ocrEngine.engineName)

        Log.d(TAG, "Resolving call with result: total=${ocrResult.parsedResult.opt("total")}")
        call.resolve(ret)
    }

    // ─────────────────────────────────────────────────────────────
    // ВСПОМОГАТЕЛЬНОЕ: JSONObject → JSObject (null-safe)
    // ─────────────────────────────────────────────────────────────

    /**
     * Конвертирует JSONObject в JSObject рекурсивно.
     * JSObject(string) иногда крашится на JSONObject с null-значениями,
     * поэтому обходим вручную.
     */
    private fun safeJsonToJSObject(json: JSONObject): JSObject {
        val js = JSObject()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val v = json.opt(key)) {
                null, JSONObject.NULL -> js.put(key, JSObject.NULL)
                is JSONObject        -> js.put(key, safeJsonToJSObject(v))
                is JSONArray         -> js.put(key, v)
                is Boolean           -> js.put(key, v)
                is Int               -> js.put(key, v)
                is Long              -> js.put(key, v)
                is Double            -> js.put(key, v)
                else                 -> js.put(key, v.toString())
            }
        }
        return js
    }

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun handleOnDestroy() {
        ocrEngine.close()
    }
}
