# Изменения JugglucoNG RUS

Что изменено по сравнению с JugglucoNG, по версиям (GPL-3.0 требует отмечать изменения с датой).
Изменения в коде автора JugglucoNG — 0 файлов, пока не сказано иное (см. HOOKS.md).
Коротко, чем RUS отличается для пользователя, — таблица на [главной странице](/.github/README.md).

Версия — тег `rus-<версия JugglucoNG>-<наш номер>` на `main`; ставится после проверки на телефоне или часах.
В каждой версии два списка: «Для пользователя» — что видно в приложении, «Сборка и проверки» — остальное.
Новая строка пишется в раздел «После …» в том же запросе на слияние (PR), что и само изменение.

## После rus-1.3.2-2 (в `main`, тега ещё нет)
Для пользователя:
- 2026-11-16 — код автора — ровно его выпуск JugglucoNG 1.3.2-Alpha: 47 его невыпущенных изменений (тревоги,
  Nightscout, часы), которые были в rus-1.3.2-2, убраны; вернутся с его следующим выпуском.

Сборка и проверки:
- 2026-11-16 — `main` один раз перестроен на выпуск 1.3.2-Alpha (6d8bb06f6): наши коммиты перенесены поверх, наши
  файлы не изменились, тексты коммитов — без служебных пометок. Прежняя история (и исходники rus-1.3.2-2) — тег
  `rus-old-main-2026-11-16`.
- 2026-11-16 — `rus/check-hooks.py`: каждая правка файла автора — не дальше 3 строк от пометки `RUS-HOOK`; удалённый
  файл автора, новый файл вне наших путей, двоичный файл, права файла — красная. `check-apk.py` вместо «0 правок в
  файлах автора» запускает эту проверку: первая врезка не сделает каждую сборку красной.
- 2026-11-15 — личный список запретов для `rus/check-secrets.sh` (свой адрес Nightscout, серийные номера): файл
  вне репозитория, читает только проверка на компьютере владельца, значения не показываются; записи —
  `rus/secrets-deny.py`. С ним проверяются и тексты наших коммитов и двоичные файлы; найденное по общим шаблонам
  тоже выводится с маской.
- 2026-11-15 — номер кандидата N выше не только тегов, но и всех прошлых кандидатов той же версии (собранных и
  незаконченных) и не повторяется при перезапуске сборки: иначе два разных APK с одной версией
  (`rus/candidate-number.sh`, проверка и перед сборкой с ключом). `rus/apk-keep.py` находит кандидатов по их
  файлам на странице сборки, а о повторе номера пишет FAIL.
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
- 2026-11-15 — `rus/SYNC.md`: что делать при каждом красном случае (до и после отправки PR), `continue`, эталоны
  до конца, команда сводки Juggluco для Windows.

## rus-1.3.2-2 — 2026-11-15 (на основе JugglucoNG 1.3.2-Alpha и 47 его невыпущенных изменений от 14.11)
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
release 7+ days old, candidate number above every earlier candidate too, every change in upstream files next to a
RUS-HOOK mark (checked on every build), personal deny list for the secrets check
(kept outside the repository), reminder for blocks on test, main protected by a ruleset, golden runs of upstream alarm/forecast code, repository front page
`.github/README.md` with the table of differences. rus-1.3.2-2 (2026-11-15, first tagged version): build type `rus`
(package, name, icon background, version code, update source), wear watch face fixes (resource ids and names kept,
no-arg constructor of ComplicationDrawable kept; checked on a watch), CI build with APK checks, signed candidate
build (main + block branches) with certificate check and update-manifest.json; GitHub releases switched off for now.
