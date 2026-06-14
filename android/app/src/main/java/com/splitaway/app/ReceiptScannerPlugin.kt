package com.splitaway.app

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ReceiptScannerPlugin"

@CapacitorPlugin(name = "ReceiptScanner")
class ReceiptScannerPlugin : Plugin() {

    // Один экземпляр распознавателя текста (Latin script)
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Главный метод: запускает Document Scanner → распознаёт текст → парсит чек.
     * Вызов из JS: ReceiptScannerPlugin.scanReceipt()
     */
    @PluginMethod
    fun scanReceipt(call: PluginCall) {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)       // «Из галереи» тоже через сканер
                .setPageLimit(1)                      // Один чек за раз
                .setResultFormats(
                    GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                    GmsDocumentScannerOptions.RESULT_FORMAT_PDF
                )
                .build()

            val scanner: GmsDocumentScanner = GmsDocumentScanning.getClient(options)

            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    startActivityForResult(call, intentSender, "handleScanResult")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Document Scanner init failed", e)
                    call.reject("SCANNER_INIT_FAILED", e.message)
                }
        } catch (e: Exception) {
            Log.e(TAG, "scanReceipt error", e)
            call.reject("SCAN_ERROR", e.message)
        }
    }

    /**
     * Callback после закрытия Document Scanner.
     * Получаем URI отсканированного JPEG → OCR → парсинг → JSON во WebView.
     */
    @ActivityCallback
    private fun handleScanResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return

        when (result.resultCode) {
            Activity.RESULT_CANCELED -> {
                call.reject("USER_CANCELLED", "Пользователь отменил сканирование")
                return
            }
            Activity.RESULT_OK -> { /* продолжаем */ }
            else -> {
                call.reject("SCAN_FAILED", "resultCode=${result.resultCode}")
                return
            }
        }

        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pages = scanResult?.pages
        if (pages.isNullOrEmpty()) {
            call.reject("NO_PAGES", "Документ не содержит страниц")
            return
        }

        val imageUri: Uri = pages[0].imageUri

        // Запускаем OCR в фоновом потоке через coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Шаг 1: Декодируем bitmap из URI
                val bitmap = context.contentResolver.openInputStream(imageUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: run {
                    call.reject("BITMAP_DECODE_FAILED", "Не удалось декодировать изображение")
                    return@launch
                }

                Log.d(TAG, "Bitmap size: ${bitmap.width}×${bitmap.height}")

                // Шаг 2: ML Kit Text Recognition
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = textRecognizer.process(inputImage).await()

                Log.d(TAG, "OCR blocks: ${visionText.textBlocks.size}")
                Log.d(TAG, "OCR full text:\n${visionText.text}")

                // Шаг 3: Структурируем блоки с координатами
                val blocks = JSONArray()
                for (block in visionText.textBlocks) {
                    val blockObj = JSONObject()
                    blockObj.put("text", block.text)
                    val blockBound = block.boundingBox
                    if (blockBound != null) {
                        blockObj.put("left",   blockBound.left)
                        blockObj.put("top",    blockBound.top)
                        blockObj.put("right",  blockBound.right)
                        blockObj.put("bottom", blockBound.bottom)
                    }
                    val linesArr = JSONArray()
                    for (line in block.lines) {
                        val lineObj = JSONObject()
                        lineObj.put("text", line.text)
                        val lb = line.boundingBox
                        if (lb != null) {
                            lineObj.put("left",   lb.left)
                            lineObj.put("top",    lb.top)
                            lineObj.put("right",  lb.right)
                            lineObj.put("bottom", lb.bottom)
                        }
                        lineObj.put("confidence", line.confidence?.toDouble() ?: 0.0)
                        val elemArr = JSONArray()
                        for (elem in line.elements) {
                            val elemObj = JSONObject()
                            elemObj.put("text", elem.text)
                            val eb = elem.boundingBox
                            if (eb != null) {
                                elemObj.put("left",   eb.left)
                                elemObj.put("top",    eb.top)
                                elemObj.put("right",  eb.right)
                                elemObj.put("bottom", eb.bottom)
                            }
                            elemArr.put(elemObj)
                        }
                        lineObj.put("elements", elemArr)
                        linesArr.put(lineObj)
                    }
                    blockObj.put("lines", linesArr)
                    blocks.put(blockObj)
                }

                // Шаг 4: Парсим чек
                val imageWidth = bitmap.width
                val imageHeight = bitmap.height
                val parsed = ReceiptParser.parse(blocks, imageWidth, imageHeight)

                Log.d(TAG, "Parsed result: $parsed")

                // Шаг 5: Возвращаем во WebView
                val ret = JSObject()
                ret.put("success", true)
                ret.put("imageUri", imageUri.toString())
                ret.put("imageWidth", imageWidth)
                ret.put("imageHeight", imageHeight)
                ret.put("rawText", visionText.text)
                ret.put("result", JSObject(parsed.toString()))

                bitmap.recycle()
                call.resolve(ret)

            } catch (e: Exception) {
                Log.e(TAG, "OCR/parse error", e)
                call.reject("OCR_FAILED", e.message)
            }
        }
    }

    override fun handleOnDestroy() {
        textRecognizer.close()
    }
}
