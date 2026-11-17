# JugglucoNGmod

**Неофициальная версия [JugglucoNG](https://github.com/ctqvva/JugglucoNG)** для Android-телефона и часов Wear OS.
Её делает не автор JugglucoNG. Об ошибках этой версии не пишите авторам JugglucoNG и Juggluco.

> [!WARNING]
> **Не медицинское изделие.** Приложение не сертифицировано. Показания и тревоги могут быть неверными или не прийти.
> Решения о лечении принимайте по своему глюкометру и вместе с врачом. Используете на свой риск
> (GPL-3.0, разделы 15–16).

## Что это
Тот же JugglucoNG плюс небольшие свои изменения. Код автора не переписывается: всё своё лежит в своих файлах
(папка [`mod/`](/mod) и ресурсы типа сборки `mod`), чтобы обновления JugglucoNG вливались легко. В файлах автора
сейчас не изменено ни строки ([реестр врезок](/mod/HOOKS.md)).

Основа — JugglucoNG 1.3.2-Alpha. Последняя версия — `rus-1.3.2-2` от 15.11.2026.
Что умеет само приложение — в [README автора](/README.md).

## Чем отличается от официального

| Что | JugglucoNG 1.3.2-Alpha | JugglucoNGmod | С версии |
|---|---|---|---|
| Циферблат на часах Wear OS | не добавляется: «String resource ID #0x0» | добавляется и работает (проверено на часах 15.11.2026) | 1.3.2-2 |
| Установка | — | своё имя пакета `tk.glucodata.ng.mod`: ставится рядом с официальным, не поверх | 1.3.2-2 |
| Название и значок | JugglucoNG | «JugglucoNGmod» на 16 языках, значок автора с другим цветом фона, циферблат «JugglucoNGmod» | 1.3.2-2 |
| Подпись | ключ автора | свой ключ, отпечаток — [`mod/mod-cert.sha256`](/mod/mod-cert.sha256) | 1.3.2-2 |
| Обновление внутри приложения | — | из выпусков этого репозитория (пока не публикуются) | 1.3.2-2 |
| Режим Clone, сервис BloodControl (для AiDEX) | есть | не работают: им нужны секреты автора JugglucoNG | 1.3.2-2 |

Два приложения на одном датчике мешают друг другу: подключайте датчик только к одному.

Строка попадает в таблицу, когда изменение проверено на телефоне или часах и влито в `main`.
Все изменения по версиям и с датами, включая сборку и проверки, — [mod/CHANGES.md](/mod/CHANGES.md).

## Скачать
Готовых выпусков пока нет. Обсуждения (Issues) в этом репозитории выключены.

## Подробнее
- [mod/CHANGES.md](/mod/CHANGES.md) — изменения по версиям.
- [mod/README.md](/mod/README.md) — сборка, номер версии, закрытые библиотеки производителей в APK.
- [mod/HOOKS.md](/mod/HOOKS.md) — правки в файлах автора (сейчас их нет).

## Авторы и лицензия
- [JugglucoNG](https://github.com/ctqvva/JugglucoNG) — [ctqvva](https://github.com/ctqvva) и участники.
- [Juggluco](https://github.com/j-kaltes/Juggluco) — Jaap Korthals Altes; JugglucoNG вырос из него.
- Спасибо им за открытый код.

Лицензия — GPL-3.0, как у оригинала ([LICENSE.txt](/LICENSE.txt)). Исходники каждой версии — тег `mod-…`
в этом репозитории.

---

## English (short)
**Unofficial build of [JugglucoNG](https://github.com/ctqvva/JugglucoNG)** for Android phones and Wear OS, not made by
its author. Do not report problems of this build to the JugglucoNG or Juggluco authors. **Not a medical device**;
use at your own risk.

It is JugglucoNG plus a few changes of our own, all kept in our own files (`mod/`, build type `mod`); no line of the
upstream files is modified. Based on JugglucoNG 1.3.2-Alpha, latest version 1.3.2-2 (2026-11-15, built under the former name: formerly JugglucoNG-RUS, package tk.glucodata.ng.rus).

| What | JugglucoNG 1.3.2-Alpha | JugglucoNGmod | Since |
|---|---|---|---|
| Wear OS watch face | cannot be added: "String resource ID #0x0" | works (checked on a watch, 2026-11-15) | 1.3.2-2 |
| Install | — | own package `tk.glucodata.ng.mod`, installs next to the official app | 1.3.2-2 |
| Name and icon | JugglucoNG | "JugglucoNGmod", upstream icon on another background, watch face "JugglucoNGmod" | 1.3.2-2 |
| Signing | upstream key | own key, fingerprint in [`mod/mod-cert.sha256`](/mod/mod-cert.sha256) | 1.3.2-2 |
| In-app update | — | from this repository's releases (none published yet) | 1.3.2-2 |
| Clone mode, BloodControl (AiDEX) | present | do not work: they need upstream's secrets | 1.3.2-2 |

Connect a sensor to one of the two apps only. No downloads yet; Issues are switched off here. Per-version change
list: [mod/CHANGES.md](/mod/CHANGES.md); build details and closed-source vendor libraries: [mod/README.md](/mod/README.md).
Credits: ctqvva (JugglucoNG), Jaap Korthals Altes (Juggluco). License: GPL-3.0.
