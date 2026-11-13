# Полный аудит приложения Electros EV Android Client (целевая платформа: Android 17+ / API 37)

- **Дата ревью:** 2026-11-13
- **Текущая версия кода:** `2.7.2` (`versionCode = 272`, коммит `370d842`, ветка `main`)
- **Целевое требование платформы:** **только Android 17 и выше (`minSdk = 37`, `targetSdk = 37`, `compileSdk = 37`)** — без обратной совместимости со старыми версиями Android, без legacy-костылей и только на современном стеке API.
- **Охват проверки:** корректность работы и бизнес-логики, требования платформы Android 17 (`API 37`), информационная безопасность, зачистка всего legacy- и мёртвого кода, зависимости, ресурсы, сборка и тесты.

> **Контекст:** Сейчас проект настроен под устаревший диапазон `minSdk = 26` (Android 8.0) – `targetSdk = 35` (Android 15) и содержит значительный пласт кода обратной совместимости (ветки `SDK_INT`, deprecated `security-crypto` alpha, до-API-31 `backup_rules.xml`, до-API-26 `setPriority` и растровые иконки, `HttpURLConnection`, `SimpleDateFormat`, миграции со старых `SharedPreferences`). При переходе на `minSdk = 37` весь этот слой удаляется, но одновременно вступают в силу строгие платформенные правила Android 17 (обязательный Edge-to-Edge без opt-out, запрет старта `specialUse` FGS из `BOOT_COMPLETED` без освобождения от Doze, Predictive Back, адаптивные окна).

---

## Сводная таблица находок

| # | Уровень | Категория | Файлы | Краткое описание проблемы |
|---|:---:|---|---|---|
| 1 | 🔴 Major | Корректность | `SessionSupervisor.kt`, `ChargeDao.kt`, `ChargeRepository.kt` | При отключении света (`OFFLINE`, счётчик `0.0`) обнуляется энергия активного среза → потеря прогресса и **перезаряд сверх квоты кВт⋅ч** |
| 2 | 🔴 Major | Корректность / Сеть | `ElectrosWebScraper.kt` | HTTP-fallback команд (`stop`, `start`, `setAmperage`, `setTimer`) следует за редиректом на `login.php` без сессии/CSRF и возвращает **ложный `Result.success`** |
| 3 | 🔴 Major | Android 17 (`API 37`) | `app/build.gradle.kts`, `MainActivity.kt`, `LoginScreen.kt`, `BootReceiver.kt`, `ChargeMonitorService.kt`, `AndroidManifest.xml` | Проект собран под API 26–35: нет `minSdk/targetSdk/compileSdk = 37`, нет обязательного `enableEdgeToEdge()` и insets (на API 37 opt-out удалён), `BootReceiver` и `startForeground` нарушают ограничения FGS Android 17 |
| 4 | 🔴 Major | Android 17 Уведомления | `AndroidManifest.xml`, `MainActivity.kt`, `DashboardScreen.kt` | Нигде не запрашивается runtime-разрешение `POST_NOTIFICATIONS` → на Android 17 система блокирует **все** аварийные и сервисные уведомления |
| 5 | 🔴 Major | Потеря данных | `SettingsScreen.kt`, `SettingsViewModel.kt` | Нажатие «Зберегти нові дані» при пустом поле пароля молча затирает пароль пустой строкой без проверки логина |
| 6 | 🔴 Major | Управление станцией | `ChargeMonitorService.kt`, `SessionSupervisor.kt` | Кнопка «Зупинити» в уведомлении (`ACTION_STOP_CHARGE`) не останавливает станцию после пересоздания процесса сервиса (`activeSessionId == null`) |
| 7 | 🔴 Major | Безопасность / Lifecycle | `ChargeRepository.kt`, `SettingsViewModel.kt`, `AppNavigation.kt`, `LoginViewModel.kt` | Неполный `logout()`: не очищаются cookies/CSRF, не останавливается `ChargeMonitorService`, пароль остаётся в памяти `LoginViewModel`, а `SettingsViewModel` показывает пустой серийник после повторного входа |
| 8 | 🔴 Major | Безопасность / Криптография | `EncryptedPrefsFactory.kt`, `AuthPreferences.kt`, `CookieStorage.kt`, `libs.versions.toml` | Используется **deprecated** альфа `security-crypto:1.1.0-alpha06` + общий `MasterKey` (сбой cookies стирает логин/пароль). Для Android 17 нужен нативный Keystore `AES/GCM/NoPadding` + `DataStore` |
| 9 | 🔴 Major | Корректность / Безопасность | `SettingsScreen.kt`, `StationConfig.kt` | Карточка «Апаратні параметри станції» показывает захардкоженные значения («Контроль заземлення: Увімкнено», «УЗО > 30мА»), не читая реальные настройки станции |
| 10 | 🔴 Major | Корректность UI | `HistoryScreen.kt`, `HistoryViewModel.kt` | На экране истории намертво зашита строка `"Листопад 2026"`, а «Споживання за місяць» суммирует сессии за всё время |
| 11 | 🟠 Medium | Безопасность протокола | `ElectrosWebScraper.kt`, `LoginScreen.kt` | Управление станцией по WebSocket без аутентификации (достаточно `deviceSerial`); поле серийного номера на `LoginScreen` разрешает автозапоминание клавиатурой |
| 12 | 🟠 Medium | OTA / Современный стек | `OtaViewModel.kt`, `OtaUpdater.kt`, `UpdateDialog.kt` | Закрытие диалога при запросе `ACTION_MANAGE_UNKNOWN_APP_SOURCES` заставляет качать APK заново; утечка `Activity`; гонка записи в один файл; устаревший `HttpURLConnection` и deprecated `URL(String)` вместо `OkHttpClient` |
| 13 | 🟠 Medium | Конкурентность | `ElectrosWebScraper.kt`, `SettingsStore.kt`, `DashboardViewModel.kt` | Гонка по `authFailures` вне `authMutex`; потеря сохранённых настроек в `SettingsStore` при ранней записи + `IOException`; дублирующий опрос станции из UI и сервиса |
| 14 | 🟠 Medium | Доменная логика | `DashboardViewModel.kt`, `EnergyAccumulator.kt`, `ElectrosWebScraper.kt`, `LimiterScreen.kt` | Ручная смена тока не обновляет Dead-Man таймер (риск раннего отключения); завышение напряжения (`coerceAtLeast(200)`); рассинхрон диапазонов DLB и токов 6–32 А |
| 15 | 🟠 Medium | UX / Ошибки | `LimiterViewModel.kt`, `DashboardViewModel.kt`, UI-экраны | Игнорирование `Result.failure` при отправке расписания и смене тока (показывается ложное «✓ Передано»); смешение английского, украинского и русского языков |
| 16 | 🟡 Minor | Legacy Android < 17 | `ChargeMonitorService.kt`, `NotificationHelper.kt`, `OtaUpdater.kt` | 6 веток `Build.VERSION.SDK_INT`, `@RequiresApi(S)` и 6 `@Suppress("DEPRECATION")` в `PackageManager` вместо современного `PackageInfoFlags.of(...)` |
| 17 | 🟡 Minor | Legacy Android < 12 / < 8 | `AndroidManifest.xml`, `backup_rules.xml`, `NotificationHelper.kt` | Игнорируемый на API 31+ `fullBackupContent` / `backup_rules.xml` и 10 игнорируемых с API 26 вызовов `NotificationCompat.Builder.setPriority(...)` |
| 18 | 🟡 Minor | Legacy-миграции | `SettingsStore.kt`, `AuthPreferences.kt`, `EncryptedPrefsFactory.kt`, `data_extraction_rules.xml` | ~130 строк старых миграций `LegacyAuthPrefsMigration`, `LEGACY_SETTINGS_KEYS`, `legacyPlaintextNames` (`auth_prefs_fallback`, `electros_cookies`) |
| 19 | 🟡 Minor | Мёртвый код / Зависимости | `ReconciliationWorker.kt`, `app/build.gradle.kts`, `libs.versions.toml` | Мёртвый `ReconciliationWorker` + лишние зависимости `work-runtime-ktx`, `navigation-compose`, `security-crypto` и `androidTestImplementation` без `src/androidTest` |
| 20 | 🟡 Minor | Устаревшие Java/Kotlin API | `HistoryScreen.kt`, `AppNavigation.kt`, `Color.kt`, `LimiterViewModel.kt`, `NotificationHelper.kt` | `SimpleDateFormat`/`Date` вместо `java.time`, `NavTab.values()` вместо `entries`, `Uri.parse` вместо `toUri()`, растровые `android.R.drawable.*`, backward-compat алиасы цветов и прокси-методы |
| 21 | 🟡 Minor | Мёртвый код в моделях | `StationConfig.kt`, `TelemetryData.kt`, `HistoryViewModel.kt`, `LoginViewModel.kt`, `ChargeSliceEntity.kt` | Неиспользуемые поля моделей (нейтраль, min/max/avg напряжение, калибровки, Wi-Fi), `previousMonthKwh = 96.9`, `LoginUiState.isSuccess`, `COUNTER_REGRESSION`, лишние импорты |
| 22 | 🟡 Minor | Лишние ресурсы и файлы | `colors.xml`, `mipmap-*/ic_launcher*.webp`, `rules.keep`, `mockup_preview.html` | 7 неиспользуемых шаблонных цветов, 10 до-API-26 растровых иконок зелёного робота, пустой `rules.keep`, HTML-мокап в корне репозитория |
| 23 | ⚪ Minimal | Android 17 Adaptive UI | `AppNavigation.kt`, `DashboardScreen.kt`, `SettingsScreen.kt`, `NotificationHelper.kt` | Сброс вкладки и карточек при ресайзе/повороте окна на Android 17 (`remember` вместо `rememberSaveable`), нет Predictive Back (`BackHandler`), автобоксинг `Long`, монолит `DashboardScreen.kt` (2 095 строк), спам уведомлений DLB |
| 24 | ⚪ Minimal | R8 / Релизная сборка | `app/proguard-rules.pro`, `ElectrosWebScraper.kt`, `app/build.gradle.kts` | Вывод логов в `Logcat` в релизе, устаревший `User-Agent` (`Android 14`), сборка unsigned release APK без предупреждения |
| 25 | ⚪ Minimal | Тулчейн / Тесты / Дока | `app/build.gradle.kts`, `src/test/...`, `README.md`, `docs/` | Обновление AGP/Kotlin/Compose под API 37 (снятие `checkTestSources = false`), доустановка `platforms;android-37`, пробелы в unit-тестах и устаревшая документация |

