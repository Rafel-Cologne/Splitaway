package com.splitaway.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit-тесты для ReceiptParser.
 * Запуск: ./gradlew :app:test  (или Run в Android Studio)
 * Все тесты работают на JVM без Android-зависимостей.
 */
class ReceiptParserTest {

    // ─────────────────────────────────────────────────────────────
    // Вспомогательные функции для построения тестовых данных
    // ─────────────────────────────────────────────────────────────

    /** Строим JSONArray blocks из пар (text, left, top, right, bottom) */
    private fun buildBlocks(vararg lines: Triple<String, IntArray, Double>): JSONArray {
        val blocks = JSONArray()
        for ((text, bbox, conf) in lines) {
            val lineObj = JSONObject().apply {
                put("text",       text)
                put("left",       bbox[0])
                put("top",        bbox[1])
                put("right",      bbox[2])
                put("bottom",     bbox[3])
                put("confidence", conf)
                put("elements",   JSONArray())
            }
            val linesArr = JSONArray().put(lineObj)
            val blockObj = JSONObject().apply {
                put("text",  text)
                put("left",  bbox[0]); put("top", bbox[1]); put("right", bbox[2]); put("bottom", bbox[3])
                put("lines", linesArr)
            }
            blocks.put(blockObj)
        }
        return blocks
    }

