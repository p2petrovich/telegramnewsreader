package com.p2petrovich.telegramnewsreader

import com.p2petrovich.telegramnewsreader.utils.Deduplicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты для Deduplicator — детектора дублей с состоянием (историей).
 * Каждый тест создаёт свежий экземпляр.
 *
 * Дубль определяется через TextProcessor.isSameEvent (strongAnchors + numbers).
 * Имена в "дублирующихся" парах стоят НЕ в начале строки (см. (?<!^)).
 * Предполагается исправленный extractFingerprint с (?U) для кириллицы.
 */
class DeduplicatorTest {

    // ── базовые свойства ────────────────────────────────────────

    @Test
    fun `новый текст не является дублем для пустой истории`() {
        val dedup = Deduplicator()
        assertFalse(dedup.isDuplicate("Банк ВТБ повысил ключевую ставку до 21 процента"))
    }

    @Test
    fun `повтор после добавления в историю распознаётся как дубль`() {
        val dedup = Deduplicator()
        val first = "Банк ВТБ повысил ключевую ставку до 21 процента"
        val second = "Ставка банка ВТБ выросла до 21 процента годовых"

        assertFalse("первое появление — не дубль", dedup.isDuplicate(first))
        dedup.addToHistory(first)
        assertTrue("второе появление того же события — дубль", dedup.isDuplicate(second))
    }

    @Test
    fun `разные события не считаются дублями`() {
        val dedup = Deduplicator()
        val a = "Apple представила новый iPhone за 999 долларов"
        val b = "Samsung показал смартфон Galaxy за 850 долларов"

        dedup.addToHistory(a)
        assertFalse(dedup.isDuplicate(b))
    }

    // ── isEnabled = false ───────────────────────────────────────

    @Test
    fun `выключенный детектор никогда не считает дублем`() {
        val dedup = Deduplicator(isEnabled = false)
        val text = "Банк ВТБ повысил ключевую ставку до 21 процента"

        dedup.addToHistory(text)
        assertFalse(dedup.isDuplicate(text))
        assertEquals(0, dedup.getHistorySize())
    }

    // ── правило "мало слов" ─────────────────────────────────────

    @Test
    fun `слишком короткий текст не считается дублем`() {
        val dedup = Deduplicator()
        assertFalse(dedup.isDuplicate("Срочно"))
        assertFalse(dedup.isDuplicate("Фото дня"))
    }

    @Test
    fun `слишком короткий текст не добавляется в историю`() {
        val dedup = Deduplicator()
        dedup.addToHistory("Срочно")
        assertEquals(0, dedup.getHistorySize())
    }

    // ── addToHistory: без дублей в самой истории ────────────────

    @Test
    fun `addToHistory не добавляет повтор одного и того же события`() {
        val dedup = Deduplicator()
        val a = "Банк ВТБ повысил ключевую ставку до 21 процента"
        val b = "Ставка банка ВТБ выросла до 21 процента годовых"

        dedup.addToHistory(a)
        dedup.addToHistory(b)
        assertEquals(1, dedup.getHistorySize())
    }

    // ── счётчик пропусков ───────────────────────────────────────

    @Test
    fun `getSkippedCount растёт на каждое срабатывание`() {
        val dedup = Deduplicator()
        val first = "Банк ВТБ повысил ключевую ставку до 21 процента"
        dedup.addToHistory(first)

        assertEquals(0, dedup.getSkippedCount())
        dedup.isDuplicate("Ставка банка ВТБ выросла до 21 процента годовых")
        assertEquals(1, dedup.getSkippedCount())
        dedup.isDuplicate("Банк ВТБ поднял ставку до 21 процента в этом году")
        assertEquals(2, dedup.getSkippedCount())
    }

    @Test
    fun `resetSkippedCount обнуляет счётчик`() {
        val dedup = Deduplicator()
        val first = "Банк ВТБ повысил ключевую ставку до 21 процента"
        dedup.addToHistory(first)
        dedup.isDuplicate("Ставка банка ВТБ выросла до 21 процента годовых")

        dedup.resetSkippedCount()
        assertEquals(0, dedup.getSkippedCount())
    }

    // ── reset ───────────────────────────────────────────────────

    @Test
    fun `reset очищает историю и счётчик`() {
        val dedup = Deduplicator()
        val first = "Банк ВТБ повысил ключевую ставку до 21 процента"
        dedup.addToHistory(first)
        dedup.isDuplicate("Ставка банка ВТБ выросла до 21 процента годовых")

        dedup.reset()
        assertEquals(0, dedup.getHistorySize())
        assertEquals(0, dedup.getSkippedCount())
    }

    // ── лимит истории (historySize) ─────────────────────────────

    @Test
    fun `история не превышает historySize и вытесняет старое`() {
        val dedup = Deduplicator(historySize = 3)

        dedup.addToHistory("Компания Alpha заключила контракт на 100 миллионов долларов")
        dedup.addToHistory("Город Beta открыл новый мост стоимостью 200 миллионов рублей")
        dedup.addToHistory("Завод Gamma выпустил 300 тысяч единиц продукции")
        dedup.addToHistory("Банк Delta снизил ставку до 15 процентов годовых")
        dedup.addToHistory("Холдинг Epsilon купил актив за 400 миллионов евро")

        assertEquals("история обрезается до лимита", 3, dedup.getHistorySize())
    }

    // ── временное окно (timeWindowMinutes) ──────────────────────

    @Test
    fun `записи старше окна вытесняются при следующей проверке`() {
        // Окно -1 минута: cutoff = now + 60000 → заведомо в будущем относительно
        // timestamp записи, поэтому любая запись считается просроченной без гонки по мс.
        val dedup = Deduplicator(timeWindowMinutes = -1)
        val first = "Банк ВТБ повысил ключевую ставку до 21 процента"

        dedup.addToHistory(first)
        val isDup = dedup.isDuplicate("Ставка банка ВТБ выросла до 21 процента годовых")
        assertFalse("просроченная запись не должна давать совпадение", isDup)
    }
}
