package com.splitaway.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * ReceiptParser — локальный парсер чека.
 *
 * Принимает ML Kit TextBlocks (JSON) с bounding boxes.
 * Возвращает SplitawayReceiptResult JSON:
 * {
 *   merchant, date, currency, items[], subtotal, tax, total,
 *   overallConfidence, warnings[], provider
 * }
 *
 * Денежные значения хранятся в CENTS (Int) чтобы избежать float-ошибок.
 * Для JSON-вывода конвертируются в Double (cents / 100.0).
 */
object ReceiptParser {

    // ─────────────────────────────────────────────────────────────
    // СЛОВАРИ СЛУЖЕБНЫХ ТЕРМИНОВ (DE / EN / ES)
    // ─────────────────────────────────────────────────────────────

    private val SKIP_KEYWORDS = setOf(
        // German
        "beleg", "belegnummer", "beleg-nr", "bel.-nr", "bel.nr",
        "rechnung", "rechnungs", "rechnungsnr", "rechnungsnummer",
        "datum", "uhrzeit", "uhr",
        "terminal", "terminalid", "terminal-id",
        "transaktion", "transaktionsnr", "transaction",
        "kartenzahlung", "kreditkarte", "girocard", "ec", "ec-karte",
        "mwst", "mehrwertsteuer", "ust", "umsatzsteuer",
        "netto", "brutto", "steuer",
        "summe", "gesamt", "gesamtbetrag", "gesamtsumme",
        "zwischensumme", "endbetrag",
        "rückgeld", "rueckgeld", "wechselgeld",
        "kundenbeleg", "händlerbeleg", "haendlerbeleg",
        "kassierer", "kasse", "kassennr",
        "iban", "bic",
        "tse", "tse-nr",
        "bon", "bon-nr", "bonnr",
        "quittung", "kassenbon",
        "trace", "genehmigung", "autorisierung",
        "paywave", "contactless", "debit", "kredit",
        "telefon", "tel", "telefonnummer",
        "echo", "tankstellen", "zapfs",
        // English
        "receipt", "invoice",
        "date", "time",
        "vat", "tax", "subtotal", "total",
        "change", "cash", "card",
        "cashier", "server", "table",
        "thank", "thanks",
        // Spanish
        "recibo", "factura",
        "fecha", "hora",
        "iva", "impuesto",
        "cambio", "caja", "cajero"
    )

    // Ключевые слова ИТОГА (для поиска total, сортируем по приоритету)
    private val TOTAL_KEYWORDS = listOf(
        "gesamtbetrag", "gesamt", "gesamtsumme", "endbetrag",
        "summe", "total", "importe total", "total a pagar",
        "zu zahlen", "betrag"
    )

    // Ключевые слова которые НЕ являются итогом (налог, промежуточные)
    private val NOT_TOTAL_KEYWORDS = setOf(
        "netto", "mwst", "ust", "steuer", "vat", "tax",
        "zwischensumme", "subtotal", "rückgeld", "rueckgeld",
        "trinkgeld", "anzahlung", "rabatt", "discount"
    )

    // ─────────────────────────────────────────────────────────────
    // ПАРСИНГ ЦЕНЫ
    // ─────────────────────────────────────────────────────────────

    // Паттерн цены: 1.234,56 или 1,234.56 или 12,56 или 12.56 или 12
    // Возвращает цену в центах или null
    private val PRICE_PATTERN = Regex(
        """(?<![0-9:/])(\d{1,4}(?:[.,]\d{3})*[.,]\d{2}|\d{1,4}[.,]\d{2}|\d{1,3})(?:\s*(?:EUR?|€))?(?!\d)"""
    )

    // Паттерны которые НЕ являются ценой
    private val DATE_PATTERN    = Regex("""\d{2}[./]\d{2}[./]\d{2,4}""")
    private val TIME_PATTERN    = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
    private val PHONE_PATTERN   = Regex("""\+?\d[\d\s\-]{7,}""")
    private val POSTCODE_PATTERN = Regex("""\b\d{5}\b""")  // German PLZ
    private val LONG_NUMBER_PATTERN = Regex("""\d{6,}""")  // длинные номера
    // Единичные цены типа "1,895 EUR/l" (3 знака после запятой = цена за литр/кг)
    // НЕ матчит "1.234,56" (тысячный + десятичный разделитель)
    private val UNIT_PRICE_3DEC = Regex("""(?<![,.])\d{1,4}[.,]\d{3}(?![,.\d])""")