**Рекомендуемый порядок работ:** `3 (переход на API 37) → 16–22 (снос всего legacy и мёртвого кода) → 1 → 2 → 4 → 6 → 5 → 7 → 8 → 9 → 10`, затем `11–15` и `23–25`.

---

## 🔴 MAJOR: Критичные ошибки корректности, данных и безопасности

### 1. Обнуление энергии активного среза при отключении питания (`OFFLINE` передаёт `0.0 кВт⋅ч`) → перерасход квоты

**Где:**
- [SessionSupervisor.kt](app/src/main/java/ua/org/electros/app/domain/monitor/SessionSupervisor.kt) — `evaluateTelemetrySuccess` (стр. 213–269), `hydrateFromDatabase` (стр. 117–130)
- [ChargeDao.kt](app/src/main/java/ua/org/electros/app/data/local/dao/ChargeDao.kt) — `finalizeActiveSliceAndCommit` (стр. 76–98)
- [ChargeRepository.kt](app/src/main/java/ua/org/electros/app/data/repository/ChargeRepository.kt) — `stopActiveSession` (стр. 387–414), `handleOutageDetected` (стр. 426–440)

**Что найдено.**
Когда в гараже пропадает питание, станция переходит в `OFFLINE` (`statusCode == 7` или `77` в WS либо офлайн-страница по HTTP) и отдаёт `consumedEnergyKwh = 0.0` (это даже зафиксировано в комментарии теста `SessionSupervisorTest.kt:469`: *«offline reports 0.0 kWh»*).
Пошаговая трассировка `SessionSupervisor.evaluateTelemetrySuccess` на этом тике:
1. На стр. 213 проверка `if (data.isOnline && EnergyAccumulator.isCounterRegression(...))` пропускается, потому что `data.isOnline == false`.
2. На стр. 230 выполняется `lastSampledCounterKwh = data.consumedEnergyKwh` — последнее валидное онлайн-показание счётчика (например, `105.0 кВт⋅ч` при старте среза `100.0 кВт⋅ч`) **безусловно перезаписывается нулём (`0.0`)**.
3. На стр. 244–249 `totalDelivered` вычисляется с `currentStationCounterKwh = 0.0` → дельта активного среза становится `(0.0 - 100.0).coerceAtLeast(0.0) = 0.0`.
4. На стр. 266 вызывается `gateway.handleOutageDetected(sessionId, data.consumedEnergyKwh)` с `lastCounter = 0.0`.
5. В `ChargeDao.finalizeActiveSliceAndCommit` срез закрывается с `finalizedKwh = 0.0` и `stationCounterLastKwh = 0.0`, а уведомление пишет пользователю `«Збережено 0.0 кВт⋅год»`.
6. Когда свет включается, станция заряжает **полную квоту заново**, превышая заданный пользователем лимит на величину потерянного среза.

Дополнительно:
- Во время штатной зарядки (`handleChargingTick`) поле `ChargeSliceEntity.stationCounterLastKwh` в БД **никогда не обновляется** (оно остаётся равным `stationCounterStartKwh` с момента создания среза). Если перезагрузится сам телефон или Android перезапустит `ChargeMonitorService`, `lastSampledCounterKwh` в памяти будет `0.0`, и при аварии/обрыве связи прогресс открытого среза также потеряется.
- Та же ошибка есть в `ChargeRepository.stopActiveSession` (стр. 402–410): `stopAndConfirm(allowOfflineConfirmation = true)` кладёт офлайн-пакет (`0.0 кВт⋅ч`) в `_telemetryState`, после чего `finalizeSessionStopped` затирает срез нулём.

**Как исправить.**
1. В `ChargeDao` добавить обновление `stationCounterLastKwh` на каждом онлайн-тике и защиту от затирания меньшим счётчиком при финализации:
```kotlin
@Query("""
    UPDATE charge_slices
    SET stationCounterLastKwh = CASE
        WHEN :counterKwh > stationCounterLastKwh THEN :counterKwh
        ELSE stationCounterLastKwh
    END
    WHERE sessionId = :sessionId AND endedAt IS NULL
""")
suspend fun updateActiveSliceCounter(sessionId: String, counterKwh: Double)
```
В `finalizeActiveSliceAndCommit` вычислять:
```kotlin
val effectiveLast = maxOf(lastSampledKwh, latestSlice.stationCounterLastKwh)
val delivered = (effectiveLast - latestSlice.stationCounterStartKwh).coerceAtLeast(0.0)
```
2. В `SessionSupervisor`:
   - обновлять `lastSampledCounterKwh = data.consumedEnergyKwh` **только при `data.isOnline`** (после обработки возможной регрессии счётчика) и сохранять его через `gateway.updateActiveSliceCounter(sessionId, data.consumedEnergyKwh)`;
   - при `!data.isOnline` передавать в `calculateTotalDeliveredKwh` и `gateway.handleOutageDetected(sessionId, lastSampledCounterKwh)` сохранённый `lastSampledCounterKwh`, а не `data.consumedEnergyKwh`;
   - в `hydrateFromDatabase()` инициализировать `lastSampledCounterKwh` из `gateway.getSlicesForSession(id).lastOrNull()?.stationCounterLastKwh ?: 0.0`.

---

### 2. HTTP-fallback команд управления молча возвращает `Result.success` при редиректе на `login.php`

**Где:** [ElectrosWebScraper.kt](app/src/main/java/ua/org/electros/app/data/network/ElectrosWebScraper.kt) — `postForm` (стр. 416–433), `startCharge` / `stopCharge` / `setAmperage` / `setTimer` / `executeAction` (стр. 348–414)

**Что найдено.**
В штатном режиме `fetchTelemetry()` работает через WebSocket (`queryWebSocket("site $serial")`), который не использует HTTP cookies и не извлекает `csrf_token`. Поэтому при обычной работе приложения HTTP-сессия на `electros.org.ua` либо отсутствует, либо давно истекла, а `lastCsrfToken == null`.
Когда WebSocket падает или не подтверждает команду (например, на 2-й попытке в `ChargeRepository.stopAndConfirm`), вызывается HTTP-fallback `postForm(...)` на `$baseUrl/auth/protected_page.php`.
У `OkHttpClient` включён `followRedirects(true)`: неавторизованный POST редиректится сервером (`302 → 200 OK`) на страницу входа `login.php`. Однако `postForm` проверяет **только** `if (response.isSuccessful) Result.success(Unit)`:
- не проверяет, что финальный URL/HTML — это страница логина (в отличие от `fetchTelemetryHttp` на стр. 241–243);
- не выполняет повторный `login()` под `authMutex` и не обновляет `lastCsrfToken`;
- возвращает **ложный `Result.success(Unit)`**, хотя команда (`stop`, `start`, `set_current`, `set_timer`) сервером отклонена.

Особенно заметно это в `setTimer` (расписание и Dead-Man таймер), где нет подтверждения по телеметрии — пользователь видит «✓ Налаштування передано на станцію», хотя таймер не установился.

