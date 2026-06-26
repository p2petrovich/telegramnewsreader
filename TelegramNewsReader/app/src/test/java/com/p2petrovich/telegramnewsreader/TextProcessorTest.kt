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
 * Все функции — чистые (вход → выход), без Android-окружения.
 *
 * ВАЖНЫЕ ОСОБЕННОСТИ КОДА, учтённые в тестах:
 *  1) extractFingerprint НЕ кладёт первое слово текста в strongAnchors
 *     (регэкспы properNames/abbreviations содержат (?<!^) и (?<![.!?…]\s)).
 *     Поэтому искомые имена в тестах стоят НЕ в начале строки.
 *  2) normalizeNumbers для $ и € выдаёт порядок "число валюта масштаб"
 *     ("50 долларов млн"), а не "число масштаб валюта". Это зафиксировано
 *     характеризационными тестами как текущее поведение (см. CODE_REVIEW).
 *  3) normalizeNumbers НЕ раскрывает "№" из-за \b перед не-словесным символом.
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
        // Имя НЕ в начале — иначе (?<!^) исключит его из strongAnchors.
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
        // numbers фильтрует значения короче 2 символов
        val fp = TextProcessor.extractFingerprint("Цена 5 рублей")
        assertFalse("одиночная '5' не попадает в numbers", "5" in fp.numbers)
    }

    @Test  // [STABLE]
    fun `fingerprint работает с латиницей и приводит к нижнему регистру`() {
        // Первое слово исключается (?<!^), поэтому имена ставим в середину.
        val fp = TextProcessor.extractFingerprint("Today Putin met Biden in Geneva")
        assertTrue("putin" in fp.strongAnchors)
        assertTrue("biden" in fp.strongAnchors)
        assertTrue("geneva" in fp.strongAnchors)
    }

    // ============================================================
    //  isSameEvent
    // ============================================================

    @Test  // [STABLE]
    fun `sameEvent — общий сильный якорь плюс общее число = дубль`() {
        // Критерий B: >=1 strongAnchor && >=1 number.
        // Имена НЕ в начале строки, иначе не попадут в strongAnchors.
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
        // Критерий C: >=2 общих числа.
        val a = TextProcessor.extractFingerprint("Землетрясение 5.8 в Японии, погибли 12")
        val b = TextProcessor.extractFingerprint("В Японии землетрясение магнитудой 5.8, жертв 12")
        assertTrue(TextProcessor.isSameEvent(a, b))
    }

    @Test  // [STABLE]
    fun `sameEvent — критерий B не зависит от высокого threshold`() {
        // B (сильный якорь + число) срабатывает независимо от threshold,
        // который влияет только на критерии A и D.
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

    @Test  // [CHARACTERIZATION]
    fun `normalize — валюта с масштабом (тыс)`() {
        assertEquals("100 тысяч рублей", TextProcessor.normalizeNumbers("₽100 тыс"))
    }

    @Test  // [CHARACTERIZATION]
    fun `normalize — миллиарды рублей`() {
        assertEquals("5 миллиардов рублей", TextProcessor.normalizeNumbers("5 млрд руб"))
    }

    @Test  // [CHARACTERIZATION] факт: для $ порядок "число валюта масштаб"
    fun `normalize — доллары с масштабом`() {
        assertEquals("цена 50 долларов млн", TextProcessor.normalizeNumbers("цена \$50 млн"))
    }

    @Test  // [CHARACTERIZATION] факт: для € порядок "число валюта масштаб"
    fun `normalize — евро с масштабом`() {
        assertEquals("бюджет 20 евро млрд", TextProcessor.normalizeNumbers("бюджет €20 млрд"))
    }

    @Test  // [CHARACTERIZATION]
    fun `normalize — диапазон 1-3-значных чисел`() {
        assertEquals("температура от 27 до 29", TextProcessor.normalizeNumbers("температура 27–29"))
    }

    @Test  // [STABLE]
    fun `normalize — НЕ ломает 4-значные годы`() {
        // Правило диапазона ограничено 1–3 значными числами, годы не трогает.
        val result = TextProcessor.normalizeNumbers("в 2024-2025 годах")
        assertFalse(
            "правило диапазона не должно применяться к годам",
            result.contains("от 2024 до 2025")
        )
    }

    @Test  // [CHARACTERIZATION] факт: "№" НЕ раскрывается (баг с \b перед не-словом)
    fun `normalize — знак номера не раскрывается`() {
        assertEquals("№ 5", TextProcessor.normalizeNumbers("№ 5"))
    }
}
