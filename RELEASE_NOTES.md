# Antigravity for Android v2.7.0

## Спецификация пакетов релиза

| Файл пакета | Идентификатор приложения | Порт рантайма | Описание |
| :--- | :--- | :--- | :--- |
| **`antigravity.apk`** | `com.agy` | 45157 | Стандартная редакция с оригинальными проверками Google (чистая версия без модификаций кода аутентификации). |
| **`antigravity-region-bypass.apk`** | `com.agy` | 45157 | Редакция с обходом проверки учетных записей (позволяет авторизоваться под аккаунтами, блокируемыми со стороны Google; требует наличия сетевого туннеля/VPN). |
| **`SHA256SUMS.txt`** | — | — | Криптографические контрольные суммы бинарных файлов. |

---

## Изменения и исправления (v2.7.0)

* **Нативный автономный Android Phone Agent (`AgyAccessibilityService`, `AgyNotificationListenerService`, `PhoneAgentHandler`):**
  * Добавлен постоянный системный сервис доступности `AgyAccessibilityService` (`@xml/accessibility_service_config`), обеспечивающий мгновенное чтение семантического дерева UI любых приложений (`ui_tree`), клики по тексту/описанию/`view_id`, ввод текста с отправкой IME Enter, скролл, мультитач-жесты (`tap`, `long_press`, `swipe`), глобальные системные действия (`back`, `home`, `recents`, `notifications`, `quick_settings`, `split_screen`, `lock_screen`) и захват PNG-скриншотов через API Android 11+ (`takeScreenshot` + `HardwareBuffer`) без зависимости от ADB/Shizuku после перезагрузки устройства.
  * Добавлен сервис `AgyNotificationListenerService` для чтения активных уведомлений любых приложений, открытия `contentIntent`, очистки уведомлений, нажатия кнопок действий и отправки мгновенных inline-ответов (`RemoteInput` для SMS, мессенджеров и почты).
  * Добавлен `PhoneAgentHandler` в `SystemGatewayServer` (`ui_status`, `ui_tree`, `ui_action`, `ui_screenshot`, `notifications_list`, `notification_action`, `apps_list`, `app_launch`, `send_intent`, `open_settings`) и разрешения `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES` в `AndroidManifest.xml`.
* **Расширение MCP-сервера `android-device` до 20 инструментов и скилл `android-phone-agent`:**
  * В `agy-mcp-android` (`v1.1.0`) добавлены 7 новых инструментов: `android_agent_status`, `android_ui_tree`, `android_ui_act`, `android_screenshot`, `android_apps`, `android_intent` и `android_notifications` (с автоматическим резервным переключением на Shizuku `uiautomator`/`input`/`screencap`, если Accessibility еще не включен).
  * Добавлен встроенный скилл `android-phone-agent` (`Perceive -> Act -> Verify`) в `modules/core/seed/skills/` и `env/overlay/etc/antigravity/skills/`.
* **Надежные Bionic Python MCP-мосты и разрешение симлинков `pkg`:**
  * В `ShellBridgeInstaller` (`BRIDGE_FORMAT = 6`) мосты `runtime/bin/agy-mcp-android` и `runtime/bin/agy-mcp-browser` переведены на прямой вызов `"$PREFIX/bin/python3" "$PREFIX/bin/agy-mcp-*"`, а в `EnvInstaller` гарантирована установка бита исполнения на `$PREFIX/bin` при каждом старте.
  * В `env/pkg/pkg` добавлено разрешение `readlink -f "$0"` и резервный путь `$PREFIX/libexec/pkg/lib` для корректного вызова `pkg` через симлинк или обертку.

---

## Изменения и исправления (v2.6.0)

* **Core & Web UI v2.35.2:** Обновлены ядро `language_server` и встроенный веб-бандл до официального релиза Antigravity `v2.35.2` (`aarch64`), обновлены сигнатуры `crash_suppressor` и проверены все 8 групп ELF-патчей (`fail_closed: true`).
* **Адаптивное разрешение и полноширинный сайдбар на внешнем экране складных смартфонов (Samsung Galaxy Z Fold 8 Ultra / Foldables):**
  * В `AndroidManifest.xml` включены `android:resizeableActivity="true"`, `android.max_aspect=3.0` и `android.supports_size_changes=true` для нативной развертки на внешнем экране `21:9` (`1080×2520`, `384dp`) без черных полос, а в `MainActivity.onConfigurationChanged` добавлена мгновенная синхронизация viewport/layout при складывании и раскладывании экрана.
  * В `WebSettingsFactory` сохранено четкое 1:1 `dp`-масштабирование в Desktop Mode без мыльного `overview`-зума, а в `web/patch_main.py` и `web_optimizations` режим сайдбара и боковой панели переведен в `RESPONSIVE` с защитой ширины `[256..456px]` и выдвижным оверлеем `min(308px, 86vw)` на экранах `< 768px` (устранено сжатие левого сайдбара до `75px` на закрытом внешнем экране Fold).