**Как исправить.**
Перевести `postForm` на `suspend`-функцию с проверкой редиректа на логин и однократным авто-перелогином + обновлением CSRF-токена:
```kotlin
private suspend fun postForm(
    fields: List<Pair<String, String>>,
    allowRelogin: Boolean = true
): Result<Unit> = withContext(Dispatchers.IO) {
    if (lastCsrfToken == null && allowRelogin) {
        // Подтягиваем сессию и актуальный csrf_token перед POST
        fetchTelemetryHttp(allowRelogin = true)
    }

    val formBuilder = FormBody.Builder().apply {
        fields.forEach { (k, v) -> add(k, v) }
        lastCsrfToken?.let { add("csrf_token", it) }
    }
    val request = Request.Builder()
        .url("$baseUrl/auth/protected_page.php")
        .post(formBuilder.build())
        .build()

    try {
        val (code, isOk, finalUrl, html) = client.newCall(request).execute().use { resp ->
            Quad(resp.code, resp.isSuccessful, resp.request.url.toString(), resp.body?.string().orEmpty())
        }
        if (!isOk) return@withContext Result.failure(IOException("HTTP $code"))

        val redirectedToLogin = finalUrl.contains("login.php") ||
            finalUrl.contains("main.php") ||
            (html.contains("name=\"password\"") && !html.contains("Станція"))

        if (redirectedToLogin) {
            if (!allowRelogin) {
                return@withContext Result.failure(SecurityException("HTTP command rejected: not authenticated"))
            }
            val relogin = login()
            if (relogin.isFailure) return@withContext relogin
            fetchTelemetryHttp(allowRelogin = false) // обновляет lastCsrfToken
            return@withContext postForm(fields, allowRelogin = false)
        }

        extractCsrfToken(Jsoup.parse(html))
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

---

### 3. Переход на Android 17+ (`API 37`) и нарушение обязательных платформенных требований Android 17

**Где:**
- [app/build.gradle.kts](app/build.gradle.kts) (стр. 9–17)
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml) (стр. 2–4, 16–42)
- [MainActivity.kt](app/src/main/java/ua/org/electros/app/MainActivity.kt) (стр. 9–19)
- [LoginScreen.kt](app/src/main/java/ua/org/electros/app/ui/login/LoginScreen.kt) (стр. 29–35)
- [BootReceiver.kt](app/src/main/java/ua/org/electros/app/service/BootReceiver.kt) (стр. 20–32)
- [ChargeMonitorService.kt](app/src/main/java/ua/org/electros/app/service/ChargeMonitorService.kt) (стр. 77–89, 316–344)

**Что найдено.**
1. **SDK-уровни настроены на старые Android:** сейчас в `app/build.gradle.kts` выставлено `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`, а в манифесте стоит `tools:targetApi="35"`. На локальной машине уже установлен `build-tools/37.0.0`, но в `~/Library/Android/sdk/platforms` пока установлены только `android-34`, `android-35` и `android-36.1` (нет `platforms;android-37`).
2. **Принудительный Edge-to-Edge без возможности opt-out:** в Android 16/17 атрибут обхода `windowOptOutEdgeToEdgeEnforcement` окончательно удалён — в `targetSdk = 37` контент **всегда** рисуется под статус-баром, вырезом камеры и жестовой навигацией:
   - в `MainActivity.onCreate()` отсутствует вызов `enableEdgeToEdge(...)` с тёмным `SystemBarStyle.dark(...)`;
   - `LoginScreen` не обёрнут в `Scaffold` и использует только `.padding(24.dp)` без `safeDrawingPadding()` и `imePadding()`, из-за чего системные бары и клавиатура перекрывают форму входа.
3. **Запрет старта `specialUse` FGS из `BOOT_COMPLETED` и креш в `startForeground`:**
   - В Android 15–17 запуск Foreground Service типа `specialUse` напрямую из `BOOT_COMPLETED` **заблокирован ОС** (бросает `ForegroundServiceStartNotAllowedException`), если пользователь не выдал освобождение от оптимизации батареи (`PowerManager.isIgnoringBatteryOptimizations == true`).
   - В `ChargeMonitorService.ensureForegroundStarted()` вызывается устаревший 2-аргументный `startForeground(id, notification)` **без явного `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`** и **без `try/catch (ForegroundServiceStartNotAllowedException)`**, что приводит к крешу процесса при старте из фона.

**Как исправить.**
1. Доустановить платформу `sdkmanager "platforms;android-37"`, обновить AGP / Kotlin / Compose BOM и задать в `app/build.gradle.kts`:
```kotlin
android {
    namespace = "ua.org.electros.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ua.org.electros.app"
        minSdk = 37
        targetSdk = 37
        versionCode = 273
        versionName = "2.8.0"
    }
}
```
Из `AndroidManifest.xml` убрать `xmlns:tools` и `tools:targetApi="35"`, добавить на `<application>` атрибут `android:enableOnBackInvokedCallback="true"`.
2. В `MainActivity.onCreate()` включить нативный Edge-to-Edge с прозрачными тёмными барами, а на `LoginScreen` добавить `.safeDrawingPadding().imePadding()`:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    )
    super.onCreate(savedInstanceState)
    val app = application as ElectrosApplication
    setContent {
        ElectrosTheme {
            AppNavigation(application = app)
        }
    }
}
```
3. В `BootReceiver` перед стартом FGS проверять `(appContext.getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(appContext.packageName)`: если `false` — сразу вызывать `NotificationHelper(appContext).showMonitorRestartRequiredAlert()`, не пытаясь запрещённо стартовать `specialUse` сервис.
4. В `ChargeMonitorService` (без всяких проверок `SDK_INT`) вызывать нативный 3-аргументный `startForeground` с перехватом `ForegroundServiceStartNotAllowedException`:
```kotlin
private fun ensureForegroundStarted(): Boolean {
    if (foregroundStarted) return true
    val initialNotification = notificationHelper.buildForegroundNotification(
        powerKw = 0.0,
        deliveredKwh = 0.0,
        targetKwh = supervisor.activeTargetKwh,
        statusText = "Ініціалізація...",
        targetDurationMinutes = supervisor.activeTargetDurationMinutes,
        accumulatedChargingMs = supervisor.accumulatedChargingMs
    )
    return try {
        startForeground(
            NotificationHelper.NOTIFICATION_ID_STATUS,
            initialNotification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        foregroundStarted = true
        true
    } catch (_: ForegroundServiceStartNotAllowedException) {
        notificationHelper.showMonitorRestartRequiredAlert()
        stopSelf()
        false
    }
}
```

---

### 4. Отсутствует runtime-запрос разрешения `POST_NOTIFICATIONS` на Android 17

**Где:**
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml) (стр. 11)
- [MainActivity.kt](app/src/main/java/ua/org/electros/app/MainActivity.kt) (стр. 9–19)
- [DashboardScreen.kt](app/src/main/java/ua/org/electros/app/ui/dashboard/DashboardScreen.kt), [SettingsScreen.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsScreen.kt)

**Что найдено.**
Разрешение `android.permission.POST_NOTIFICATIONS` объявлено в манифесте, но **нигде в коде не запрашивается у пользователя в рантайме**. На Android 17 оно всегда запрещено по умолчанию после установки.
Из-за этого система молча блокирует **все** уведомления приложения:
- критический алерт `showStopFailedAlert()` («Не вдалося зупинити станцію!»);
- алерт отключения света `showOutageAlert()`;
- уведомление `showManualResumeAvailableAlert()` с кнопкой «Відновити заряд» (когда `autoResumeAfterOutage = false`);
- уведомление `showMonitorRestartRequiredAlert()`, если система заблокировала фоновый старт FGS;
- постоянную карточку прогресса зарядки с кнопкой «Зупинити».

**Как исправить.**
1. Так как `minSdk = 37`, никаких проверок `SDK_INT` не требуется: на `DashboardScreen` (при первом входе после авторизации и перед `onStartClicked`) напрямую проверять `ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)` и запускать `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`.
2. В `SettingsScreen` добавить строку статуса уведомлений (`NotificationManager.areNotificationsEnabled()`) с кнопкой открытия `Settings.ACTION_APP_NOTIFICATION_SETTINGS` (`putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)`).

---

### 5. Затирание пароля пустой строкой на экране «Налаштування»

**Где:**
- [SettingsScreen.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsScreen.kt) (стр. 45–46, 154–163)
- [SettingsViewModel.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsViewModel.kt) — `updateCredentials` (стр. 31–38)

**Что найдено.**
В `SettingsScreen` поле `serialInput` инициализируется текущим серийным номером, а `passwordInput` — пустой строкой `""` (стр. 46). При нажатии кнопки «Зберегти нові дані» вызывается `viewModel.updateCredentials(serialInput, passwordInput)` и сразу рисуется `"✓ Збережено"`.
Метод `SettingsViewModel.updateCredentials`:
- не проверяет `serial.isNotBlank()` и `pass.isNotBlank()`;
- **безусловно перезаписывает `authPreferences.internetPassword` пустой строкой `""`**;
- не проверяет валидность новых данных через `repository.login(...)` и не очищает cookies предыдущей станции.
При следующем запуске приложения `hasValidCredentials()` возвращает `false`, и пользователь теряет сохранённый доступ.

**Как исправить.**
1. Сделать кнопку «Зберегти нові дані» неактивной (`enabled = serialInput.isNotBlank() && passwordInput.isNotBlank() && !state.isSaving`) либо при пустом `passwordInput` явно требовать ввод пароля с сообщением об ошибке.
2. Сохранять новые учётные данные через `repository.login(serialInput.trim(), passwordInput.trim())` (предварительно очищая старые cookies), выставляя `savedSuccess = true` только после успешного ответа сервера.

---

### 6. Кнопка «Зупинити» в уведомлении не работает после пересоздания сервиса

**Где:**
- [ChargeMonitorService.kt](app/src/main/java/ua/org/electros/app/service/ChargeMonitorService.kt) — `onStartCommand` (стр. 105–110)
- [SessionSupervisor.kt](app/src/main/java/ua/org/electros/app/domain/monitor/SessionSupervisor.kt) — `handleManualStop` (стр. 647–672)

**Что найдено.**
Когда пользователь нажимает «Зупинити» в уведомлении, `PendingIntent` запускает `ChargeMonitorService` с `action = ACTION_STOP_CHARGE` без `EXTRA_SESSION_ID`. В `onStartCommand` сразу вызывается `handleManualStop()` без вызова `supervisor.onSessionStarted(...)` и `hydrateFromDatabase()`.
Если процесс сервиса был ранее выгружен системой и пересоздан для обработки этого интента, `supervisor.activeSessionId` равен `null`. В `SessionSupervisor.handleManualStop()` написано:
```kotlin
val id = activeSessionId
    ?: return@withLock TickOutcome(stateMachine.getState(), events, shouldStopService = true)
```
В отличие от `handleManualResume()` (где на стр. 680 есть fallback `val id = activeSessionId ?: gateway.getActiveSession()?.id`), `handleManualStop()` немедленно завершает сервис **без отправки команды `stop` на станцию и без закрытия активной сессии в Room**.

**Как исправить.**
В `SessionSupervisor.handleManualStop()` подгружать активную сессию из БД, а если даже записи в БД нет — всё равно отправлять подтверждаемый `stop` на станцию:
```kotlin
val id = activeSessionId ?: gateway.getActiveSession()?.id
if (id == null) {
    val stopped = gateway.stopAndConfirm(allowOfflineConfirmation = true)
    return@withLock TickOutcome(
        status = if (stopped) SessionStatus.CANCELLED else SessionStatus.STOP_PENDING,
        events = if (stopped) events else events.apply { add(SupervisorEvent.ShowStopFailed) },
        shouldStopService = stopped
    )
}
activeSessionId = id
```

---

### 7. Неполный `logout()`: утечка сессионных cookies, фонового сервиса и `ViewModel`

