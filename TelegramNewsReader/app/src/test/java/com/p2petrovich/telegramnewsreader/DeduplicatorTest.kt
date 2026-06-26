package com.p2petrovich.telegramnewsreader

import com.p2petrovich.telegramnewsreader.utils.Deduplicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты для Deduplicator — детектора дублей с состоянием (историей).
 *
 * Каждый тест создаёт свежий экземпляр (Deduplicator хранит состояние).
 *
 * ВАЖНО: дубль определяется через TextProcessor.isSameEvent, который
 * опирается на strongAnchors. Поскольку extractFingerprint НЕ включает
 * первое слово текста в strongAnchors, во всех «дублирующихся» парах
 * сильное имя (ВТБ) стоит НЕ в начале строки — иначе isSameEvent вернёт
 * false и дубль не задетектится.
 *
 * Размещение: testOptions { unitTests.returnDefaultValues = true } в
 * build.gradle уже включён, поэтому Logx/android.util.Log не мешают
 * запуску в src/test/.
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
        val second = "Ставка банка ВТБ выросла до 21 процента годовых" // то же событие

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

        dedup.addToHistory(text) // при isEnabled=false addToHistory — no-op
        assertFalse(dedup.isDuplicate(text))
        assertEquals(0, dedup.getHistorySize())
    }

    // ── правило "мало слов" ─────────────────────────────────────

    @Test
    fun `слишком короткий текст не считается дублем`() {
        val dedup = Deduplicator()
        // < 3 значимых слов → isDuplicate возвращает false, не сравнивая
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
        dedup.addToHistory(b) // то же событие — не должно увеличить историю
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

        // 5 заведомо РАЗНЫХ событий (разные имена не в начале + числа)
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
        // Окно 0 минут: любая запись мгновенно считается просроченной.
        val dedup = Deduplicator(timeWindowMinutes = 0)
        val first = "Банк ВТБ повысил ключевую ставку до 21 процента"

        dedup.addToHistory(first)
        // cleanOldEntries() вызывается внутри isDuplicate(); при окне 0
        // запись уже "старая" → её выкинут, и дубль НЕ распознается.
        val isDup = dedup.isDuplicate("Ставка банка ВТБ выросла до 21 процента годовых")
        assertFalse("просроченная запись не должна давать совпадение", isDup)
    }
}
