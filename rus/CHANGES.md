# Изменения JugglucoNG RUS

Что изменено по сравнению с JugglucoNG, по версиям (GPL-3.0 требует отмечать изменения с датой).
Изменения в коде автора JugglucoNG — 0 файлов, пока не сказано иное (см. HOOKS.md).
Коротко, чем RUS отличается для пользователя, — таблица на [главной странице](/.github/README.md).

Версия — тег `rus-<версия JugglucoNG>-<наш номер>` на `main`; ставится после проверки на телефоне или часах.
В каждой версии два списка: «Для пользователя» — что видно в приложении, «Сборка и проверки» — остальное.
Новая строка пишется в раздел «После …» в том же запросе на слияние (PR), что и само изменение.

## После rus-1.3.2-2 (в `main`, тега ещё нет)
Для пользователя: изменений нет.

Сборка и проверки:
- 2026-11-15 — `rus/apk-keep.py`: 5 последних APK-кандидатов на компьютере владельца (скачивает, сверяет подпись,
  номер версии и хеши).
- 2026-11-15 — инструменты вливания обновлений JugglucoNG: списки `rus/our-paths.txt` и `rus/risk-areas.txt`,
  отчёт о новом коде автора (`rus/sync-report.py`), сверка врезок и бюджет правок в файлах автора — не больше
  15 файлов и 60 строк (`rus/check-hooks.py`), проверка секретов только в наших коммитах (`check-secrets.sh --ours`).
- 2026-11-15 — эталонные прогоны: 13 синтетических серий сахара через код тревог и прогноза автора
  (`Common/src/testMobile/java/rus/`, ожидаемые решения — `rus/golden/`).
- 2026-11-15 — ежедневная проверка последнего выпуска JugglucoNG (`.github/workflows/rus-sync.yml`): слияние внутри
  задачи, отчёт, врезки, бюджет, секреты, сборка, тесты автора и наши эталоны; секретов нет, ничего не отправляет.
- 2026-11-15 — главная страница репозитория `.github/README.md` с таблицей отличий (README автора не тронут);
  этот список разбит по версиям.
- 2026-11-15 — вливание выпуска через PR `sync/<выпуск>` (`rus/sync-merge.sh`, порядок — `rus/SYNC.md`, журнал —
  `rus/SYNC-LOG.md`), сводка Juggluco раз в месяц (`rus/juggluco-summary.py`); кандидат на телефон — только когда
  код автора в `main` ровно его выпуск старше 7 дней; напоминание о блоках на тесте дольше 7 дней
  (`rus-remind.yml`); `main` защищён набором правил (`rus/ruleset-main.json`).
- 2026-11-15 — облегчённая проверка PR только из наших `.md` (кроме `rus/HOOKS.md` и `Common/`): врезки, бюджет
  и секреты — да, сборка и тесты — нет, примерно минута; пробная сборка на такие ветки не запускается.
- 2026-11-15 — правки после проверки критиками: упавший скрипт проверки врезок или секретов больше не даёт
  зелёный `report`; сборка с ключом берёт ровно те коммиты блоков, что видела предпроверка; возраст их выпуска
  считается и от коммита (перенесённый тег); проверка секретов шире (пароли без `=`, решения конфликтов);
  `sync-merge.sh`: `continue` после исправлений, `finish` сверяет `report` и выключает новые задачи автора до
  и после слияния; напоминания честнее (красная проверка, недоступный GitHub).

## rus-1.3.2-2 — 2026-11-15 (на основе JugglucoNG 1.3.2-Alpha)
Первая версия с тегом. Кандидат 1 тега не получил: циферблат на часах падал (вторая ошибка ниже).

Для пользователя:
- 2026-11-14 — тип сборки `rus`: пакет `tk.glucodata.ng.rus`, название «JugglucoNG RUS» на 16 языках, значок
  с другим фоном, циферблат «Juggluco RUS», свой номер версии (103201, часы 1103201), обновления из этого репозитория,
  запасной источник обновлений выключен.
- 2026-11-14 — циферблат часов: в официальном 1.3.2 он падает с «String resource ID #0x0» — названия и значки
  цветов попадают в код как 0 и выбрасываются при сжатии. Часы собираются с настоящими номерами ресурсов
  и прежним способом сжатия ресурсов (`rus/gradlew-rus.sh`: `-Pandroid.nonFinalResIds=false`,
  `-Pandroid.r8.optimizedResourceShrinking=false`), сами ресурсы на всякий случай сохраняет
  `Common/src/wearRus/res/raw/rus_keep.xml`.
- 2026-11-15 — циферблат часов, вторая ошибка (на часах после починки «#0x0»): цвета `complication_*_style.xml`
  создаются по имени класса `ComplicationDrawable`, а прежний способ сжатия ресурсов не сохраняет его конструктор —
  чёрный экран, `NoSuchMethodException`. Своё правило `rus/proguard-rus.pro` и проверка конструкторов в сборке.
  Проверено на часах 15.11.2026.

Сборка и проверки:
- 2026-11-14 — сборка и проверки APK в GitHub Actions (`rus-build.yml`, `rus/check-apk.py`), проверка секретов
  перед коммитом (`rus/check-secrets.sh`).
- 2026-11-15 — сборка-кандидат: `main` + ветки блоков, подпись своим ключом (окружение `rus-signing` с ручным
  подтверждением, только `main`), проверка «ветки не приносят нового кода автора», отпечаток сертификата сверяется
  с `rus/rus-cert.sha256`, `update-manifest.json` (`rus/make-update-manifest.py`). APK — на странице сборки;
  выпуски на GitHub пока не делаются (задание `publish` выключено переменной `RUS_PUBLISH_RELEASES`).

---
English: per-version list of our changes against JugglucoNG; a version is a tag `rus-<JugglucoNG version>-<our number>`.
After rus-1.3.2-2 (in main, not tagged): nothing user-visible; tooling only — keeping the last 5 candidate APKs,
upstream sync tools and daily sync check workflow, release merge through a sync PR, candidate only on an upstream
release 7+ days old, reminder for blocks on test, main protected by a ruleset, golden runs of upstream alarm/forecast code, repository front page
`.github/README.md` with the table of differences. rus-1.3.2-2 (2026-11-15, first tagged version): build type `rus`
(package, name, icon background, version code, update source), wear watch face fixes (resource ids and names kept,
no-arg constructor of ComplicationDrawable kept; checked on a watch), CI build with APK checks, signed candidate
build (main + block branches) with certificate check and update-manifest.json; GitHub releases switched off for now.