**Где:**
- [ChargeRepository.kt](app/src/main/java/ua/org/electros/app/data/repository/ChargeRepository.kt) — `logout` (стр. 73–77)
- [SettingsViewModel.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsViewModel.kt) — `logout` (стр. 44–50)
- [SettingsScreen.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsScreen.kt) (стр. 165–174)
- [AppNavigation.kt](app/src/main/java/ua/org/electros/app/ui/navigation/AppNavigation.kt) (стр. 48–69)
- [LoginViewModel.kt](app/src/main/java/ua/org/electros/app/ui/login/LoginViewModel.kt) (стр. 24–63)

**Что найдено.**
При нажатии «Вийти зі станції» происходят сразу четыре проблемы:
1. **Сессионные cookies и CSRF остаются живыми:** `CookieStorage.clear()` нигде не вызывается, а `ElectrosWebScraper.lastCsrfToken` не сбрасывается. HTTP-клиент остаётся авторизованным на сервере под старым аккаунтом.
2. **Не останавливается `ChargeMonitorService`:** если шла зарядка или ожидание света, Foreground Service продолжает работать в фоне с `PARTIAL_WAKE_LOCK` после выхода из аккаунта.
3. **Устаревший `SettingsViewModel` после повторного входа:** в `AppNavigation` переключение `isLoggedIn` происходит внутри одной `MainActivity`, поэтому `ViewModelStore` не очищается. Когда пользователь входит под новой станцией, `viewModel(factory = factory)` возвращает старый экземпляр `SettingsViewModel`, в котором `deviceSerial` остался `""` с момента `logout()`.
4. **Пароль в открытом виде остаётся в куче:** `LoginViewModel` также остаётся в `MainActivity.viewModelStore` на всё время работы приложения, удерживая открытый `password: String` в `_uiState`.

**Как исправить.**
- В `ElectrosWebScraper` добавить метод `clearSession()`, обнуляющий `lastCsrfToken = null`, `resetAuthBackoff()` и вызывающий `cookieStorage?.clear()`. Вызывать его из `ChargeRepository.logout()`.
- При `logout` в UI вызывать `ChargeMonitorService.stop(context)` и закрывать локальные активные сессии (`cancelAllActiveSessions()`).
- В `LoginViewModel.login` при `onSuccess` сразу очищать пароль из состояния: `_uiState.value = _uiState.value.copy(password = "", isLoading = false)`.
- Для `SettingsViewModel` обновлять `deviceSerial` при входе на экран (либо хранить `StateFlow` в `AuthPreferences`, либо скоупить авторизованные `ViewModel` в отдельный `NavBackStackEntry`).

---

### 8. Устаревшая (deprecated) библиотека `security-crypto` и общий `MasterKey`, способный стереть учётные данные

**Где:**
- [EncryptedPrefsFactory.kt](app/src/main/java/ua/org/electros/app/data/local/EncryptedPrefsFactory.kt) (стр. 1–78)
- [AuthPreferences.kt](app/src/main/java/ua/org/electros/app/data/local/AuthPreferences.kt) (стр. 10–35)
- [CookieStorage.kt](app/src/main/java/ua/org/electros/app/data/network/CookieStorage.kt) (стр. 10–27)
- [gradle/libs.versions.toml](gradle/libs.versions.toml) (стр. 13: `securityCrypto = "1.1.0-alpha06"`)

**Что найдено.**
1. Библиотека `androidx.security:security-crypto:1.1.0-alpha06` (`EncryptedSharedPreferences`, `MasterKey`) официально **deprecated** в AndroidX, брошена на стадии вечной альфы и опирается на устаревший API `SharedPreferences`. Для чистого проекта под Android 17+ тянуть её не нужно.
2. В текущей реализации `AuthPreferences` (`auth_prefs`) и `CookieStorage` (`electros_cookies_encrypted`) используют один и тот же `MasterKey.DEFAULT_MASTER_KEY_ALIAS`. Если файл cookies повреждён, шаг восстановления `deleteDefaultMasterKey()` удаляет общий мастер-ключ из `AndroidKeyStore`, после чего при следующем запуске расшифровка `auth_prefs` падает и стирает логин/пароль станции.

**Как исправить (современный подход для Android 17 / API 37).**
Полностью отказаться от `androidx.security:security-crypto` и `SharedPreferences`:
- На Android 17 аппаратный `AndroidKeyStore` гарантированно поддерживает `AES/GCM/NoPadding` (`KeyProperties.KEY_ALGORITHM_AES`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, 256 бит). Шифровать учётные данные напрямую через стандартный `javax.crypto.Cipher` + `KeyGenParameterSpec` и хранить их в типизированном `DataStore` (либо использовать актуальный `com.google.crypto.tink:tink-android` поверх `DataStore`).
- Для `CookieStorage` либо использовать отдельный алиас ключа Keystore, либо вообще хранить краткоживущие PHP-сессионные cookies только в памяти процесса (`ConcurrentHashMap` в `CookieJar`), так как при перезапуске процесса скрапер прозрачно авторизуется заново.

---

### 9. Фейковые показатели защитной автоматики (заземление и УЗО) в «Налаштуваннях»

**Где:**
- [SettingsScreen.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsScreen.kt) (стр. 179–191)
- [SettingsViewModel.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsViewModel.kt) (стр. 12)
- [StationConfig.kt](app/src/main/java/ua/org/electros/app/data/model/StationConfig.kt) (стр. 3–14)

**Что найдено.**
Карточка «АПАРАТНІ ПАРАМЕТРИ СТАНЦІЇ» отображает:
- `Контроль заземлення: Увімкнено` (строковый литерал прямо в `SettingsScreen.kt:183`);
- `Струм витоку (УЗО): > 30мА`;
- `Мін. напруга заряджання: 175 В`;
- `Сервер станції: electros.org.ua:8000`.

Ни одно из этих полей нигде в проекте не загружается со станции (`StationConfig()` всегда создаётся с дефолтными константами). Если на физической зарядной станции пользователь отключил контроль заземления или изменил порог минимального напряжения в веб-кабинете, приложение продолжает утверждать, что защита включена. Для силового оборудования зарядки электромобиля отображение фиктивного статуса защит недопустимо.

**Как исправить.**
Либо парсить реальные параметры конфигурации станции из пакета WebSocket (в нём уже приходят хост, порт и флаги режимов в строках `4`, `5`, `6`, `14`) / HTML-страницы настроек и показывать `"Н/Д"` до получения данных, либо удалить карточку с неподтверждёнными параметрами электробезопасности.

---

### 10. Сломанная аналитика на экране «Історія»: зашитый `"Листопад 2026"` и сумма за всё время вместо месяца

**Где:**
- [HistoryScreen.kt](app/src/main/java/ua/org/electros/app/ui/history/HistoryScreen.kt) (стр. 64, 90, 99–128)
- [HistoryViewModel.kt](app/src/main/java/ua/org/electros/app/ui/history/HistoryViewModel.kt) (стр. 12–28)

**Что найдено.**
1. В `HistoryScreen.kt:64` название месяца захардкожено строкой `"Листопад 2026"`.
2. В `HistoryViewModel.kt:22` `val monthTotal = list.sumOf { it.totalKwhDelivered }` складывает **все сессии в базе за всё время**, не фильтруя их по текущему месяцу и году.
3. В карточке сессии выводится сырое техническое имя enum `session.status.name` (`VEHICLE_STOPPED`, `TARGET_REACHED`, `STOP_PENDING`), а у открытой активной сессии отображается `0.0 кВт⋅год`.

**Как исправить.**
Фильтровать сессии по текущему `YearMonth` в системной таймзоне и формировать подпись месяца динамически через `Locale.of("uk", "UA")` (современный JDK API вместо deprecated конструктора `Locale(String, String)`):
```kotlin
val ukLocale = Locale.of("uk", "UA")
val zone = ZoneId.systemDefault()
val currentMonth = YearMonth.now(zone)
val monthLabel = currentMonth.format(
    DateTimeFormatter.ofPattern("LLLL yyyy", ukLocale)
).replaceFirstChar { it.titlecase(ukLocale) }

val monthTotal = list
    .filter { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(zone)) == currentMonth }
    .sumOf { it.totalKwhDelivered }
```
Также добавить `key = { it.id }` в `LazyColumn` (`items(state.sessions, key = { it.id })`) и маппинг `SessionStatus` в локализованные статусы.

---

## 🟠 MEDIUM: Протокол, OTA, конкурентность и доменная логика

### 11. Управление по WebSocket без пароля и автозапоминание серийного номера клавиатурой

**Где:**
- [ElectrosWebScraper.kt](app/src/main/java/ua/org/electros/app/data/network/ElectrosWebScraper.kt) (стр. 83–87, 136–140, 350–395)
- [LoginScreen.kt](app/src/main/java/ua/org/electros/app/ui/login/LoginScreen.kt) (стр. 75–86)

**Что найдено.**
Сервер `wss://electros.org.ua/ws/` принимает команды `site <serial>\nstart...`, `stop`, `datetime` без какой-либо сессионной аутентификации — единственным идентификатором выступает `deviceSerial` (вместе с шаблонным заголовком `Sec-WebSocket-Protocol: example-protocol`). Следовательно, `deviceSerial` фактически является секретным ключом управления станцией.
При этом на `LoginScreen` поле `Номер пристрою` не отключает автокоррекцию и персонализированное обучение клавиатуры (`KeyboardOptions` не заданы), из-за чего клавиатура может сохранить серийный номер в открытый словарь подсказок.

**Как исправить.**
- На `LoginScreen` и `SettingsScreen` для поля серийного номера указать:
  `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false)`
- На стороне прошивки/сервера `electros.org.ua` (если есть доступ к бэкенду): проверять авторизованную cookie или токен при открытии WebSocket и заменить `example-protocol` на осмысленный subprotocol.

---

### 12. Проблемы в OTA-обновлении: повторное скачивание APK, утечка `Context`, гонка записи и устаревший `HttpURLConnection`