* **Восстановление стримов после сна/складывания и защита RPC (`bridge.core.js`):** Портированы `resume-stream-recovery.js` и `online-and-notifications.js` — мгновенное переподключение зависших ConnectRPC-стримов (`40ms` вместо `1000ms`), подавление ложного баннера «No internet» в Android WebView и ограничение стартовых повторов неидемпотентных POST-запросов после готовности UI.
* **Отключение вечного кэширования локальных ассетов (`LocalAssetServer`):** Заголовок `Cache-Control: immutable` заменен на `no-store, no-cache, must-revalidate`, чтобы WebView всегда подхватывал свежие `main.js` и CSS сразу после обновления APK поверх.
* **Защита `ExecHandler` от OOM и зависших процессов:** Добавлены лимит буферов `stdout`/`stderr` (`256 КБ`), немедленное закрытие `stdin`, принудительное завершение `destroyForcibly()` по таймауту и корректное закрытие потоков.
* **Ускорение `pkg` и авто-симлинки OpenJDK:** Добавлена параллельная предзагрузка `.deb`-пакетов (до 4 потоков), исправлено удаление файлов в `pkg remove` и реализовано автоматическое создание симлинков `lib/jvm/java-*-openjdk/bin/*` в `$PREFIX/bin` (и мостов `runtime/bin`) при `pkg install` и `agy-fix-paths`.
* **Кликабельные уведомления и чистая синхронизация тем:** В `NotificationHandler` добавлен `PendingIntent` запуска `MainActivity` по тапу на уведомление, `agy-notify` переведен на приоритетную отправку через локальный `SystemGatewayServer` (работает даже без Shizuku), а `theme_sync` защищен от перезаписи системного режима темы на этапе загрузки.

---

## Изменения и исправления (v2.5.0)