    /**
     * Распарсить строку в центы. Возвращает null если не похоже на цену.
     * Реальный диапазон цены товара: 0.10€ – 999.99€ (10 – 99999 центов)
     */
    fun parsePriceCents(text: String): Int? {
        val t = text.trim()

        // Исключить если это дата, время, телефон, PLZ
        if (DATE_PATTERN.containsMatchIn(t)) return null
        if (TIME_PATTERN.containsMatchIn(t)) return null
        if (POSTCODE_PATTERN.containsMatchIn(t) && t.length <= 6) return null
        // Исключить единичные цены "1,895" (EUR/l) — 3 знака после запятой без второго разделителя
        if (UNIT_PRICE_3DEC.containsMatchIn(t)) return null

        val m = PRICE_PATTERN.find(t) ?: return null
        val raw = m.groupValues[1]

        // Нормализуем разделитель: немецкий формат "26,87" или "1.234,56"
        val normalized = when {
            raw.contains(',') && raw.contains('.') -> {
                // Тысячный разделитель . и десятичный ,  →  "1.234,56" → 1234.56
                raw.replace(".", "").replace(",", ".")
            }
            raw.contains(',') -> raw.replace(",", ".")
            else -> raw
        }

        val value = normalized.toDoubleOrNull() ?: return null
        val cents = (value * 100).toInt()

        // Реалистичный диапазон: 10 центов – 99999 центов (0.10€ – 999.99€)
        if (cents < 10 || cents > 99999) return null

        // Отдельно исключить длинные номера которые случайно попали
        if (LONG_NUMBER_PATTERN.containsMatchIn(raw) && !raw.contains(',') && !raw.contains('.')) return null

        return cents
    }

    // ─────────────────────────────────────────────────────────────
    // ФИЛЬТРАЦИЯ МУСОРА
    // ─────────────────────────────────────────────────────────────

