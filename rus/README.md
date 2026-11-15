# JugglucoNG RUS

**Неофициальная версия [JugglucoNG](https://github.com/ctqvva/JugglucoNG).** Её делает не автор JugglucoNG.
Об ошибках JugglucoNG RUS не пишите авторам JugglucoNG и Juggluco. Обсуждения (Issues) в этом репозитории выключены.

Эта страница — про сборку и устройство. Что это и чем отличается от официального — на
[главной странице](/.github/README.md), изменения по версиям — в [CHANGES.md](CHANGES.md).

**Не медицинское изделие.** Приложение не сертифицировано. Показания и тревоги могут быть неверными или не прийти.
Решения о лечении принимайте по своему глюкометру и вместе с врачом. Используете на свой риск (GPL-3.0, раздел 15–16).

## Что это
Тот же JugglucoNG для Android-телефона и часов Wear OS, плюс небольшие свои изменения. Код автора не переписывается:
всё своё лежит в своих файлах, чтобы обновления JugglucoNG вливались легко.

Таблица отличий от официального — одна, на [главной странице](/.github/README.md): новое отличие дописывается туда.

## Версии
- Версия — тег `rus-<версия JugglucoNG>-<наш номер>` на `main`, например `rus-1.3.2-2`; ставится после проверки
  на телефоне или часах.
- Номер версии в APK — их номер × 100 + наш номер (1.3.2 → 103201, 103202…), у часов + 1 000 000.
- Своя подпись, отпечаток сертификата — [rus-cert.sha256](rus-cert.sha256). Обновления внутри приложения смотрят
  в выпуски этого репозитория; выпуски пока не публикуются, APK остаётся на странице сборки.

## Закрытые библиотеки
В APK телефона лежат 34 закрытые библиотеки производителей (`.so`, ≈ 44 МБ; например, для FreeStyle Libre 3).
Их исходников нет, лицензия у каждой своя, и это не GPL. Как у автора JugglucoNG, в git их нет: сборка скачивает его
опубликованный APK [1.2.3-Alpha](https://github.com/ctqvva/JugglucoNG/releases/tag/1.2.3-Alpha), сверяет хеш
и достаёт из него только нужные файлы (`scripts/restore-build-inputs.sh`, список и хеши — `scripts/dist/build-inputs.json`).
Подробности и разрешение автора — `docs/distribution.md`, раздел «Private JNI inputs». Драйвер Sibionics написан
на Kotlin, и сборка автора не кладёт к нему библиотеки производителя.

## Сборка
Только через свою обёртку — она подключает тип сборки `rus` (`rus/rus-init.gradle`), файлы автора не меняются:

    bash rus/gradlew-rus.sh :Common:assembleMobileRus
    bash rus/gradlew-rus.sh :Common:assembleWearRus

Телефон и часы — отдельными запусками: часам нужен параметр для циферблата, обёртка добавляет его сама.

Сборка и проверки APK — задача GitHub Actions `.github/workflows/rus-build.yml`, проверки — `rus/check-apk.py`.
Перед каждым коммитом — `bash rus/check-secrets.sh`. Врезки в файлы автора (сейчас их нет) — в [HOOKS.md](HOOKS.md).

Вливание выпусков JugglucoNG: каждое утро их последний выпуск проверяет `.github/workflows/rus-sync.yml` (слияние
внутри задачи, отчёт, сборка, их тесты, наши эталоны тревог), вливает чат через PR `sync/<выпуск>` — [SYNC.md](SYNC.md).
В `main` — только через PR с зелёной проверкой `report` (набор правил `rus/ruleset-main.json`). PR только из наших
`.md` (кроме `rus/HOOKS.md` и файлов в `Common/`) проверяется без сборки и тестов, примерно за минуту.

## Авторы и лицензия
- [JugglucoNG](https://github.com/ctqvva/JugglucoNG) — [ctqvva](https://github.com/ctqvva) и участники.
- [Juggluco](https://github.com/j-kaltes/Juggluco) — Jaap Korthals Altes; JugglucoNG вырос из него.
- Спасибо им за открытый код.

Лицензия — GPL-3.0, как у оригинала ([LICENSE.txt](../LICENSE.txt)). Исходники каждого выпуска — тег `rus-…`
в этом репозитории.

---

## English (short)
**Unofficial build of [JugglucoNG](https://github.com/ctqvva/JugglucoNG)**, not made by its author. Do not report
problems of this build to the JugglucoNG or Juggluco authors; Issues are switched off here. **Not a medical device**;
use at your own risk.

This page is about the build. The table of differences from the official app is on the
[front page](/.github/README.md), per-version changes are in [CHANGES.md](CHANGES.md). Version = tag
`rus-<JugglucoNG version>-<our number>`; version code = theirs × 100 + our number (wear + 1 000 000).
The phone APK contains 34 closed-source vendor libraries taken, like upstream does, from the published
JugglucoNG 1.2.3-Alpha APK with hash checks. Build only via `bash rus/gradlew-rus.sh …`; upstream files are not
modified (hooks, if any, are listed in HOOKS.md). Credits: ctqvva (JugglucoNG), Jaap Korthals Altes (Juggluco).
License: GPL-3.0.
