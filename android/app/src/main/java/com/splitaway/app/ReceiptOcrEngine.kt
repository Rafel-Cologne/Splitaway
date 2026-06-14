package com.splitaway.app

import android.net.Uri
import org.json.JSONObject

/**
 * ReceiptOcrEngine — интерфейс для движков OCR.
 *
 * Текущая реализация: MlKitReceiptOcrEngine (локальный, бесплатный).
 * Будущая реализация: FuturePaddleReceiptOcrEngine (для русского языка и сложных чеков).
 *
 * Все реализации:
 * - НЕ используют сетевые API (Azure, Google Cloud, Mindee и т.д.)
 * - Выполняют распознавание на устройстве
 * - Возвращают структурированный JSON совместимый с ReceiptParser
 * - Денежные значения в центах (Int) — никаких float
 */
interface ReceiptOcrEngine {

    /**
     * Выполнить OCR на изображении по URI.
     *
     * @param imageUri  Content URI изображения (от ML Kit Document Scanner или галереи)
     * @param imageWidth  Ширина изображения в пикселях (для нормализации координат)
     * @param imageHeight Высота изображения в пикселях
     * @return OcrResult с raw text blocks и распарсенным результатом
     */
    suspend fun recognize(imageUri: Uri, imageWidth: Int, imageHeight: Int): OcrResult

    /**
     * Освободить ресурсы движка (вызывается при уничтожении плагина).
     */
    fun close()

    /**
     * Человекочитаемое имя движка (для логов и отладки).
     */
    val engineName: String
}

/**
 * Результат OCR-распознавания.
 *
 * @param rawText       Полный текст из OCR (для отладки)
 * @param parsedResult  Структурированный результат из ReceiptParser
 * @param imageWidth    Ширина изображения
 * @param imageHeight   Высота изображения
 * @param imageUri      URI обработанного изображения
 */
data class OcrResult(
    val rawText: String,
    val parsedResult: JSONObject,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageUri: Uri
)
