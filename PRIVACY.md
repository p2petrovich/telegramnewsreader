# Privacy Policy / Политика конфиденциальности

Дата вступления в силу / Effective date: 2026.05.26

---

## 🇷🇺 Политика конфиденциальности Telegram News Reader

Мы уважаем вашу конфиденциальность. Данное приложение разработано для чтения и озвучивания сообщений из Telegram-каналов с помощью синтеза речи (TTS) и, при желании пользователя, их обработки с помощью моделей искусственного интеллекта.

### 1. Сбор и использование данных

Приложение получает доступ только к содержимому тех Telegram-каналов, которые вы самостоятельно выбираете. Получение сообщений выполняется через официальную библиотеку **TDLib** и проходит через серверы Telegram в соответствии с [Политикой конфиденциальности Telegram](https://telegram.org/privacy).

Локально на вашем устройстве сохраняются:
- список выбранных каналов и пользовательских пресетов;
- кэш недавно полученных сообщений (для дедупликации и повторного воспроизведения);
- пользовательские настройки (выбранный голос TTS, период обновления, тема оформления, настройки прокси и т. п.);
- данные авторизационной сессии Telegram (в папке `tdlib`).

Эти данные хранятся **исключительно на вашем устройстве**. Разработчик не имеет к ним доступа и не получает их копию.

### 2. Сторонние сервисы

Приложение может взаимодействовать со следующими внешними сервисами:

- **Telegram** — обязательно, для получения сообщений из выбранных каналов. Используется официальная библиотека TDLib.
- **MTProto-прокси** — опционально, если вы настроили прокси для обхода блокировок. В этом случае трафик к Telegram проходит через выбранный вами прокси-сервер.
- **Встроенный TTS Android** — используется по умолчанию для синтеза речи и работает локально.
- **Сторонние TTS-движки** (например, Google TTS, Yandex SpeechKit, Microsoft Azure TTS) — опционально, если вы выберете соответствующий движок в настройках. В этом случае тексты сообщений будут отправлены на серверы выбранного провайдера для преобразования в речь, в соответствии с его политикой конфиденциальности.
- **OpenRouter (AI-постобработка)** — опционально, если вы укажете собственный API-ключ. В этом случае тексты сообщений отправляются на серверы [OpenRouter](https://openrouter.ai/) для очистки, суммаризации или смены стиля изложения. Без указания API-ключа функция AI-постобработки неактивна, и никакие тексты на серверы AI не передаются.

Разработчик приложения не получает данные, передаваемые сторонним сервисам — взаимодействие происходит напрямую между вашим устройством и серверами выбранных провайдеров.

### 3. Реклама и аналитика

Приложение **не содержит** встроенной рекламы, не использует аналитические сервисы (Google Analytics, Firebase Analytics, Crashlytics и т. п.) и не собирает статистику использования.

### 4. Безопасность

Чувствительные данные (токены авторизации Telegram, пользовательские API-ключи) хранятся в защищённом виде с использованием стандартных механизмов Android (AndroidX Security / Android Keystore). Все вычисления, не требующие обращения к указанным выше сторонним сервисам, выполняются локально на вашем устройстве.

Никакие персональные данные не передаются разработчику приложения и не размещаются на серверах разработчика — у проекта нет собственного бэкенда.

### 5. Удаление данных

Чтобы полностью удалить все локально сохранённые данные приложения, удалите его стандартными средствами Android (Настройки → Приложения → News Reader → Удалить). После удаления никакие данные приложения на устройстве не остаются.

При необходимости вы также можете отозвать сессию Telegram в официальном клиенте Telegram: Настройки → Устройства → выбрать сессию приложения → Завершить сеанс.

### 6. Возрастные ограничения

Приложение не предназначено для лиц младше 13 лет. Разработчик не собирает осознанно данные несовершеннолетних пользователей.

### 7. Изменения политики

Текст политики может изменяться по мере развития приложения. Актуальная версия всегда доступна в репозитории проекта на GitHub. Дата последнего обновления указана в начале документа.

### 8. Контакты

Если у вас есть вопросы по политике конфиденциальности, свяжитесь с нами:  
📧 p2.petrovich@gmail.com

⚠️ Приложение **не связано с компанией Telegram** и не является её официальным продуктом. Telegram® — товарный знак Telegram Messenger Inc.

---

## 🇺🇸 Privacy Policy Telegram News Reader

We respect your privacy. This application is designed for reading and listening to messages from Telegram channels using text-to-speech (TTS) and, optionally, processing them with AI models.

### 1. Data collection and usage

The app only accesses the content of Telegram channels that you explicitly select. Messages are retrieved through the official **TDLib** library and pass through Telegram's servers in accordance with [Telegram's Privacy Policy](https://telegram.org/privacy).

The following data is stored **locally on your device**:
- the list of selected channels and user-defined presets;
- a cache of recently retrieved messages (used for deduplication and replay);
- user settings (selected TTS voice, refresh period, theme, proxy settings, etc.);
- Telegram authorization session data (in the `tdlib` directory).

This data is stored **exclusively on your device**. The developer has no access to it and does not receive any copy of it.

### 2. Third-party services

The app may interact with the following external services:

- **Telegram** — required, for retrieving messages from selected channels via the official TDLib library.
- **MTProto proxy** — optional, if you configure a proxy to bypass network restrictions. In this case, Telegram traffic is routed through the proxy server you choose.
- **Built-in Android TTS** — used by default for speech synthesis and runs locally on your device.
- **Third-party TTS engines** (e.g., Google TTS, Yandex SpeechKit, Microsoft Azure TTS) — optional, if you select one in the app settings. In that case, message texts are sent to the chosen provider's servers for speech generation, subject to that provider's privacy policy.
- **OpenRouter (AI post-processing)** — optional, if you provide your own API key. When enabled, message texts are sent to [OpenRouter](https://openrouter.ai/) servers for cleaning, summarization, or style adjustment. Without an API key, this feature is disabled and no text is sent to any AI service.

The developer does not receive the data transmitted to third-party services — communication happens directly between your device and the chosen provider's servers.

### 3. Ads and analytics

The app contains **no advertising**, uses **no analytics** (Google Analytics, Firebase Analytics, Crashlytics, etc.), and collects no usage statistics.

### 4. Security

Sensitive data (Telegram authorization tokens, user-supplied API keys) is stored securely using standard Android mechanisms (AndroidX Security / Android Keystore). All processing that does not require the third-party services listed above is performed locally on your device.

No personal data is transmitted to the developer or stored on any server controlled by the developer — the project has no backend of its own.

### 5. Data deletion

To completely remove all locally stored app data, uninstall the application via the standard Android settings (Settings → Apps → News Reader → Uninstall). After uninstallation, no app data remains on your device.

You may also revoke the Telegram session at any time using the official Telegram client: Settings → Devices → select the app's session → Terminate session.

### 6. Age restrictions

The app is not intended for users under the age of 13. The developer does not knowingly collect data from minors.

### 7. Changes to this policy

This policy may be updated as the application evolves. The current version is always available in the project's GitHub repository. The effective date is shown at the top of this document.

### 8. Contact

If you have any questions regarding this privacy policy, please contact us:  
📧 p2.petrovich@gmail.com

⚠️ This application is **not affiliated with Telegram** and is not an official Telegram product. Telegram® is a trademark of Telegram Messenger Inc.
