package com.p2petrovich.telegramnewsreader

import com.p2petrovich.telegramnewsreader.utils.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Юнит-тесты для UpdateChecker.versionCodeFromTag.
 *
 * Функция парсит тег релиза GitHub ("v3.0.487") в versionCode (487)
 * для сравнения с BuildConfig.VERSION_CODE. Ошибка здесь = обновления
 * либо не предлагаются, либо предлагаются ошибочно.
 *
 * Функция помечена `internal`, поэтому тест должен лежать в том же
 * модуле (app) — тогда видимость internal сохраняется.
 *
 * Реализация (для справки):
 *   tag.removePrefix("v").substringAfterLast(".").toIntOrNull()
 */
class UpdateCheckerTest {

    @Test
    fun `штатный тег v3_0_487 даёт 487`() {
        assertEquals(487, UpdateChecker.versionCodeFromTag("v3.0.487"))
    }

    @Test
    fun `тег без префикса v парсится так же`() {
        assertEquals(487, UpdateChecker.versionCodeFromTag("3.0.487"))
    }

    @Test
    fun `нулевая сборка v3_0_0 даёт 0`() {
        assertEquals(0, UpdateChecker.versionCodeFromTag("v3.0.0"))
    }

    @Test
    fun `многозначный номер v10_2_1009 даёт 1009`() {
        assertEquals(1009, UpdateChecker.versionCodeFromTag("v10.2.1009"))
    }

    @Test
    fun `нечисловой хвост даёт null`() {
        assertNull(UpdateChecker.versionCodeFromTag("v3.0.beta"))
    }

    @Test
    fun `пустая строка даёт null`() {
        assertNull(UpdateChecker.versionCodeFromTag(""))
    }

    @Test
    fun `тег без точек v3 берёт всю строку после v`() {
        // substringAfterLast(".") при отсутствии "." вернёт всю строку → "3" → 3
        assertEquals(3, UpdateChecker.versionCodeFromTag("v3"))
    }

    @Test
    fun `тег без числовой последней компоненты даёт null`() {
        assertNull(UpdateChecker.versionCodeFromTag("release-5"))
    }
}
