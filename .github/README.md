# JugglucoNG RUS

**Неофициальная версия [JugglucoNG](https://github.com/ctqvva/JugglucoNG)** для Android-телефона и часов Wear OS.
Её делает не автор JugglucoNG. Об ошибках этой версии не пишите авторам JugglucoNG и Juggluco.

> [!WARNING]
> **Не медицинское изделие.** Приложение не сертифицировано. Показания и тревоги могут быть неверными или не прийти.
> Решения о лечении принимайте по своему глюкометру и вместе с врачом. Используете на свой риск
> (GPL-3.0, разделы 15–16).

## Что это
Тот же JugglucoNG плюс небольшие свои изменения. Код автора не переписывается: всё своё лежит в своих файлах
(папка [`rus/`](/rus) и ресурсы типа сборки `rus`), чтобы обновления JugglucoNG вливались легко. В файлах автора
сейчас не изменено ни строки ([реестр врезок](/rus/HOOKS.md)).

Основа — JugglucoNG 1.3.2-Alpha. Последняя версия — `rus-1.3.2-2` от 15.11.2026.
Что умеет само приложение — в [README автора](/README.md).

## Чем отличается от официального

| Что | JugglucoNG 1.3.2-Alpha | JugglucoNG RUS | С версии |
|---|---|---|---|
| Циферблат на часах Wear OS | не добавляется: «String resource ID #0x0» | добавляется и работает (проверено на часах 15.11.2026) | rus-1.3.2-2 |
| Установка | — | своё имя пакета `tk.glucodata.ng.rus`: ставится рядом с официальным, не поверх | rus-1.3.2-2 |
| Название и значок | JugglucoNG | «JugglucoNG RUS» на 16 языках, значок автора с другим цветом фона, циферблат «Juggluco RUS» | rus-1.3.2-2 |
| Подпись | ключ автора | свой ключ, отпечаток — [`rus/rus-cert.sha256`](/rus/rus-cert.sha256) | rus-1.3.2-2 |
| Обновление внутри приложения | — | из выпусков этого репозитория (пока не публикуются) | rus-1.3.2-2 |
| Режим Clone, сервис BloodControl (для AiDEX) | есть | не работают: им нужны секреты автора JugglucoNG | rus-1.3.2-2 |

Два приложения на одном датчике мешают друг другу: подключайте датчик только к одному.

Строка попадает в таблицу, когда изменение проверено на телефоне или часах и влито в `main`.
Все изменения по версиям и с датами, включая сборку и проверки, — [rus/CHANGES.md](/rus/CHANGES.md).

## Скачать
Готовых выпусков пока нет. Обсуждения (Issues) в этом репозитории выключены.

## Подробнее
- [rus/CHANGES.md](/rus/CHANGES.md) — изменения по версиям.
- [rus/README.md](/rus/README.md) — сборка, номер версии, закрытые библиотеки производителей в APK.
- [rus/HOOKS.md](/rus/HOOKS.md) — правки в файлах автора (сейчас их нет).

## Авторы и лицензия
- [JugglucoNG](https://github.com/ctqvva/JugglucoNG) — [ctqvva](https://github.com/ctqvva) и участники.
- [Juggluco](https://github.com/j-kaltes/Juggluco) — Jaap Korthals Altes; JugglucoNG вырос из него.
- Спасибо им за открытый код.

Лицензия — GPL-3.0, как у оригинала ([LICENSE.txt](/LICENSE.txt)). Исходники каждой версии — тег `rus-…`
в этом репозитории.

---

## English (short)
**Unofficial build of [JugglucoNG](https://github.com/ctqvva/JugglucoNG)** for Android phones and Wear OS, not made by
its author. Do not report problems of this build to the JugglucoNG or Juggluco authors. **Not a medical device**;
use at your own risk.

It is JugglucoNG plus a few changes of our own, all kept in our own files (`rus/`, build type `rus`); no line of the
upstream files is modified. Based on JugglucoNG 1.3.2-Alpha, latest version `rus-1.3.2-2` (2026-11-15).

| What | JugglucoNG 1.3.2-Alpha | JugglucoNG RUS | Since |
|---|---|---|---|
| Wear OS watch face | cannot be added: "String resource ID #0x0" | works (checked on a watch, 2026-11-15) | rus-1.3.2-2 |
| Install | — | own package `tk.glucodata.ng.rus`, installs next to the official app | rus-1.3.2-2 |
| Name and icon | JugglucoNG | "JugglucoNG RUS", upstream icon on another background, watch face "Juggluco RUS" | rus-1.3.2-2 |
| Signing | upstream key | own key, fingerprint in [`rus/rus-cert.sha256`](/rus/rus-cert.sha256) | rus-1.3.2-2 |
| In-app update | — | from this repository's releases (none published yet) | rus-1.3.2-2 |
| Clone mode, BloodControl (AiDEX) | present | do not work: they need upstream's secrets | rus-1.3.2-2 |

Connect a sensor to one of the two apps only. No downloads yet; Issues are switched off here. Per-version change
list: [rus/CHANGES.md](/rus/CHANGES.md); build details and closed-source vendor libraries: [rus/README.md](/rus/README.md).
Credits: ctqvva (JugglucoNG), Jaap Korthals Altes (Juggluco). License: GPL-3.0.