**Где:**
- [OtaViewModel.kt](app/src/main/java/ua/org/electros/app/ota/OtaViewModel.kt) (стр. 34–89)
- [OtaUpdater.kt](app/src/main/java/ua/org/electros/app/ota/OtaUpdater.kt) (стр. 17–18, 74–78, 271–454)
- [UpdateDialog.kt](app/src/main/java/ua/org/electros/app/ui/ota/UpdateDialog.kt) (стр. 27, 200)

**Что найдено.**
1. **Повторное скачивание APK при первом обновлении:** когда `canRequestPackageInstalls() == false`, `OtaUpdater.installApk()` открывает системные настройки «Неизвестные источники» и выходит, а `OtaViewModel` на стр. 83 сразу скрывает диалог (`_showUpdateDialog.value = false`). Вернувшись из настроек с выданным разрешением, пользователь вынужден заново проверять обновление и скачивать APK с нуля.
2. **Утечка `Activity` в `ViewModel`:** `startDownloadAndInstall(context: Context)` захватывает `Activity` внутри `viewModelScope.launch` на всё время загрузки файла.
3. **Гонка параллельных загрузок:** нет проверки `if (_isCheckingUpdate.value) return` и `if (_isDownloadingUpdate.value) return`; повторный вызов параллельно пишет в тот же `cacheDir/electros_update.apk`.
4. **Устаревший сетевой стек в `OtaUpdater`:** хотя в приложении уже подключён `OkHttpClient` (HTTP/2, пул соединений, современный TLS), `OtaUpdater` использует древний `java.net.HttpURLConnection` и конструктор `java.net.URL(String)` (официально **deprecated** с Java 20) с ручным циклом обработки HTTP-редиректов `301/302/303/307/308`.
5. **Опциональный SHA-256:** на стр. 375 `OtaUpdater.kt` проверка `computeSha256` выполняется только при `expectedSha256 != null`. Если у релиза в GitHub API отсутствует `asset.digest`, проверка контрольной суммы пропускается.

**Как исправить.**
- Переписать `OtaUpdater` на общий `OkHttpClient` + `HttpUrl.toHttpUrlOrNull()` с сетевым `Interceptor`, проверяющим хост каждого редиректа по белому списку `ALLOWED_REDIRECT_HOSTS`.
- В `OtaViewModel` передавать `context.applicationContext`, добавить защиту `if (_isDownloadingUpdate.value) return` и скачивать во временный файл `electros_update.apk.part` с атомарным переименованием после проверки SHA-256 и сертификата подписи.
- Возвращать из `OtaUpdater.installApk` статус (`LAUNCHED_INSTALLER`, `NEEDS_UNKNOWN_SOURCES_PERMISSION`, `FAILED`). При `NEEDS_UNKNOWN_SOURCES_PERMISSION` сохранять `pendingVerifiedApk: File?` во `ViewModel` и автоматически запускать установку по `Lifecycle.Event.ON_RESUME`, когда `canRequestPackageInstalls() == true`.

---

### 13. Гонки состояний в `ElectrosWebScraper`, `SettingsStore` и дублирующийся опрос телеметрии

**Где:**
- [ElectrosWebScraper.kt](app/src/main/java/ua/org/electros/app/data/network/ElectrosWebScraper.kt) (стр. 61, 65–68, 274)
- [SettingsStore.kt](app/src/main/java/ua/org/electros/app/data/local/SettingsStore.kt) (стр. 112–120, 300–355)
- [DashboardViewModel.kt](app/src/main/java/ua/org/electros/app/ui/dashboard/DashboardViewModel.kt) — `pollWhileVisible` (стр. 240–246)

**Что найдено.**
1. В `ElectrosWebScraper` счётчик `private var authFailures: Int = 0` не защищён `@Volatile` и сбрасывается вне `authMutex` в `resetAuthBackoff()` и `fetchTelemetryHttp()`.
2. В `SettingsStore`: если вызов любого сеттера (`update()`) произойдёт до первого эмита `dataStore.data`, флаг `hasAnyLocalWrite` становится `true`, отключая первичный коллектор (`if (!hasAnyLocalWrite)`). Если при этом запись `dataStore.edit` упадёт с `IOException` (который поглощается на стр. 331 и возвращает `null`), `_settings.value` навсегда останется с дефолтными `AppSettings()`, проигнорировав сохранённые на диске настройки.
3. Когда запущен `ChargeMonitorService` (опрос каждые 10 с) и открыт `DashboardScreen`, `pollWhileVisible()` крутит второй независимый цикл опроса (каждые 60 с), создавая лишние параллельные WebSocket-подключения, хотя оба пишут в один и тот же `repository.telemetryState`.

**Как исправить.**
- Сделать `authFailures` `AtomicInteger` (либо менять его строго внутри `authMutex.withLock`).
- В `SettingsStore.flushPendingEdits()` перед применением пачки дожидаться первой загрузки `dataStore.data.first()` или при `committed == null` не подавлять коллектор `dataStore.data`.
- В `DashboardViewModel.pollWhileVisible()` пропускать периодический `fetchLatestTelemetry`, пока `isMonitorServiceRunning() == true` (достаточно одного начального запроса при открытии экрана).

---

### 14. Ошибки расчёта Dead-Man таймера при ручной смене тока, завышении напряжения и лимите 16 А / 32 А

**Где:**
- [DashboardViewModel.kt](app/src/main/java/ua/org/electros/app/ui/dashboard/DashboardViewModel.kt) — `onAmperageSelected` (стр. 267–283)
- [EnergyAccumulator.kt](app/src/main/java/ua/org/electros/app/domain/accumulator/EnergyAccumulator.kt) — `calculateDeadManTimerMinutes` (стр. 48–61)
- [ElectrosWebScraper.kt](app/src/main/java/ua/org/electros/app/data/network/ElectrosWebScraper.kt) — `setAmperage`, `amperageToIndex` (стр. 375–406)
- [DashboardScreen.kt](app/src/main/java/ua/org/electros/app/ui/dashboard/DashboardScreen.kt) (стр. 554–557), [LimiterScreen.kt](app/src/main/java/ua/org/electros/app/ui/limiter/LimiterScreen.kt) (стр. 495–565)

**Что найдено.**
1. **Dead-Man таймер не обновляется при ручном изменении тока:** когда DLB меняет ток в `SessionSupervisor`, он вызывает `refreshDeadManTimer(...)`. Но когда пользователь вручную крутит ручку тока во время активной сессии (`DashboardViewModel.onAmperageSelected`, стр. 267), `refreshDeadManTimer` **не вызывается**. Если старт был на `16 А`, а пользователь снизил ток до `6 А`, аппаратный таймер станции останется рассчитанным под `16 А` и преждевременно выключит зарядку!
2. **Преждевременное срабатывание Dead-Man таймера при просадке напряжения:** в `calculateDeadManTimerMinutes` стоит `val effectiveVoltage = voltageVolts.coerceAtLeast(200)` и фиксированный буфер `15 минут`. При реальном напряжении в гараже `180 В` расчёт завышает мощность на ~11%, а во время зарядки (если DLB не переключал ступени) окно таймера ни разу не сдвигается вперёд — на длинной сессии (>3 часов) станция может отключиться по аппаратному расписанию раньше достижения лимита кВт⋅ч.
3. **Нечётный ток в `setAmperage`:** `amperageToIndex(amperes)` округляет нечётные значения вниз до чётного (`15 А → 14 А`), но `sendAndVerify` проверяет `it.selectedCurrentAmperes == amperes` (`14 == 15 → false`), всегда проваливаясь в лишний HTTP-fallback.
4. **Несогласованность диапазонов тока и порогов DLB:**
   - Станции и парсер поддерживают `6..32 А`, но роторный селектор поддерживает только `listOf(6, 8, 10, 12, 16)`. Если на станции выставлено `24 А` или `32 А`, ручка на `DashboardScreen` молча отображает `10 А` (`indexOf(...).takeIf { it >= 0 } ?: 2`).
   - В `AppSettings` пороги DLB допускают `160..200 В` / `195..230 В` и ток `6..16 А`, а слайдеры `LimiterScreen` режут их до `170..195 В` / `200..225 В` и `8..16 А` (включая `14 А`, которого нет на ручке Dashboard).

**Как исправить.**
- В `DashboardViewModel.onAmperageSelected` после успешного `repository.setAmperage(amperes)` при `active != null` вызывать `repository.refreshDeadManTimer(active.id, fresh.voltageCurrent ?: 230, currentState.totalDeliveredKwh)`.
- В `EnergyAccumulator.calculateDeadManTimerMinutes` снизить нижний предел напряжения до `voltageVolts.coerceAtLeast(170)`, сделать буфер пропорциональным (`maxOf(20, (requiredMinutes * 0.15).roundToInt())`), а в `SessionSupervisor` обновлять `refreshDeadManTimer` раз в 15–20 минут.
- В `ElectrosWebScraper.setAmperage` проверять чётный целевой ток: `val targetEven = if (amperes % 2 != 0) amperes - 1 else amperes` и `it.selectedCurrentAmperes == targetEven`.
- Привести диапазоны `LimiterScreen` и `AppSettings` к единым константам.

---

### 15. Игнорирование сетевых ошибок в UI и смешение трёх языков

**Где:**
- [LimiterViewModel.kt](app/src/main/java/ua/org/electros/app/ui/limiter/LimiterViewModel.kt) — `applyScheduleToStation` (стр. 140–149)
- [DashboardViewModel.kt](app/src/main/java/ua/org/electros/app/ui/dashboard/DashboardViewModel.kt) — `onAmperageSelected` (стр. 267–283), `setScheduleTimer` (стр. 340–350)
- UI-файлы в `ui/dashboard`, `ui/limiter`, `ui/history`, `ui/settings`, `ui/login`, `service/NotificationHelper.kt`

