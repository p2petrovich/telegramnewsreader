package com.p2petrovich.telegramnewsreader

import com.p2petrovich.telegramnewsreader.utils.TextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты доменного ядра TextProcessor:
 *   - extractFingerprint  (построение отпечатка новости)
 *   - isSameEvent         (детекция дублей)
 *   - normalizeNumbers    (нормализация чисел/валют/процентов для TTS)
 *
 * ВАЖНО: тесты предполагают исправленный extractFingerprint с флагом (?U)
 * в регэкспах abbreviations/properNames. Без (?U) кириллические якоря НЕ
 * извлекаются (баг с не-Unicode границей слова \b), и тесты с ВТБ/Крым упадут.
 *
 * Также: extractFingerprint НЕ кладёт ПЕРВОЕ слово текста в strongAnchors
 * (регэксп properNames содержит (?<!^)), поэтому искомые имена стоят НЕ в начале.
 *
 * Группы:
 *   [STABLE]           — поведенческие проверки наличия/отсутствия признака.
 *   [CHARACTERIZATION] — точное сравнение строк, фиксирует текущее поведение.
 */
class TextProcessorTest {

    // ============================================================
    //  extractFingerprint
    // ============================================================

    @Test  // [STABLE]
    fun `fingerprint срезает префикс времени и извлекает число и имя`() {
        val fp = TextProcessor.extractFingerprint("08:30 — Банк ВТБ повысил ставку до 21")
        assertTrue("число 21 должно попасть в numbers", "21" in fp.numbers)
        assertTrue("имя 'втб' должно попасть в strongAnchors", "втб" in fp.strongAnchors)
        assertFalse("'08' не должно быть в words", "08" in fp.words)
        assertFalse("'30' не должно быть в words", "30" in fp.words)
    }

    @Test  // [STABLE]
    fun `fingerprint исключает шумовые якоря (NOISE_ANCHORS)`() {
        // 'россии' и 'дронов' — в NOISE_ANCHORS; 'крым' (не в начале) — сильный якорь.
        val fp = TextProcessor.extractFingerprint("Над регионом Крым сбили 310 дронов России")
        assertTrue("310" in fp.numbers)
        assertTrue("'крым' — сильный якорь", "крым" in fp.strongAnchors)
        assertFalse("'дронов' не должен быть якорем", "дронов" in fp.strongAnchors)
        assertFalse("'россии' не должен быть якорем", "россии" in fp.strongAnchors)
    }

    @Test  // [STABLE]
    fun `fingerprint сжимает 4+-значные числа в форму XXXk`() {
        val fp = TextProcessor.extractFingerprint("Курс вырос на 1234 пункта")
        assertTrue("1234 → '123k'", "123k" in fp.numbers)
    }

    @Test  // [STABLE]
    fun `fingerprint игнорирует одиночные цифры`() {
        val fp = TextProcessor.extractFingerprint("Цена 5 рублей")
        assertFalse("одиночная '5' не попадает в numbers", "5" in fp.numbers)
    }

    @Test  // [STABLE]
    fun `fingerprint работает с латиницей и приводит к нижнему регистру`() {
        val fp = TextProcessor.extractFingerprint("Today Putin met Biden in Geneva")
        assertTrue("putin" in fp.strongAnchors)
        assertTrue("biden" in fp.strongAnchors)
        assertTrue("geneva" in fp.strongAnchors)
    }

    @Test  // [STABLE] регрессионный тест на баг (?U): кириллический якорь извлекается
    fun `fingerprint извлекает кириллический якорь (регрессия на баг U)`() {
        val fp = TextProcessor.extractFingerprint("Сегодня Газпром подписал контракт на 15 лет")
        assertTrue("кириллическое имя 'газпром' должно быть в strongAnchors",
            "газпром" in fp.strongAnchors)
    }

    @Test // [STABLE] тест на распознавание имени собственного в самом начале строки
    fun `fingerprint должен извлекать имя собственное в начале строки`() {
        // Сейчас это падает, так как в регулярке стоит (?<!^)
        val fp = TextProcessor.extractFingerprint("ВТБ повысил ставки")
        assertTrue("имя 'втб' в начале строки должно попасть в strongAnchors",
            "втб" in fp.strongAnchors)
    }

    // ============================================================
    //  isSameEvent
    // ============================================================

    @Test  // [STABLE]
    fun `sameEvent — общий сильный якорь плюс общее число = дубль`() {
        val a = TextProcessor.extractFingerprint("Банк ВТБ повысил ставку до 21 процента")
        val b = TextProcessor.extractFingerprint("Ставка банка ВТБ выросла до 21")
        assertTrue(TextProcessor.isSameEvent(a, b))
    }

    @Test  // [STABLE]
    fun `sameEvent — разные события не дубль`() {
        val a = TextProcessor.extractFingerprint("Apple представила iPhone за 999")
        val b = TextProcessor.extractFingerprint("Samsung показал Galaxy за 850")
        assertFalse(TextProcessor.isSameEvent(a, b))
    }

    @Test  // [STABLE]
    fun `sameEvent — два общих числа = дубль`() {
        val a = TextProcessor.extractFingerprint("Землетрясение 5.8 в Японии, погибли 12")
        val b = TextProcessor.extractFingerprint("В Японии землетрясение магнитудой 5.8, жертв 12")
        assertTrue(TextProcessor.isSameEvent(a, b))
    }

