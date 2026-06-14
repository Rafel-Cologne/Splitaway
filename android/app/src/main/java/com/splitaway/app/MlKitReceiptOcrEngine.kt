package com.splitaway.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "MlKitOcrEngine"

/**
 * MlKitReceiptOcrEngine — реализация ReceiptOcrEngine на базе Google ML Kit.
 *
 * Использует:
 * - ML Kit Text Recognition v2 (Latin script: DE/EN/ES/IT/FR)
 * - ReceiptParser для структурированного разбора
 *
 * Полностью локальное выполнение на устройстве.
 * Не требует интернета. Бесплатно.
 *
 * Рекомендуемое минимальное разрешение входного изображения: 1200px по ширине.
 * ML Kit Document Scanner уже обеспечивает это при съёмке.
 */
class MlKitReceiptOcrEngine(private val context: Context) : ReceiptOcrEngine {

    override val engineName: String = "ML Kit Text Recognition v2 (Latin)"

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(imageUri: Uri, imageWidth: Int, imageHeight: Int): OcrResult {
        Log.d(TAG, "[$engineName] Starting OCR on $imageUri")

        // 1. Декодируем bitmap из content URI
        val bitmap = context.contentResolver.openInputStream(imageUri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalStateException("Cannot decode bitmap from URI: $imageUri")

        Log.d(TAG, "Bitmap decoded: ${bitmap.width}x${bitmap.height}")

        // Сохраняем размеры до recycle()
        val actualWidth  = bitmap.width
        val actualHeight = bitmap.height

        // 2. Запускаем ML Kit OCR
        Log.d(TAG, "Running ML Kit text recognition...")
        val visionText = textRecognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        Log.d(TAG, "OCR complete. Blocks: ${visionText.textBlocks.size}")
        Log.d(TAG, "=== RAW OCR TEXT ===\n${visionText.text}\n===================")

        // 3. Преобразуем результат в JSONArray blocks для ReceiptParser
        val blocks = JSONArray()
        for (block in visionText.textBlocks) {
            val linesArr = JSONArray()
            for (line in block.lines) {
                val lb = line.boundingBox
                linesArr.put(JSONObject().apply {
                    put("text",       line.text)
                    put("left",       lb?.left   ?: 0)
                    put("top",        lb?.top    ?: 0)
                    put("right",      lb?.right  ?: actualWidth)
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
                put("right",  bb?.right  ?: actualWidth)
                put("bottom", bb?.bottom ?: 0)
                put("lines",  linesArr)
            })
        }

        bitmap.recycle()

        // 4. Парсим структуру чека
        Log.d(TAG, "Parsing receipt structure...")
        val parsedResult = ReceiptParser.parse(blocks, actualWidth, actualHeight)
        Log.d(TAG, "Parse complete: $parsedResult")

        return OcrResult(
            rawText       = visionText.text,
            parsedResult  = parsedResult,
            imageWidth    = actualWidth,
            imageHeight   = actualHeight,
            imageUri      = imageUri
        )
    }

    override fun close() {
        Log.d(TAG, "[$engineName] Closing text recognizer")
        textRecognizer.close()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Заглушка для будущего PaddleOCR (русский язык, сложные чеки)
// Не реализована — подключить отдельно когда потребуется.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * FuturePaddleReceiptOcrEngine — будущая реализация на базе PaddleOCR.
 *
 * Подходит для:
 * - Кириллических чеков (русский, украинский, болгарский)
 * - Сложных шрифтов и плохого качества сканирования
 *
 * НЕ реализована в текущей версии.
 * Раскомментировать и реализовать когда потребуется поддержка русского языка.
 */
/*
class FuturePaddleReceiptOcrEngine(private val context: Context) : ReceiptOcrEngine {
    override val engineName = "PaddleOCR (Future)"

    override suspend fun recognize(imageUri: Uri, imageWidth: Int, imageHeight: Int): OcrResult {
        throw UnsupportedOperationException("PaddleOCR not yet implemented. Use MlKitReceiptOcrEngine.")
    }

    override fun close() { }
}
*/