**Что найдено.**
1. В `LimiterViewModel.applyScheduleToStation()` возвращаемый `Result` от `repository.setScheduleTimer(...)` полностью игнорируется, и всегда выставляется `_isScheduleSaved.value = true` (`"✓ Налаштування передано на станцію"`), даже если сеть недоступна. Аналогично не обрабатываются ошибки `setScheduleTimer` и `setAmperage` в `DashboardViewModel`.
2. Интерфейс смешивает языки: `DashboardScreen` переведён на английский, но баннер ошибок на нём же выводит украинские тексты исключений из `ChargeRepository`, нижняя навигация и остальные экраны — на украинском, а в `LimiterScreen.kt:283` для часов использовано русское сокращение `"${h}ч"` (рядом с `"${state.targetDurationHours} год"` строкой выше). Все строки захардкожены в коде вместо `res/values/strings.xml`.

**Как исправить.**
- Проверять `val res = repository.setScheduleTimer(...)`: ставить `_isScheduleSaved.value = res.isSuccess`, а при `res.onFailure` показывать ошибку пользователю.
- Вынести все пользовательские строки в `res/values/strings.xml` (EN) и `res/values-uk/strings.xml` (UK), передавая из `Repository` типизированные коды ошибок (sealed enum/class) вместо зашитых строк.

---

## 🟡 MINOR: Удаление legacy-кода старых Android (< 17), мёртвого кода, лишних зависимостей и ресурсов

### 16. Удаление всех веток `Build.VERSION.SDK_INT`, `@RequiresApi` и `@Suppress("DEPRECATION")` в `PackageManager`

**Где:**
- [ChargeMonitorService.kt](app/src/main/java/ua/org/electros/app/service/ChargeMonitorService.kt) (стр. 325–343)
- [NotificationHelper.kt](app/src/main/java/ua/org/electros/app/service/NotificationHelper.kt) (стр. 32–36, 46)
- [OtaUpdater.kt](app/src/main/java/ua/org/electros/app/ota/OtaUpdater.kt) (стр. 110–131, 422, 437–444)

**Что найдено.**
При целевом `minSdk = 37` (Android 17) весь код условной совместимости со старыми версиями Android становится мёртвым балластом:
1. В `ChargeMonitorService.start()` — `when` по `SDK_INT >= S` / `SDK_INT >= O` / `else { startService }` и отдельный метод `@RequiresApi(S) startForegroundApi31`.
2. В `NotificationHelper` — две проверки `SDK_INT >= O` и мёртвая ветка `PendingIntent.getService(...)`.
3. В `OtaUpdater.installApk()` — проверки `SDK_INT >= O` вокруг `canRequestPackageInstalls()` и `SDK_INT >= N` с мёртвой веткой `Uri.fromFile(apkFile)`.
4. В `OtaUpdater.hasMatchingSigningCertificate()` (стр. 110–131) — ветвление `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)` с древним `GET_SIGNATURES` / `signatures` и **6 подавлений `@Suppress("DEPRECATION")`**, потому что целочисленные флаги `getPackageInfo(String, Int)` и `getPackageArchiveInfo(String, Int)` устарели ещё в API 33!

**Как исправить.**
Удалить все проверки `Build.VERSION.SDK_INT` и заменить вызовы `PackageManager` на современный типизированный API `PackageInfoFlags`:
```kotlin
internal fun hasMatchingSigningCertificate(context: Context, apkFile: File): Boolean {
    return try {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        )
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(apkFile.path, flags)
        val installedDigests = installed.signingInfo?.apkContentsSigners
            .orEmpty().map { sha256Hex(it.toByteArray()) }.sorted()
        val archiveDigests = archive?.signingInfo?.apkContentsSigners
            .orEmpty().map { sha256Hex(it.toByteArray()) }.sorted()

        doCertificatesMatch(
            expectedPackage = context.packageName,
            archivePackage = archive?.packageName,
            installedDigests = installedDigests,
            archiveDigests = archiveDigests
        )
    } catch (e: Exception) {
        false
    }
}
```

---

### 17. Удаление до-Android-12 правил бэкапа (`backup_rules.xml`) и до-Android-8 приоритетов уведомлений (`setPriority`)

**Где:**
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml) (стр. 19: `android:fullBackupContent="@xml/backup_rules"`)
- [app/src/main/res/xml/backup_rules.xml](app/src/main/res/xml/backup_rules.xml) (весь файл)
- [NotificationHelper.kt](app/src/main/java/ua/org/electros/app/service/NotificationHelper.kt) (стр. 116, 135, 159, 174, 209, 221, 235, 246, 272, 286)

**Что найдено.**
1. **`backup_rules.xml` игнорируется на Android 12–17:** атрибут `android:fullBackupContent` используется системой **только на Android 11 и ниже (`API <= 30`)**. Начиная с API 31 и на Android 17 работает исключительно `android:dataExtractionRules="@xml/data_extraction_rules"`. Файл `backup_rules.xml` не читается ОС вовсе.
2. **10 устаревших вызовов `NotificationCompat.Builder.setPriority(...)`:** начиная с Android 8.0 (`API 26`), индивидуальный приоритет уведомления игнорируется системой — важность определяется исключительно каналом `NotificationChannel`. Из-за этого в `showBalancingAlert` (стр. 209) попытка понизить важность при повышении тока (`if (isLowered) PRIORITY_HIGH else PRIORITY_DEFAULT`) внутри высокоприоритетного канала `CHANNEL_ID_ALERTS` фактически **не работает** и всегда бьёт heads-up баннером.

**Как исправить.**
- Удалить атрибут `android:fullBackupContent="@xml/backup_rules"` из `AndroidManifest.xml` и удалить файл `res/xml/backup_rules.xml`.
- Удалить все 10 вызовов `.setPriority(...)` в `NotificationHelper.kt`, а для информационных уведомлений повышения тока DLB завести отдельный канал с `NotificationManager.IMPORTANCE_DEFAULT` (или `IMPORTANCE_LOW`).

---

### 18. Удаление legacy-миграций со старых версий хранилищ (~130 строк кода)

**Где:**
- [SettingsStore.kt](app/src/main/java/ua/org/electros/app/data/local/SettingsStore.kt) (стр. 377, 399, 408–410, 444–524 — класс `LegacyAuthPrefsMigration`)
- [AuthPreferences.kt](app/src/main/java/ua/org/electros/app/data/local/AuthPreferences.kt) (стр. 15, 37–57 — `legacyPrefsForMigration`, `LEGACY_SETTINGS_KEYS`)
- [EncryptedPrefsFactory.kt](app/src/main/java/ua/org/electros/app/data/local/EncryptedPrefsFactory.kt) (стр. 20, 33, 38 — `legacyPlaintextNames`)
- [CookieStorage.kt](app/src/main/java/ua/org/electros/app/data/network/CookieStorage.kt) (стр. 16 — `legacyPlaintextNames = listOf("electros_cookies")`)
- [data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml) (исключения для `auth_prefs_fallback.xml`, `electros_cookies.xml`, `__androidx_security_crypto_encrypted_prefs__.xml`)
- [AppDatabase.kt](app/src/main/java/ua/org/electros/app/data/local/AppDatabase.kt) (стр. 28–34 — `MIGRATION_1_2`)

**Что найдено.**
Кодовая база тащит за собой одноразовые миграции от ранних прототипов приложения (перенос 14 ключей из старого `SharedPreferences` в `DataStore`, подчистка незашифрованных `auth_prefs_fallback` и `electros_cookies`, миграция Room `1 -> 2` и соответствующие тесты `SettingsStoreTest` / `AuthPreferencesTest`). Для приложения с нуля под Android 17+ эти переходные мосты не нужны.

**Как исправить.**
Удалить `LegacyAuthPrefsMigration`, `KEY_MIGRATED_FROM_AUTH_PREFS`, `LEGACY_SETTINGS_KEYS`, `legacyPrefsForMigration()`, `legacyPlaintextNames`, устаревшие записи в `data_extraction_rules.xml` и (при отказе от совместимости со старой локальной БД v1) сжать схему Room до чистой версии `1`.

---

### 19. Мёртвый класс `ReconciliationWorker` и 4 лишние группы зависимостей

**Где:**
- [ReconciliationWorker.kt](app/src/main/java/ua/org/electros/app/service/ReconciliationWorker.kt) (весь файл)
- [app/build.gradle.kts](app/build.gradle.kts) (стр. 93, 98–99, 102–103)
- [gradle/libs.versions.toml](gradle/libs.versions.toml) (стр. 12–13, 16, 19–20, 30, 34–35, 42–43)

**Что найдено.**
В проекте подключены зависимости, которые либо мертвы, либо устарели:
1. **`androidx.work:work-runtime-ktx` + `ReconciliationWorker.kt`:** класс нигде не вызывается, но библиотека при каждом холодном старте приложения запускает `WorkManagerInitializer` и создаёт пустую БД `androidx.work.workdb`.
2. **`androidx.navigation:navigation-compose`:** ни один класс из `androidx.navigation.*` нигде в проекте не импортируется.
3. **`androidx.security:security-crypto` (`1.1.0-alpha06`):** deprecated альфа-библиотека (см. п. 8).
4. **`androidx.test.espresso:espresso-core` и `androidx.test.ext:junit` (`androidTestImplementation`) + `testInstrumentationRunner`:** директории `app/src/androidTest` в проекте нет.

**Как исправить.**
Удалить `ReconciliationWorker.kt` и убрать все четыре группы неиспользуемых/deprecated зависимостей из `app/build.gradle.kts` и `gradle/libs.versions.toml`.

---

### 20. Замена устаревших Java / Android / Kotlin API на современные идиомы

