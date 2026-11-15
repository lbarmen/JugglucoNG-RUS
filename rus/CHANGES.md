# Изменения JugglucoNG RUS

Что изменено по сравнению с JugglucoNG, по выпускам (GPL-3.0 требует отмечать изменения с датой).
Изменения в коде автора JugglucoNG — 0 файлов, пока не сказано иное (см. HOOKS.md).

## rus-1.3.2-1 — готовится (на основе JugglucoNG 1.3.2-Alpha)
- 2026-11-14 — тип сборки `rus`: пакет `tk.glucodata.ng.rus`, название «JugglucoNG RUS» на 16 языках, значок
  с другим фоном, циферблат «Juggluco RUS», свой номер версии (103201, часы 1103201), обновления из этого репозитория,
  запасной источник обновлений выключен.
- 2026-11-14 — циферблат часов: в официальном 1.3.2 он падает с «String resource ID #0x0» — названия и значки
  цветов попадают в код как 0 и выбрасываются при сжатии. Часы собираются с настоящими номерами ресурсов
  и прежним способом сжатия ресурсов (`rus/gradlew-rus.sh`: `-Pandroid.nonFinalResIds=false`,
  `-Pandroid.r8.optimizedResourceShrinking=false`), сами ресурсы на всякий случай сохраняет
  `Common/src/wearRus/res/raw/rus_keep.xml`.
- 2026-11-14 — сборка и проверки APK в GitHub Actions (`rus-build.yml`, `rus/check-apk.py`), проверка секретов
  перед коммитом (`rus/check-secrets.sh`).
- 2026-11-15 — циферблат часов, вторая ошибка (на часах после починки «#0x0»): цвета `complication_*_style.xml`
  создаются по имени класса `ComplicationDrawable`, а прежний способ сжатия ресурсов не сохраняет его конструктор —
  чёрный экран, `NoSuchMethodException`. Своё правило `rus/proguard-rus.pro` и проверка конструкторов в сборке.
- 2026-11-15 — сборка-кандидат: `main` + ветки блоков, подпись своим ключом (окружение `rus-signing` с ручным
  подтверждением, только `main`), проверка «ветки не приносят нового кода автора», отпечаток сертификата сверяется
  с `rus/rus-cert.sha256`, `update-manifest.json` (`rus/make-update-manifest.py`). APK — на странице сборки;
  выпуски на GitHub пока не делаются (задание `publish` выключено переменной `RUS_PUBLISH_RELEASES`).

---
English: per-release list of our changes against JugglucoNG. First release rus-1.3.2-1 (in preparation): build type
`rus` (package, name, icon background, version code, update source), wear watch face fix (keep color names and icons),
CI build with APK checks; signed candidate build (main + block branches) with certificate check and
update-manifest.json; GitHub releases switched off for now.