    private fun line(text: String, top: Int, conf: Double = 0.95) =
        Triple(text, intArrayOf(10, top, 500, top + 25), conf)

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 1: Чек Esso — итог 26,87 €
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 1 - Esso receipt total 26 87`() {
        val blocks = buildBlocks(
            line("Esso Station", 20),
            line("Ogur Kirkkav", 50),
            line("Duererstr. 267", 75),
            line("50935 Koeln", 100),
            line("Tel.: 0221/435057", 130),
            line("Datum      Uhrzeit      Belegnummer", 200),
            line("07.06.2026  19:54   7090/00001/007", 225),
            line("Rechnung", 280),
            line("*000005 Super 95   26,87 EUR A #*(", 310),
            line("*10 03    14,15 l   1,895 EUR/l  T", 340),
            line("Gesamtbetrag           26,87 EUR",    400),
            line("Echo Tankstellen GmbH 27/166/01115 (#)", 450),
            line("Typ     Netto    MwSt   Brutto",  500),
            line("A 19%   22,58    4,29   26,87",   525),
            line("** Kundenbeleg **",                570),
            line("Datum: 07.06.2026",                600),
            line("Uhrzeit: 19:54:00",                625)
        )

        val result = ReceiptParser.parse(blocks, 600, 700)

        // Итог
        val total = result.optDouble("total", 0.0)
        assertEquals("Итог должен быть 26.87", 26.87, total, 0.01)

        // Название магазина
        val merchant = result.optString("merchant")
        assertTrue("Merchant должен содержать Esso", merchant.contains("Esso", ignoreCase = true))

        // Дата
        val date = result.optString("date")
        assertTrue("Дата должна быть 07.06.2026", date.contains("07.06.2026") || date.contains("07/06/2026"))

        // Позиции: должен быть Super 95
        val items = result.getJSONArray("items")
        assertTrue("Должна быть хотя бы одна позиция", items.length() > 0)

        val names = (0 until items.length()).map { items.getJSONObject(it).getString("name") }
        val hasSuper95 = names.any { it.contains("Super 95", ignoreCase = true) || it.contains("Super", ignoreCase = true) }
        assertTrue("Должен содержать Super 95, найдено: $names", hasSuper95)
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 2: Belegnummer не является товаром
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 2 - Belegnummer is not an item`() {
        val blocks = buildBlocks(
            line("Belegnummer: 7090/00001/007", 100),
            line("Super 95   26,87 EUR", 200),
            line("Gesamtbetrag  26,87", 300)
        )
        val result = ReceiptParser.parse(blocks, 600, 400)
        val items = result.getJSONArray("items")
        val names = (0 until items.length()).map { items.getJSONObject(it).getString("name").lowercase() }
        assertFalse("Belegnummer не должен быть товаром", names.any { it.contains("beleg") })
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 3: Дата 07.06.2026 не является ценой
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 3 - Date is not a price`() {
        val cents = ReceiptParser.parsePriceCents("07.06.2026")
        assertNull("07.06.2026 не должно распознаваться как цена", cents)
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 4: Время 19:54 не является ценой
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 4 - Time is not a price`() {
        val cents = ReceiptParser.parsePriceCents("19:54")
        assertNull("19:54 не должно распознаваться как цена", cents)
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 5: MwSt 4,29 не является товарной позицией
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 5 - MwSt is not an item`() {
        val blocks = buildBlocks(
            line("Super 95   26,87", 100),
            line("MwSt 19%   4,29", 200),
            line("Gesamtbetrag  26,87", 300)
        )
        val result = ReceiptParser.parse(blocks, 600, 400)
        val items = result.getJSONArray("items")
        val names = (0 until items.length()).map { items.getJSONObject(it).getString("name").lowercase() }
        assertFalse("MwSt не должен быть товаром", names.any { it.contains("mwst") || it.contains("steuer") })
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 6: Netto 22,58 не является товарной позицией
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 6 - Netto is not an item`() {
        val result = ReceiptParser.isSkipLine("Netto 22,58")
        assertTrue("Netto должен фильтроваться", result)
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 7: Итог извлекается корректно из "Gesamtbetrag 26,87 EUR"
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 7 - Total extracted correctly`() {
        val blocks = buildBlocks(
            line("Artikel   5,00", 100),
            line("Gesamtbetrag   26,87 EUR", 300)
        )
        val result = ReceiptParser.parse(blocks, 600, 400)
        val total = result.optDouble("total", 0.0)
        assertEquals("Итог 26.87", 26.87, total, 0.01)
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 8: Пустой список позиций при найденном total → NO_ITEMS_FOUND_TOTAL_KNOWN
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 8 - Empty items with known total triggers warning`() {
        val blocks = buildBlocks(
            line("Gesamtbetrag   26,87 EUR", 300)
        )
        val result = ReceiptParser.parse(blocks, 600, 400)
        val warnings = result.getJSONArray("warnings")
        val codes = (0 until warnings.length()).map { warnings.getJSONObject(it).getString("code") }
        assertTrue("Должно быть предупреждение NO_ITEMS_FOUND_TOTAL_KNOWN", codes.contains("NO_ITEMS_FOUND_TOTAL_KNOWN"))
        assertEquals("Total должен быть 26.87", 26.87, result.optDouble("total", 0.0), 0.01)
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 9: Мусорный текст фильтруется
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 9 - Garbage text filtered`() {
        assertTrue("% ke os. — мусор", ReceiptParser.isGarbageText("% ke os."))
        assertTrue("© # * — мусор", ReceiptParser.isGarbageText("© # *"))
        assertTrue("A B C D E — мусор", ReceiptParser.isGarbageText("A B C D E"))
        assertFalse("Super 95 — не мусор", ReceiptParser.isGarbageText("Super 95"))
        assertFalse("Esso Station — не мусор", ReceiptParser.isGarbageText("Esso Station"))
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 10: Цена с запятой → правильные центы
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 10 - Price with comma parsed to cents correctly`() {
        assertEquals("26,87 → 2687 центов", 2687, ReceiptParser.parsePriceCents("26,87 EUR"))
        assertEquals("5,00 → 500 центов",   500,  ReceiptParser.parsePriceCents("5,00"))
        assertEquals("1,895 EUR/l → null (не товарная цена)", null, ReceiptParser.parsePriceCents("1,895"))
        // 1,895 имеет 3 знака после запятой — не является ценой товара
        assertEquals("0,99 → 99 центов",    99,   ReceiptParser.parsePriceCents("0,99"))
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 11: Esso-паттерн "*000005 Super 95  26,87 EUR A #*("
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 11 - Esso gas station line pattern`() {
        val result = ReceiptParser.findPriceInLine("*000005 Super 95   26,87 EUR A #*(")
        assertNotNull("Должен распарсить строку Esso", result)
        assertEquals("Цена 2687 центов", 2687, result!!.cents)
        assertTrue("Название содержит Super 95", result.name.contains("Super 95", ignoreCase = true))
    }

    // ─────────────────────────────────────────────────────────────
    // ТЕСТ 12: Номер телефона не является ценой
    // ─────────────────────────────────────────────────────────────
    @Test
    fun `test 12 - Phone number is not a price`() {
        val cents = ReceiptParser.parsePriceCents("0221/435057")
        assertNull("Телефон не должен быть ценой", cents)
    }
}