**Где и что найдено:**
1. **`SimpleDateFormat` и `java.util.Date`** в [HistoryScreen.kt](app/src/main/java/ua/org/electros/app/ui/history/HistoryScreen.kt) (стр. 19–20, 106–107) → заменить на потокобезопасный `java.time.Instant` + `java.time.format.DateTimeFormatter` (уже используется в остальных частях проекта).
2. **`NavTab.values()`** в [AppNavigation.kt](app/src/main/java/ua/org/electros/app/ui/navigation/AppNavigation.kt) (стр. 77) → заменить на современный `NavTab.entries` (не аллоцирует массив при каждой рекомпозиции).
3. **`Uri.parse(...)`** в [OtaViewModel.kt](app/src/main/java/ua/org/electros/app/ota/OtaViewModel.kt) (стр. 63), [OtaUpdater.kt](app/src/main/java/ua/org/electros/app/ota/OtaUpdater.kt) (стр. 426), [SettingsScreen.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsScreen.kt) (стр. 219) → заменить на KTX-расширение `"…".toUri()` (`androidx.core.net.toUri`).
4. **Старые системные растровые иконки времён Android 4.x (`android.R.drawable.ic_lock_idle_charging`, `stat_sys_warning`, `ic_media_pause`, `ic_media_play`)** в [NotificationHelper.kt](app/src/main/java/ua/org/electros/app/service/NotificationHelper.kt) → заменить на собственные монохромные векторные иконки в `res/drawable/`, корректно отображающиеся в статус-баре и системных панелях Android 17.
5. **Backward-compatibility алиасы старой цветовой схемы** в [Color.kt](app/src/main/java/ua/org/electros/app/ui/theme/Color.kt) (стр. 22–38: `PrimaryNeonGreen = AmberNixie`, `SurfaceDark = StudioChassisDark` и т.д.) и дублирующие прокси-методы в `companion object` [LimiterViewModel.kt](app/src/main/java/ua/org/electros/app/ui/limiter/LimiterViewModel.kt) (стр. 169–179) → использовать актуальные имена напрямую и удалить обёртки обратной совместимости.

**Как исправить.**
Привести все перечисленные места к современному API Kotlin 2.x / AndroidX / `java.time`.

---

### 21. Мёртвый код внутри моделей, парсера и ViewModels

**Где и что найдено:**
1. **[StationConfig.kt](app/src/main/java/ua/org/electros/app/data/model/StationConfig.kt) (стр. 4, 7–8, 11–13):** 6 из 10 полей (`isGroundingControlEnabled`, `wifiSsid`, `wifiPassword`, `deviceSerial`, `voltageCalibrationConstant`, `currentCalibrationConstant`) нигде не читаются и не изменяются.
2. **[TelemetryData.kt](app/src/main/java/ua/org/electros/app/data/model/TelemetryData.kt) (стр. 6–7, 9–13, 19) и [ElectrosWebScraper.kt](app/src/main/java/ua/org/electros/app/data/network/ElectrosWebScraper.kt) (стр. 469–475, 552–572):** 8 полей (`voltageMin`, `voltageAvg`, `voltageMax`, `neutralMin`, `neutralAvg`, `neutralCurrent`, `neutralMax`, `timestamp`) парсятся из WS и HTML на каждом тике, но нигде в приложении не используются.
3. **[HistoryViewModel.kt](app/src/main/java/ua/org/electros/app/ui/history/HistoryViewModel.kt) (стр. 15):** захардкоженное поле `val previousMonthKwh: Double = 96.9` нигде не читается.
4. **[LoginViewModel.kt](app/src/main/java/ua/org/electros/app/ui/login/LoginViewModel.kt) (стр. 16, 56):** флаг `val isSuccess: Boolean = false` в `LoginUiState` никогда не читается UI.
5. **[ChargeSliceEntity.kt](app/src/main/java/ua/org/electros/app/data/local/entity/ChargeSliceEntity.kt) (стр. 13):** константа `SliceEndReason.COUNTER_REGRESSION` нигде не присваивается.
6. **[Color.kt](app/src/main/java/ua/org/electros/app/ui/theme/Color.kt) (стр. 12–14, 38):** цвета `AmberNixieDim`, `AmberNixieGlow`, `AmberNixieText`, `WarningContainerDark` нигде не используются.
7. **Неиспользуемые импорты:** `Icons.Default.Refresh` в [UpdateDialog.kt](app/src/main/java/ua/org/electros/app/ui/ota/UpdateDialog.kt) (стр. 10); `detectDragGestures`, `detectTapGestures`, `androidx.compose.animation.*` в [DashboardScreen.kt](app/src/main/java/ua/org/electros/app/ui/dashboard/DashboardScreen.kt) (стр. 4, 15–16); `Modifier.clip`, `Color` в [HistoryScreen.kt](app/src/main/java/ua/org/electros/app/ui/history/HistoryScreen.kt) (стр. 11–12).

**Как исправить.**
Удалить перечисленные неиспользуемые поля, константы и импорты (либо вывести показатели нейтрали и min/max напряжения в UI, если они востребованы).

---

### 22. Шаблонные неиспользуемые ресурсы и лишние файлы в репозитории

**Где и что найдено:**
- **[colors.xml](app/src/main/res/values/colors.xml) (стр. 3–9):** все 7 шаблонных цветов Android Studio (`purple_200`, `purple_500`, `purple_700`, `teal_200`, `teal_700`, `black`, `white`) не используются.
- **`app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.webp` (10 файлов):** до-API-26 растровые иконки стандартного зелёного робота Android Studio. На Android 17 используются исключительно адаптивные векторные иконки из `mipmap-anydpi/ic_launcher.xml` (включая `<monochrome>` для тематических иконок рабочего стола Android 17).
- **[app/src/main/keepRules/rules.keep](app/src/main/keepRules/rules.keep):** пустой шаблон с закомментированным примером для `WebView` (реальные правила находятся в `app/proguard-rules.pro`).
- **[mockup_preview.html](mockup_preview.html):** HTML-мокап дизайна лежит прямо в корне git-репозитория вместо `docs/designs/`.

**Как исправить.**
Удалить `res/values/colors.xml`, все 5 папок `res/mipmap-*dpi` с `.webp`-роботами и пустой `app/src/main/keepRules/rules.keep`; переместить `mockup_preview.html` в `docs/designs/`.

---

## ⚪ MINIMAL: Адаптивность Android 17, R8, тулчейн и тесты

### 23. Адаптивные окна Android 17, Predictive Back, гигиена Compose и спам уведомлений DLB

**Где и что найдено:**
1. **Сброс состояния при ресайзе окна / смене ориентации на Android 17:** в Android 16/17 система принудительно разрешает изменение размера окна, свободный оконный режим (Desktop Windowing) и смену ориентации. Сейчас в [AppNavigation.kt](app/src/main/java/ua/org/electros/app/ui/navigation/AppNavigation.kt) (стр. 63) `var currentTab by remember { mutableStateOf(NavTab.CHARGING) }`, состояния раскрытия карточек (`expanded`) в `DashboardScreen.kt` и черновики ввода в `SettingsScreen.kt` используют `remember` вместо `rememberSaveable` и теряются при любой переконфигурации окна.
2. **Отсутствует `BackHandler` для Predictive Back:** нажатие системного жеста «Назад» на вкладках «Ліміт», «Історія» или «Налаштування» сразу закрывает приложение вместо плавного Predictive Back возврата на вкладку «Заряд».
3. **Мелкие замечания Compose и уведомлений:**
   - [DashboardScreen.kt](app/src/main/java/ua/org/electros/app/ui/dashboard/DashboardScreen.kt) — монолитный файл на **2 095 строк**; на стр. 1433 стоит `mutableStateOf(System.currentTimeMillis())` вместо `mutableLongStateOf(...)` (ежесекундный автобоксинг `Long`) и лишние полные квалификации `androidx.compose.runtime.remember`;
   - [SettingsScreen.kt](app/src/main/java/ua/org/electros/app/ui/settings/SettingsScreen.kt) (стр. 40): параметр `modifier: Modifier = Modifier` должен быть первым опциональным параметром;
   - [ic_nav_settings.xml](app/src/main/res/drawable/ic_nav_settings.xml) (стр. 8): слишком длинный `pathData` (904 символа, предупреждение Lint `[VectorPath]`);
   - [NotificationHelper.kt](app/src/main/java/ua/org/electros/app/service/NotificationHelper.kt) (стр. 211): `val notifId = NOTIFICATION_ID_ALERT + 10 + (System.currentTimeMillis() % 100).toInt()` плодит десятки несхлопывающихся уведомлений в шторке при колебаниях напряжения.

**Как исправить.**
Перевести `currentTab` и UI-флаги на `rememberSaveable`, добавить `BackHandler(enabled = currentTab != NavTab.CHARGING) { currentTab = NavTab.CHARGING }`, заменить `mutableStateOf` → `mutableLongStateOf`, использовать фиксированный `NOTIFICATION_ID_BALANCING` и разбить `DashboardScreen.kt` на отдельные компоненты в `ui/dashboard/components/`.

---

### 24. Hardening релизной сборки R8 и сетевых метаданных

**Где:**
- [app/proguard-rules.pro](app/proguard-rules.pro)
- [ElectrosWebScraper.kt](app/src/main/java/ua/org/electros/app/data/network/ElectrosWebScraper.kt) (стр. 614: `DEFAULT_USER_AGENT`)
- [data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml)

**Что найдено.**
1. В релизной сборке остаются вызовы `Log.i` / `Log.w` / `Log.e` из `OtaUpdater` и `OtaViewModel`.
2. В `ElectrosWebScraper` зашит устаревший `DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"`.
3. В `data_extraction_rules.xml` при переходе с `EncryptedSharedPreferences` на `DataStore` для хранения зашифрованных учётных данных необходимо исключить файл нового `DataStore` учётных данных из `cloud-backup` и `device-transfer` (так как аппаратный ключ `AndroidKeyStore` не переносится на другое устройство).

