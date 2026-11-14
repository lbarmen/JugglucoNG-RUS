# Врезки в файлы JugglucoNG (RUS-HOOK)

Всё наше — в своих файлах. Если без правки файла автора не обойтись, правка — 1–3 строки с пометкой `RUS-HOOK:<id>`
в коде и строкой здесь. Скрипт вливания сверяет пометки в коде с этой таблицей в обе стороны.

| id | файл | зачем | как вернуть при конфликте |
|----|------|-------|---------------------------|

Сейчас врезок нет: тип сборки `rus` подключается init-скриптом (`rus/rus-init.gradle`), ресурсы — своими папками
`Common/src/rus/` и `Common/src/wearRus/`.

---
Hooks into upstream files: none so far. Each hook is 1–3 lines marked `RUS-HOOK:<id>` and listed in this table.
