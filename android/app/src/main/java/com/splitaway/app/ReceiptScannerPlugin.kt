package com.splitaway.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
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

private const val TAG = "ReceiptScannerPlugin"
private const val REQUEST_SCAN = 9901

/**
 * ReceiptScannerPlugin — Capacitor плагин для нативного сканирования чеков.
 *
 * Поток:
 * 1. JS вызывает scanReceipt()
 * 2. Запускается ML Kit Document Scanner (автообрезка + исправление перспективы)
 * 3. handleOnActivityResult получает JPEG URI
 * 4. Файл читается и конвертируется в base64
 * 5. call.resolve({imageBase64, mimeType, imageUri, ...}) возвращает данные в WebView
 * 6. Frontend вызывает Eagle Doc OCR через /api/receipts/analyze
 *
 * OCR выполняется на backend — API-ключ никогда не попадает в APK.
 */
@CapacitorPlugin(name = "ReceiptScanner")
class ReceiptScannerPlugin : Plugin() {

    @Volatile private var pendingCall: PluginCall? = null

    // ─────────────────────────────────────────────────────────────
    // ШАГ 1: Запустить ML Kit Document Scanner
    // ─────────────────────────────────────────────────────────────

    @PluginMethod
    fun scanReceipt(call: PluginCall) {
        call.setKeepAlive(true)   // держим call живым пока activity не вернёт результат
        pendingCall = call
        Log.d(TAG, "scanReceipt: starting ML Kit Document Scanner")

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

        // ШАГ 3: Читаем файл и конвертируем в base64 в фоновом потоке
        CoroutineScope(Dispatchers.IO).launch {
            try {
                readImageAndResolve(call, imageUri)
            } catch (e: Throwable) {
                Log.e(TAG, "Image read crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                call.reject("READ_FAILED", "${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ШАГ 3: Читаем изображение → base64 → resolve
    // ─────────────────────────────────────────────────────────────

    private fun readImageAndResolve(call: PluginCall, imageUri: Uri) {
        val inputStream = context.contentResolver.openInputStream(imageUri)
            ?: throw IllegalStateException("Cannot open stream for $imageUri")
        val bytes = inputStream.readBytes()
        inputStream.close()

        val imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Log.d(TAG, "Image read OK: ${bytes.size} bytes → ${imageBase64.length} base64 chars")

        val ret = JSObject()
        ret.put("success",       true)
        ret.put("imageBase64",   imageBase64)
        ret.put("mimeType",      "image/jpeg")
        ret.put("imageUri",      imageUri.toString())
        ret.put("fileSizeBytes", bytes.size)

        call.resolve(ret)
    }

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun handleOnDestroy() {
        // no resources to release (OCR engine removed)
    }
}