    @Test  // [STABLE]
    fun `sameEvent — критерий B не зависит от высокого threshold`() {
        val a = TextProcessor.extractFingerprint("Банк ВТБ повысил ставку до 21 процента")
        val b = TextProcessor.extractFingerprint("Ставка банка ВТБ выросла до 21")
        assertTrue(TextProcessor.isSameEvent(a, b, threshold = 0.95))
    }

    // ============================================================
    //  normalizeNumbers
    //  [CHARACTERIZATION] — фиксируют ТЕКУЩЕЕ поведение кода
    // ============================================================

    @Test  // [CHARACTERIZATION]
    fun `normalize — проценты в слова`() {
        assertEquals("рост на 5 процентов", TextProcessor.normalizeNumbers("рост на 5%"))
    }

    @Test
    fun `normalize — drobnye procenty`() {
        assertEquals("9 и 5 десятых процента", TextProcessor.normalizeNumbers("9.5%"))
    }

    @Test  // [CHARACTERIZATION]
    fun `normalize — валюта с масштабом (тыс)`() {
        assertEquals("100 тысяч рублей", TextProcessor.normalizeNumbers("₽100 тыс"))
    }

    @Test  // [CHARACTERIZATION]
    fun `normalize — миллиарды рублей`() {
        assertEquals("5 миллиардов рублей", TextProcessor.normalizeNumbers("5 млрд руб"))
    }

    @Test  // [STABLE]
    fun `normalize — доллары с масштабом`() {
        assertEquals("цена 50 миллионов долларов", TextProcessor.normalizeNumbers("цена \$50 млн"))
    }

    @Test  // [STABLE]
    fun `normalize — evro s masshtabom`() {
        assertEquals("бюджет 20 миллиардов евро", TextProcessor.normalizeNumbers("бюджет €20 млрд"))
    }

    @Test  // [CHARACTERIZATION]
    fun `normalize — диапазон 1-3-значных чисел`() {
        assertEquals("температура от 27 до 29", TextProcessor.normalizeNumbers("температура 27–29"))
    }

    @Test  // [STABLE]
    fun `normalize — НЕ ломает 4-значные годы`() {
        val result = TextProcessor.normalizeNumbers("в 2024-2025 годах")
        assertFalse(
            "правило диапазона не должно применяться к годам",
            result.contains("от 2024 до 2025")
        )
    }

    @Test  // [STABLE]
    fun `normalize — знак номера раскрывается`() {
        assertEquals("номер 5", TextProcessor.normalizeNumbers("№ 5"))
    }

    // ============================================================
    //  expandAbbreviations (новые тесты)
    // ============================================================

    @Test
    fun `expandAbbreviations - g posle goda`() {
        assertEquals("2024 года", TextProcessor.expandAbbreviations("2024 г."))
    }

    @Test
    fun `expandAbbreviations - g v nachale`() {
        assertEquals("город Москва", TextProcessor.expandAbbreviations("г. Москва"))
    }

    @Test
    fun `expandAbbreviations - d pered cifroy`() {
        assertEquals("дом 5", TextProcessor.expandAbbreviations("д. 5"))
    }

    @Test
    fun `expandAbbreviations - d vnutri slova`() {
        assertEquals("вода", TextProcessor.expandAbbreviations("вода"))
    }

    @Test
    fun `applyStressMarks - godu i lisheniya`() {
        // к гóду лишéния
        val text = "к году лишения"
        val stressed = TextProcessor.prepareForSpeech(text)
        assertTrue(stressed.contains("го\u0301ду"))
        assertTrue(stressed.contains("лиш\u0301ения"))
    }
    @Test
    fun `DIAG печать фактических строк`() {
        fun p(label: String, s: String) = println("$label=[$s]")

        p("NUM $50 млн ", TextProcessor.normalizeNumbers("цена \$50 млн"))
        p("NUM €20 млрд", TextProcessor.normalizeNumbers("бюджет €20 млрд"))
        p("NUM 35млнруб", TextProcessor.normalizeNumbers("до 35 млн рублей"))
        p("NUM 35млн   ", TextProcessor.normalizeNumbers("выросло на 35 млн"))
        p("NUM -5C     ", TextProcessor.normalizeNumbers("похолодает до -5°C"))
        p("NUM ₽100тыс ", TextProcessor.normalizeNumbers("₽100 тыс"))
        p("NUM 5млрдруб", TextProcessor.normalizeNumbers("5 млрд руб"))

        p("ABBR 2024 г", TextProcessor.expandAbbreviations("2024 г."))
        p("ABBR г.Моск", TextProcessor.expandAbbreviations("г. Москва"))
        p("ABBR д. 5  ", TextProcessor.expandAbbreviations("д. 5"))
        p("ABBR АЗС   ", TextProcessor.expandAbbreviations("АЗС горит"))
        p("ABBR РФ    ", TextProcessor.expandAbbreviations("в РФ приняли"))

        val f1 = TextProcessor.extractFingerprint("08:30 — Банк ВТБ повысил ставку до 21")
        println("FP1 strong=${f1.strongAnchors} numbers=${f1.numbers}")
        val f2 = TextProcessor.extractFingerprint("Над регионом Крым сбили 310 дронов России")
        println("FP2 strong=${f2.strongAnchors} numbers=${f2.numbers}")
        val f3 = TextProcessor.extractFingerprint("Today Putin met Biden in Geneva")
        println("FP3 strong=${f3.strongAnchors} numbers=${f3.numbers}")
    }

}