    fun isGarbageText(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return true

        val letters = t.count { it.isLetter() }
        val nonSpace = t.count { !it.isWhitespace() }
        if (nonSpace == 0) return true

        // < 20% букв → мусор
        if (letters.toDouble() / nonSpace < 0.20) return true

        // Слишком много спец-символов
        val specials = t.count { it in "@©<>~_=*|\\{}#" }
        if (nonSpace > 3 && specials.toDouble() / nonSpace > 0.40) return true

        // Все слова из одного символа
        val words = t.trim().split(Regex("\\s+"))
        if (words.size >= 3 && words.count { it.length == 1 } > words.size / 2) return true

        // Все слова короткие (< 4 символов) + хотя бы одно «слово» состоит только из спецсимволов
        // Пример мусора: "% ke os.", "# A B", "© km st"
        if (words.size >= 2) {
            val longMeaningfulWords = words.count { w -> w.length >= 4 && w.count { c -> c.isLetter() } >= 2 }
            val pureSymbolWords    = words.count { w -> w.none { c -> c.isLetterOrDigit() } }
            if (longMeaningfulWords == 0 && pureSymbolWords >= 1) return true
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────
    // СЛУЖЕБНАЯ СТРОКА?
    // ─────────────────────────────────────────────────────────────

    fun isSkipLine(text: String): Boolean {
        val lower = text.lowercase().trim()
        // Точное совпадение или начинается с ключевого слова
        for (kw in SKIP_KEYWORDS) {
            if (lower == kw || lower.startsWith("$kw ") || lower.startsWith("$kw:") ||
                lower.startsWith("$kw-") || lower.contains("$kw ") ) {
                return true
            }
        }
        // Строка содержит только ©, *, # и прочее
        if (lower.matches(Regex("[^a-zа-я0-9]+"))) return true
        return false
    }

    // ─────────────────────────────────────────────────────────────
    // ДЕТЕКЦИЯ ИТОГА
    // ─────────────────────────────────────────────────────────────

    data class TotalCandidate(
        val cents: Int,
        val priority: Int,   // меньше = лучше (индекс в TOTAL_KEYWORDS)
        val topPx: Int       // вертикальная позиция в изображении
    )

    // ─────────────────────────────────────────────────────────────
    // ГЛАВНАЯ ФУНКЦИЯ
    // ─────────────────────────────────────────────────────────────

    /**
     * @param blocks JSONArray из ReceiptScannerPlugin (ML Kit TextBlocks)
     * @param imageWidth ширина изображения в пикселях
     * @param imageHeight высота изображения в пикселях
     */
    fun parse(blocks: JSONArray, imageWidth: Int, imageHeight: Int): JSONObject {
        // ── 1. Собираем все строки с координатами ──
        data class Line(
            val text: String,
            val left: Int, val top: Int, val right: Int, val bottom: Int,
            val confidence: Double
        )

        val allLines = mutableListOf<Line>()
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            val linesArr = block.getJSONArray("lines")
            for (j in 0 until linesArr.length()) {
                val l = linesArr.getJSONObject(j)
                allLines.add(
                    Line(
                        text       = l.getString("text"),
                        left       = l.optInt("left", 0),
                        top        = l.optInt("top", 0),
                        right      = l.optInt("right", imageWidth),
                        bottom     = l.optInt("bottom", 0),
                        confidence = l.optDouble("confidence", 0.8)
                    )
                )
            }
        }

        // Сортируем по вертикали
        allLines.sortBy { it.top }

        // ── 2. Ищем итог ──
        var totalCents: Int? = null
        var totalLineTop = imageHeight  // позиция найденного итога

        val totalCandidates = mutableListOf<TotalCandidate>()

        for (line in allLines) {
            val lower = line.text.lowercase()
            val priorityIdx = TOTAL_KEYWORDS.indexOfFirst { lower.contains(it) }
            if (priorityIdx < 0) continue
            // Убедиться что это не NOT_TOTAL
            if (NOT_TOTAL_KEYWORDS.any { lower.contains(it) }) continue

            // Ищем цену в этой строке и соседних (±30px)
            val nearby = allLines.filter { abs(it.top - line.top) < 30 }
            for (nl in nearby) {
                val cents = parsePriceCents(nl.text) ?: continue
                totalCandidates.add(TotalCandidate(cents, priorityIdx, line.top))
            }
        }

        // Выбираем лучший кандидат: приоритет по ключевому слову, затем снизу чека
        val best = totalCandidates.minByOrNull { it.priority * 100000 - it.topPx }
        if (best != null) {
            totalCents = best.cents
            totalLineTop = best.topPx
        }

        // ── 3. Ищем название магазина (первые строки чека) ──
        var merchant: String? = null
        val topLines = allLines.filter { it.top < imageHeight * 0.20 }.take(5)
        for (l in topLines) {
            val t = l.text.trim()
            if (t.length < 3 || isGarbageText(t) || isSkipLine(t)) continue
            // Если строка не содержит цену → вероятно название
            if (parsePriceCents(t) == null) {
                merchant = t
                break
            }
        }

        // ── 4. Ищем дату ──
        var date: String? = null
        for (l in allLines) {
            val m = DATE_PATTERN.find(l.text)
            if (m != null) {
                date = m.value
                break
            }
        }

        // ── 5. Ищем товарные позиции ──
        // Товарные строки: до строки с итогом, не служебные, с ценой справа
        val items = mutableListOf<JSONObject>()

        for (line in allLines) {
            // Не обрабатывать строки ниже итога
            if (totalCents != null && line.top > totalLineTop + 10) continue

            val text = line.text.trim()
            if (text.length < 2) continue
            if (isGarbageText(text)) continue
            if (isSkipLine(text)) continue

            // Дата и время не являются товарами
            if (DATE_PATTERN.containsMatchIn(text) || TIME_PATTERN.containsMatchIn(text)) continue

            // Ищем цену в строке
            // Стратегия: ищем число в правой части строки (right > 55% ширины)
            // Или просто число в конце строки
            val priceMatch = findPriceInLine(text) ?: continue
            val priceCents = priceMatch.cents
            val nameText   = priceMatch.name.trim()

            if (nameText.isEmpty() || nameText.length < 2) continue
            if (isSkipLine(nameText)) continue
            if (isGarbageText(nameText)) continue

            // Убрать случайные символы и мусор из названия
            val cleanName = nameText
                .replace(Regex("[©*#|\\\\{}]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleanName.length < 2) continue

            val itemObj = JSONObject()
            itemObj.put("name",       cleanName)
            itemObj.put("quantity",   1)
            itemObj.put("totalPrice", priceCents / 100.0)
            itemObj.put("unitPrice",  priceCents / 100.0)
            itemObj.put("confidence", line.confidence)
            itemObj.put("needsReview", cleanName.isEmpty() || line.confidence < 0.7)
            itemObj.put("rawText",    text)
            items.add(itemObj)
        }

        // ── 6. Проверка суммы ──
        val warnings = JSONArray()
        val itemsTotal = items.sumOf { it.getDouble("totalPrice") }
        val itemsTotalCents = (itemsTotal * 100).toInt()

        if (totalCents != null && items.isNotEmpty()) {
            if (abs(itemsTotalCents - totalCents) > 5) {  // 5 центов допуск
                val w = JSONObject()
                w.put("code", "TOTAL_MISMATCH")
                w.put("expected", totalCents / 100.0)
                w.put("calculated", itemsTotalCents / 100.0)
                warnings.put(w)
            }
        }

        // Если нет позиций но есть итог — добавить предупреждение
        if (items.isEmpty() && totalCents != null) {
            val w = JSONObject()
            w.put("code", "NO_ITEMS_FOUND_TOTAL_KNOWN")
            w.put("total", totalCents / 100.0)
            warnings.put(w)
        }

        // ── 7. Формируем результат ──
        val result = JSONObject()
        result.put("provider",          "mlkit")
        result.put("merchant",          merchant)
        result.put("date",              date)
        result.put("currency",          "EUR")
        result.put("items",             JSONArray(items.map { it }))
        result.put("subtotal",          null)
        result.put("tax",               null)
        result.put("total",             totalCents?.let { it / 100.0 })
        result.put("overallConfidence", if (items.isEmpty()) 0.0 else items.map { it.getDouble("confidence") }.average())
        result.put("warnings",          warnings)

        return result
    }

    // ─────────────────────────────────────────────────────────────
    // ВСПОМОГАТЕЛЬНЫЙ: найти цену в строке и отделить название
    // ─────────────────────────────────────────────────────────────

    data class PriceInLine(val name: String, val cents: Int)

    /**
     * Ищет цену в строке вида "Super 95    26,87 EUR A #*("
     * Возвращает {name, cents} или null.
     *
     * Паттерны:
     * 1) "Name   26,87 EUR"
     * 2) "*000005 Super 95   26,87 EUR A #*("  — газовая станция Esso
     * 3) "Name - 12,90"
     * 4) "Name 5,00 A"  — буква налоговой группы после цены
     */
    fun findPriceInLine(text: String): PriceInLine? {
        // Предобработка: убрать ведущие * # ©
        var t = text.trimStart { !it.isLetterOrDigit() }.trim()

        // Убрать суффиксы после цены: " A", " B", " A #*(", " EUR A"
        // Паттерн: ЦЕНА пробел? буква(необязательно) пробел? мусор_до_конца
        val withSuffix = Regex(
            """(.+?)\s{1,6}(\d{1,4}[.,]\d{2})\s*(?:EUR?)?\s*[A-Z]?\s*[#*()\[\]]*\s*$"""
        )

        // Паттерн для газовой станции: "*000005 Super 95  26,87 EUR A #*("
        val essoPattern = Regex(
            """^\*?\d{5,}\s+(.+?)\s{1,6}(\d{1,4}[.,]\d{2})\s*(?:EUR?)?\s*[A-Z]?\s*.*$"""
        )

        var matchResult: MatchResult? = essoPattern.matchEntire(t)
        if (matchResult != null) {
            val name  = matchResult.groupValues[1].trim()
            val price = parsePriceCents(matchResult.groupValues[2]) ?: return null
            return PriceInLine(name, price)
        }

        matchResult = withSuffix.matchEntire(t)
        if (matchResult != null) {
            val name  = matchResult.groupValues[1].trim()
            val price = parsePriceCents(matchResult.groupValues[2]) ?: return null
            if (isSkipLine(name) || isGarbageText(name)) return null
            return PriceInLine(name, price)
        }

        // Простой fallback: последнее число в строке
        val allMatches = PRICE_PATTERN.findAll(t).toList()
        if (allMatches.isEmpty()) return null

        val lastMatch = allMatches.last()
        val priceCents = parsePriceCents(lastMatch.value) ?: return null
        val name = t.substring(0, lastMatch.range.first).trim()
            .trimEnd { !it.isLetterOrDigit() }.trim()

        if (name.length < 2) return null
        if (isSkipLine(name) || isGarbageText(name)) return null

        return PriceInLine(name, priceCents)
    }
}
