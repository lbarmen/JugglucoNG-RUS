# JugglucoNG RUS

**Неофициальная версия [JugglucoNG](https://github.com/ctqvva/JugglucoNG).** Её делает не автор JugglucoNG.
Об ошибках JugglucoNG RUS пишите сюда, в [Issues этого репозитория](https://github.com/lbarmen/JugglucoNG-RUS/issues),
а не авторам JugglucoNG и Juggluco.

**Не медицинское изделие.** Приложение не сертифицировано. Показания и тревоги могут быть неверными или не прийти.
Решения о лечении принимайте по своему глюкометру и вместе с врачом. Используете на свой риск (GPL-3.0, раздел 15–16).

## Что это
Тот же JugglucoNG для Android-телефона и часов Wear OS, плюс небольшие свои изменения. Код автора не переписывается:
всё своё лежит в своих файлах, чтобы обновления JugglucoNG вливались легко.

Чем отличается от официального:
- Имя пакета `tk.glucodata.ng.rus` и название «JugglucoNG RUS» — ставится рядом с официальным, не поверх.
  Два приложения на одном датчике мешают друг другу: подключайте датчик только к одному.
- Значок — их, с другим цветом фона; циферблат на часах — «Juggluco RUS».
- Своя подпись. Обновления внутри приложения приходят из [выпусков этого репозитория](https://github.com/lbarmen/JugglucoNG-RUS/releases);
  номер версии — их номер × 100 + наш номер выпуска (1.3.2 → 103201, 103202…), у часов + 1 000 000.
- Починка циферблата часов: в официальном 1.3.2 он не добавляется («String resource ID #0x0»).
- Список изменений по выпускам — [CHANGES.md](CHANGES.md).

Что не работает: режим Clone и сервис BloodControl (для AiDEX) — им нужны секреты автора JugglucoNG.

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

## Авторы и лицензия
- [JugglucoNG](https://github.com/ctqvva/JugglucoNG) — [ctqvva](https://github.com/ctqvva) и участники.
- [Juggluco](https://github.com/j-kaltes/Juggluco) — Jaap Korthals Altes; JugglucoNG вырос из него.
- Спасибо им за открытый код.

Лицензия — GPL-3.0, как у оригинала ([LICENSE.txt](../LICENSE.txt)). Исходники каждого выпуска — тег `rus-…`
в этом репозитории.

---

## English (short)
**Unofficial build of [JugglucoNG](https://github.com/ctqvva/JugglucoNG)**, not made by its author. Report problems
here, not to the JugglucoNG or Juggluco authors. **Not a medical device**; use at your own risk.

Differences: package `tk.glucodata.ng.rus` (installs next to the official app), name "JugglucoNG RUS", own signing key,
in-app updates from this repository's releases, fixed wear watch face. Clone mode and BloodControl do not work.
The phone APK contains 34 closed-source vendor libraries taken, like upstream does, from the published
JugglucoNG 1.2.3-Alpha APK with hash checks. Build only via `bash rus/gradlew-rus.sh …`; upstream files are not
modified (hooks, if any, are listed in HOOKS.md). Credits: ctqvva (JugglucoNG), Jaap Korthals Altes (Juggluco).
License: GPL-3.0.
