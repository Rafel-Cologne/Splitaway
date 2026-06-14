package com.splitaway.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ReceiptScannerPlugin"
private const val REQUEST_SCAN = 9901

@CapacitorPlugin(name = "ReceiptScanner")
class ReceiptScannerPlugin : Plugin() {

    @Volatile private var pendingCall: PluginCall? = null

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    @PluginMethod
    fun scanReceipt(call: PluginCall) {
        call.setKeepAlive(true)          // <-- держим call живым пока не resolve/reject
        pendingCall = call
        Log.d(TAG, "scanReceipt: pendingCall set, starting scanner")

        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
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

    override fun handleOnActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.handleOnActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "handleOnActivityResult: req=$requestCode res=$resultCode pendingCall=${pendingCall != null}")

        if (requestCode != REQUEST_SCAN) return

        val call = pendingCall ?: run {
            Log.w(TAG, "pendingCall is null — ignoring result")
            return
        }
        pendingCall = null
        call.setKeepAlive(false)

        if (resultCode == Activity.RESULT_CANCELED) {
            call.reject("USER_CANCELLED", "Отменено")
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            call.reject("SCAN_FAILED", "resultCode=$resultCode")
            return
        }

        val pages = GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages
        Log.d(TAG, "pages count: ${pages?.size}")
        if (pages.isNullOrEmpty()) {
            call.reject("NO_PAGES", "Нет страниц")
            return
        }

        val imageUri: Uri = pages[0].imageUri
        Log.d(TAG, "imageUri: $imageUri")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                runOcrAndParse(call, imageUri)
            } catch (e: Exception) {
                Log.e(TAG, "Uncaught exception in OCR coroutine", e)
                call.reject("OCR_CRASH", e.message ?: "unknown error")
            }
        }
    }

    private suspend fun runOcrAndParse(call: PluginCall, imageUri: Uri) {
        // 1. Декодируем bitmap
        Log.d(TAG, "Decoding bitmap...")
        val bitmap = context.contentResolver.openInputStream(imageUri)?.use { s ->
            BitmapFactory.decodeStream(s)
        }
        if (bitmap == null) {
            Log.e(TAG, "Bitmap decode returned null")
            call.reject("DECODE_FAILED", "Не удалось декодировать изображение")
            return
        }
        Log.d(TAG, "Bitmap decoded: ${bitmap.width}x${bitmap.height}")

        // 2. ML Kit Text Recognition
        Log.d(TAG, "Running OCR...")
        val visionText = textRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        Log.d(TAG, "OCR done, blocks=${visionText.textBlocks.size}")
        Log.d(TAG, "=== RAW OCR TEXT ===\n${visionText.text}\n===================")

        // 3. Собираем блоки
        val blocks = JSONArray()
        for (block in visionText.textBlocks) {
            val linesArr = JSONArray()
            for (line in block.lines) {
                val lb = line.boundingBox
                linesArr.put(JSONObject().apply {
                    put("text",       line.text)
                    put("left",       lb?.left   ?: 0)
                    put("top",        lb?.top    ?: 0)
                    put("right",      lb?.right  ?: bitmap.width)
                    put("bottom",     lb?.bottom ?: 0)
                    put("confidence", line.confidence?.toDouble() ?: 0.9)
                    put("elements",   JSONArray())
                })
            }
            val bb = block.boundingBox
            blocks.put(JSONObject().apply {
                put("text",   block.text)
                put("left",   bb?.left   ?: 0)
                put("top",    bb?.top    ?: 0)
                put("right",  bb?.right  ?: bitmap.width)
                put("bottom", bb?.bottom ?: 0)
                put("lines",  linesArr)
            })
        }

        // 4. Парсим чек
        val imgW = bitmap.width
        val imgH = bitmap.height
        Log.d(TAG, "Parsing receipt...")
        val parsed = ReceiptParser.parse(blocks, imgW, imgH)
        Log.d(TAG, "Parsed: $parsed")

        bitmap.recycle()

        // 5. Формируем ответ
        val resultJs = safeJsonToJSObject(parsed)

        val ret = JSObject()
        ret.put("success",     true)
        ret.put("imageUri",    imageUri.toString())
        ret.put("imageWidth",  imgW)
        ret.put("imageHeight", imgH)
        ret.put("rawText",     visionText.text)
        ret.put("result",      resultJs)

        Log.d(TAG, "Calling call.resolve()")
        call.resolve(ret)
        Log.d(TAG, "call.resolve() done")
    }

    /**
     * Конвертирует JSONObject в JSObject, заменяя null → JSObject.NULL-safe строки.
     * JSObject(string) иногда падает на JSONObject с null-значениями.
     */
    private fun safeJsonToJSObject(json: JSONObject): JSObject {
        val js = JSObject()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val v = json.opt(key)) {
                null, JSONObject.NULL -> js.put(key, JSObject.NULL)
                is JSONObject        -> js.put(key, safeJsonToJSObject(v))
                is JSONArray         -> js.put(key, v)   // массивы передаём как есть
                is Boolean           -> js.put(key, v)
                is Int               -> js.put(key, v)
                is Long              -> js.put(key, v)
                is Double            -> js.put(key, v)
                else                 -> js.put(key, v.toString())
            }
        }
        return js
    }

    override fun handleOnDestroy() {
        textRecognizer.close()
    }
}