* **Core & Web UI v2.34.0 (`/swarm`, Remote Agents, Voice Mode, Rich Approvals, Clipboard Image Paste, Native Android & Browser MCPs):** Обновлены ядро `language_server` и веб-бандл до `v2.34.0`, добавлены голосовой режим, вставка изображений и скриншотов из буфера обмена/Gboard, нативные MCP-серверы `android-device` и `chrome_devtools`, панель дополнительных клавиш и Share Sheet.
* **Отключение неподдерживаемой десктопной песочницы на Android (Issues #40, #41):** В `web/patch_main.py` отключен флаг `enableTerminalSandbox` для пресетов `Default` и `Auto`, устраняя ошибку `fork/exec .../libloader.so: invalid argument` при первом вызове `run_command`.
* **Исправление выбора папок Downloads (`DocumentsContractHelper`, Issue #41):** Добавлена обработка префиксов `raw:` и виртуальных идентификаторов провайдера `com.android.providers.downloads.documents` (`msd:`, числовые ID).
* **Улучшение мостов Bionic и окружения шелла (PR #36 + Userspace Fixes):** Приоритет `SHELL` переведен на мост `runtime/bin/bash`, в мосты добавлены `GODEBUG=netdns=cgo`, `NODE_EXTRA_CA_CERTS` и `GIT_SSL_CAINFO`, в `pkg` и `agy-fix-paths` добавлено переписывание симлинков Termux (исправление SSL в `python-pip`), а `rish_shizuku.dex` включен в `env/overlay/share/rish/` из коробки.

---

## Изменения и исправления (v2.4.1)

### 1. Поддержка встроенного веб-терминала и спавнера PTY (PR #19)
* **Низкоуровневый бинарный патч PTY-шеллы:** Устранено аварийное завершение терминала из-за отсутствия `/bin/bash` на Android. Реализован модульный бинарный патч с сохранением длины строки (`/bin/bash` ➔ `/bin///sh`, ровно 9 байт), безопасно перенаправляющий запросы спавнера ядра в системный шелл Android без сдвига смещений ELF-секций.
* **Приоритетная иерархия `$SHELL` в супервизоре процесса (`CoreEnvironment`):** Вместо жестко заданного `runtime/bin/bash` выстроена отказоустойчивая цепочка разрешения шелла:
  1. `$PREFIX/bin/bash` (полноценный bash окружения пользователя со всей поддержкой `.bashrc`, автодополнения и истории);
  2. `runtime/bin/bash` (встроенный bridge-рантайм);
  3. `/system/bin/sh` (гарантированный системный шелл Android).
* **Эмуляция терминала и база `terminfo`:** В окружение процесса добавлен экспорт `TERM=xterm-256color` и автоматическое подключение каталога `TERMINFO` из `$PREFIX/share/terminfo`, что предотвращает артефакты и искажения цветов и управляющих последовательностей ANSI в интерактивных консольных программах (`nano`, `htop`, mc).

### 2. Повышение стабильности локальной сборки пакетов (AAPT)
* В этапы сборки ресурсов `modules/builder/02_r_java.sh` и `modules/builder/06_package.sh` добавлен экспорт `export ANDROID_DATA="${ANDROID_DATA:-/data}"`. Это устраняет сбои компилятора `aapt` с кодом `SIGABRT (134)` при сборке APK непосредственно внутри ARM64 Android userspace.

---

## Ранее реализованные ключевые возможности (v2.4.0)

### 1. Безопасность и изоляция данных (Issue #6)
* Из релизных сборок полностью удален атрибут `android:debuggable="true"`.
* Устранена уязвимость, позволявшая локальному пользователю или стороннему приложению через `run-as com.agy` считывать OAuth-токены сессий и внутренние файлы конфигурации.
* Флаг отладки изолирован исключительно в dev-сборке (`com.agy.dev`).

### 2. Системный диалог выбора файлов (Issue #8)
* Устаревший ограниченный интерфейс Storage Access Framework (SAF) заменен на нативный системный Intent-селектор Android.
* Обеспечен прямой доступ к файлам, изображениям, видео и архивам из Галереи, Google Фото и любых сторонних файловых менеджеров.
* Реализовано автоматическое сохранение постоянных разрешений на чтение (`Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION`).

### 3. Совместимость с процессорами архитектуры ARMv8.0-A
* Добавлена библиотека динамической эмуляции атомарных инструкций `liblse_compat.so` и низкоуровневый перехватчик `sigill_handler`.
* Устранены аварийные завершения с кодом `SIGILL: illegal instruction` на устройствах с чипсетами Qualcomm Snapdragon 680/685 (Cortex-A53 / Redmi Note 12).

### 4. Оптимизация авторизации OAuth
* Полностью устранен дисковый опрос (polling с интервалом 80 мс) и создание промежуточных файлов при входе в учетную запись.
* Захват URL авторизации переведен на прямой перехват стандартного потока вывода ядра (stdout) с мгновенной отправкой Intent в системный браузер.

### 5. Аппаратный интерфейс фонарика
* Добавлена консольная утилита управления вспышкой камеры: `torch on` / `torch off`.

### 6. Фоновый планировщик задач (`agy-daemon`)
* Реализован системный микро-демон (<10 МБ RAM, 0% CPU в режиме ожидания) для выполнения отложенных и периодических задач по расписанию (cron, интервалы).
* Добавлена поддержка отслеживания изменений файлов и отправка нативных уведомлений в панель Android через `agy-notify`.

### 7. Диагностика запуска ядра
* В интерфейс ошибки инициализации сервера добавлены действия «Copy Logs» и «Save Logs» для быстрого экспорта журнала сбоя в буфер обмена или файл.

### 8. Модульная структура конвейера сборки
* Исходный код разнесен по изолированным модулям: `modules/patches`, `modules/runner`, `modules/builder`.
* Внедрена аргументная передача параметров компиляторам `@java_sources.txt` и `@class_files.txt`, исключающая ошибку переполнения буфера командной строки ядра Linux (`ARG_MAX` / `E2BIG`).

---

## Обновление и совместимость

Все сборки подписаны постоянным ключом проекта. Обновление устанавливается поверх предыдущих версий 2.x без удаления приложения. Пользовательские проекты в каталоге `Documents`, рабочее окружение и сессии полностью сохраняются.
