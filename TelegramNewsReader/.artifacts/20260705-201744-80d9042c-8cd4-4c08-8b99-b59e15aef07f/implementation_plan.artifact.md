# Подробный план реализации: Этап 1 — Стабильность данных и обработка ошибок

Этот этап направлен на предотвращение потери данных пользователя, обеспечение надежной дедупликации между перезапусками и улучшение диагностики через логирование.

## 1. Рефакторинг базы данных (Room)

### 1.1. Отказ от деструктивной миграции
**Файл**: [AppDatabase.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/db/AppDatabase.kt)
- Удалить `.fallbackToDestructiveMigration()`.
- Добавить базовую миграцию (если схема изменится в будущем) или зафиксировать текущую версию.

### 1.2. Постоянное хранение истории дедупликации
**Цель**: Перенести историю из `Deduplicator` (LinkedList в памяти) в Room.

**Новая сущность**: `DedupEntity`
```kotlin
@Entity(tableName = "dedup_history")
data class DedupEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val words: String,         // JSON или сериализованный Set
    val anchors: String,       // JSON
    val numbers: String,       // JSON
    val strongAnchors: String, // JSON
    val timestamp: Long = System.currentTimeMillis()
)
```

**Новый DAO**: `DedupDao`
- `insert(entity: DedupEntity)`
- `getAll(): List<DedupEntity>`
- `deleteOldEntries(cutoff: Long)`
- `clearAll()`

---

## 2. Обновление Deduplicator.kt

**Файл**: [Deduplicator.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/Deduplicator.kt)
- **Инициализация**: При создании загружать последние `historySize` записей из БД.
- **Метод `addToHistory`**: Теперь должен выполнять асинхронную вставку в БД через Room.
- **Метод `isDuplicate`**: Продолжает работать с кешем в памяти (для скорости), но кеш синхронизирован с БД.
- **Очистка**: Метод `cleanOldEntries` должен удалять записи и из памяти, и из БД.

---

## 3. Тотальный аудит исключений (Error Handling)

**Цель**: Заменить пустые блоки `catch` на информативные логи.

**Файлы для правки**:
1. [AudioPlayerService.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/services/AudioPlayerService.kt):
   - Логировать ошибки `mediaPlayer` (stop/release/prepare).
   - Логировать ошибки регистрации `Receiver`.
2. [MainActivity.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/activities/MainActivity.kt):
   - Логировать ошибки `unregisterReceiver` (хотя бы как `verbose`, чтобы понимать, почему не удался unregister).
3. [PreferenceManager.kt](file:///C:/Telegram_cloude/TelegramNewsReader/app/src/main/java/com/p2petrovich/telegramnewsreader/utils/PreferenceManager.kt):
   - Улучшить логирование в `tryCreateEncryptedPrefs`, чтобы видеть причину коррупции файлов.

**Стандарт логирования**:
```kotlin
try {
    // действие
} catch (e: Exception) {
    Logx.e(TAG, "Краткое описание контекста (например: 'Failed to stop player')", e)
}
```

---

## 4. Верификация 1 этапа

### Автоматические тесты
1. **DeduplicatorPersistentTest**:
   - Добавить новость в Deduplicator.
   - Имитировать перезапуск (создать новый экземпляр Deduplicator с той же БД).
   - Проверить, что новость распознается как дубль.
2. **DatabaseMigrationTest**:
   - Проверить, что БД не сбрасывается при обновлении версии (если добавим миграцию).

### Ручная проверка
1. Запустить сбор новостей, дождаться завершения.
2. Закрыть приложение (Force Stop).
3. Запустить снова и проверить, что те же новости помечаются как "пропущено" (skipped) в логах или UI.
4. Проверить Logcat на наличие ошибок при манипуляциях с плеером (нажатия Стоп/Пауза).

---

## План действий (Subtasks):
- [ ] Создать `DedupEntity` и `DedupDao`.
- [ ] Обновить `AppDatabase` (версия 3) + добавить `DedupDao`.
- [ ] Интегрировать Room в `Deduplicator.kt`.
- [ ] Пройти по списку "тихих" `catch` и добавить логирование.
- [ ] Написать юнит-тест на персистентность дедупликации.