**Как исправить.**
- Актуализировать `DEFAULT_USER_AGENT` под Android 17 и обновить `data_extraction_rules.xml` под актуальные имена файлов `datastore/`.
- Добавить в `app/proguard-rules.pro` удаление логов в релизе:
```proguard
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
```

---

### 25. Обновление тулчейна под API 37, тестовое покрытие и синхронизация документации

**Где:**
- [app/build.gradle.kts](app/build.gradle.kts) (стр. 55–73), [gradle/libs.versions.toml](gradle/libs.versions.toml)
- `app/src/test/java/ua/org/electros/app/...`
- [README.md](README.md), [docs/designs/electros_ev_client.md](docs/designs/electros_ev_client.md)

**Что найдено.**
1. **Тулчейн сборки:** текущая связка AGP `8.7.3` + Kotlin `2.0.20` (с отключённым `checkTestSources = false` из-за бага раннего K2 UAST) и `composeBom = 2024.09.01` рассчитана на API 35. Для полноценной сборки и lint-проверки под `compileSdk = 37` / `targetSdk = 37` следует обновить AGP, Kotlin `2.1+`/`2.2+`, KSP, Compose BOM, Room `2.7+` и `core-ktx`, убрав костыль `checkTestSources = false` (и при желании подняв JVM target с `17` до `21`).
2. **Пробелы в тестах:** отсутствуют unit-тесты для `HistoryViewModel`, `SettingsViewModel`, `LoginViewModel` и `OtaViewModel`; в `SessionSupervisorTest` не проверяется сохранение `totalKwhDelivered` при офлайн-пакете `0.0 кВт⋅ч`; в `ElectrosWebScraperCommandTest` не моделируется редирект `postForm` на `login.php`.
3. **Рассинхрон документации:** `README.md` и `docs/designs/electros_ev_client.md` описывают удалённый `WorkManager` / `ReconciliationWorker`, отображение напряжения нейтрали (*«neutral line drift»*) и устаревший порог стабилизации `198V-242V` (в коде `180..250 В`), а также не отражают требование **Android 17+ (`API 37`)**.

**Как исправить.**
- Обновить версии плагинов и библиотек в `gradle/libs.versions.toml` под SDK 37 и включить `checkTestSources = true`.
- Добавить регрессионные unit-тесты на потерю среза при `OFFLINE (0.0 кВт⋅ч)`, авто-перелогин в `postForm`, фильтрацию текущего месяца в `HistoryViewModel`, валидацию пароля в `SettingsViewModel` и полный `logout`.
- Актуализировать `README.md` и `docs/designs/electros_ev_client.md` под архитектуру только для Android 17+.

---

# Раунд 2: повторный аудит (после закрытия п. 1–25)

- **Дата:** 2026-11-13, ветка `fix/review-round-2`.
- **Результат проверки раунда 1:** п. 1–13, 16–22, 25 закрыты; п. 14, 15, 23, 24 закрыты частично (см. «Остаточные замечания»). Тесты (103) и lint (0 ошибок) проходили до начала раунда 2.

## Сводная таблица новых находок

| # | Уровень | Файлы | Описание | Статус |
|---|:---:|---|---|---|
| R2-1 | 🔴 Major | `ElectrosWebScraper.kt` | Таймаут WebSocket (`withTimeout(5000)`) пробрасывается как `CancellationException`: HTTP-fallback не вызывается, цикл `ChargeMonitorService` тихо завершается, сервис висит с уведомлением и wake lock | Исправлено |
| R2-2 | 🟠 Medium | `ChargeDao.kt`, `ChargeRepository.kt`, `BootReceiver.kt` | `cancelAllActiveSessions` закрывает сессию без финализации открытого среза → потеря учтённых кВт⋅ч в истории | Исправлено |
| R2-3 | 🟠 Medium | `ChargeRepository.kt` | При `logout` с активной сессией на станции остаётся окно Dead-Man таймера как ежедневное расписание | Исправлено |
| R2-4 | 🟠 Medium | `DashboardScreen.kt` | Индикатор тока справа вверху показывает ток сессии/станции, а не значение ручки, когда зарядка не идёт; при расхождении предпочитает значение сессии вместо телеметрии | Исправлено |
| R2-5 | 🟡 Minor | `NotificationHelper.kt` | Алерты (отключение света, лимит, связь потеряна, возобновление) без `contentIntent` — нажатие ничего не открывает | Исправлено |
| R2-6 | 🟡 Minor | `ElectrosWebScraper.kt` | `catch (e: Exception)` в `queryWebSocket`, `fetchTelemetryHttp`, `postForm` глотает настоящую отмену корутины | Исправлено вместе с R2-1 |

### R2-1. Таймаут WebSocket-команды молча убивает мониторинг

**Где:** [ElectrosWebScraper.kt](app/src/main/java/ua/org/electros/app/data/network/ElectrosWebScraper.kt) — `sendCommandWebSocket` (`catch (e: CancellationException)`), `queryWebSocket`.

**Что найдено.** `TimeoutCancellationException` — подкласс `CancellationException`. Если сокет не открылся за 5 с, блок `catch (e: CancellationException) { throw e }` пробрасывает исключение наружу, HTTP-fallback в `stopCharge`/`startCharge`/`setTimer` не вызывается, а корутина цикла мониторинга отменяется без ошибки. Воспроизведено временным unit-тестом: `stopCharge` бросает `TimeoutCancellationException`, `reachedAfterCall=false`. Последствия: остановка по квоте не повторяется, сервис остаётся висеть, кнопка на Dashboard зависает в состоянии «запуск».

**Как исправить.** Ловить `TimeoutCancellationException` отдельно и превращать в `Result.failure(IOException(...))`; настоящую отмену (`CancellationException`) пробрасывать. В `catch (e: Exception)` добавить `if (e is CancellationException) throw e` (R2-6).

### R2-2. Потеря энергии при `cancelAllActiveSessions`

**Где:** [ChargeDao.kt](app/src/main/java/ua/org/electros/app/data/local/dao/ChargeDao.kt), [ChargeRepository.kt](app/src/main/java/ua/org/electros/app/data/repository/ChargeRepository.kt) (`logout`, `startNewSession`, `attachSessionToOngoingCharge`), [BootReceiver.kt](app/src/main/java/ua/org/electros/app/service/BootReceiver.kt).

**Что найдено.** Запрос просто ставит `status = CANCELLED`, не закрывая открытый срез и не пересчитывая `totalKwhDelivered`. Всё, что набежало в последнем срезе, в истории исчезает.

**Как исправить.** Перед отменой финализировать срез активной сессии (`finalizeActiveSliceAndCommit` с `stationCounterLastKwh`) — вынесено в `ChargeRepository.cancelActiveSessionsPreservingEnergy()` и используется во всех трёх местах.

### R2-3. Расписание станции после `logout`

**Где:** `ChargeRepository.logout`.

**Что найдено.** Пока шла сессия с лимитом, на станции стоит окно Dead-Man таймера. После выхода из аккаунта приложение его не снимает, и станция воспринимает это окно как ежедневное расписание (по коду; на реальной станции не проверялось).

**Как исправить.** Перед очисткой учётных данных, если есть активная сессия с лимитом, вернуть пользовательское расписание (или `00:00–00:00`) через `restoreScheduleTimer` (best-effort).

### R2-4. Индикатор тока справа вверху

**Где:** [DashboardScreen.kt](app/src/main/java/ua/org/electros/app/ui/dashboard/DashboardScreen.kt) — расчёт `actualAmperes`.

**Что найдено.** Требование: показывать актуальный ток (выставлено 6 — «6», 10 — «10», DLB поднял/снизил — новое значение). Два расхождения: (1) без зарядки показывается ток из телеметрии/сессии, а не значение ручки; (2) при расхождении предпочитается `session.amperageSetting`, а не телеметрия станции.

**Как исправить.** Чистая функция `resolveDisplayedAmperes`: при активной зарядке — `telemetry.selectedCurrentAmperes`, иначе — значение ручки. Покрыта unit-тестом.

### R2-5. Уведомления без `contentIntent`

**Где:** `NotificationHelper.showOutageAlert/showTargetReachedAlert/showConnectionLostAlert/showDurationTargetReachedAlert/showOutageResumedAlert`.

**Как исправить.** Добавить `setContentIntent(createOpenAppPendingIntent(...))` с уникальными `requestCode`.

## Остаточные замечания (не исправлялись в раунде 2)

- **DLB поднимает ток выше ручки до `voltageMaxAmperage`** — осознанное поведение (автоподъём при нормальной сети), не дефект. Опционально: подсказка «Auto-ramp up to N A» на карточке DLB.
- **`BootReceiver` не стартует мониторинг после перезагрузки без исключения из оптимизации батареи.** Требует проверки на устройстве с Android 17: ограничение для `specialUse` из `BOOT_COMPLETED` не подтверждено, `try/catch` на `ForegroundServiceStartNotAllowedException` уже есть.
- **Сеть/батарея:** новое WebSocket-соединение с TLS каждые 10 с, wake lock держится часами при отключении света — рассмотреть одно постоянное соединение.
- **Холодный старт:** `runBlocking` в `AuthPreferences.ensureLoaded()` на главном потоке при первой композиции.
- **OTA:** APK не удаляется из кэша после обновления; `file_paths.xml` открывает весь `cacheDir`, а не подпапку.
- **Зависимости:** OkHttp 4.12 → 5.x, Kotlin 2.3.21 → 2.4.21, Gradle 9.6.1 → 9.9.1.
- **Пункты раунда 1 с частичным закрытием:** п. 14 (слайдер макс. тока DLB допускает 14 А, которого нет на ручке), п. 15 (смешение языков, ошибки распознаются по подстрокам, `strings.xml` содержит только `app_name`), п. 23 (`DashboardScreen.kt` — 2122 строки), п. 24 (нет предупреждения о сборке неподписанного release APK).
