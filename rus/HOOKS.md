# Врезки в файлы JugglucoNG (RUS-HOOK)

Всё наше — в своих файлах. Если без правки файла автора не обойтись, правка — 1–3 строки с пометкой `RUS-HOOK:<id>`
в коде и строкой здесь. Скрипт вливания сверяет пометки в коде с этой таблицей в обе стороны.

| id | файл | зачем | как вернуть при конфликте |
|----|------|-------|---------------------------|
| carelink-tempbasal | `Common/src/mobile/java/tk/glucodata/data/journal/JournalTreatmentUploader.kt` | функция `isSendableType`: базал помпы из CareLink (записи `TEMP_BASAL` с нашим источником `HEALTH_CONNECT`) уходит в Nightscout как `Temp Basal`; записи AAPS и Nightscout зеркальные и по-прежнему не уходят | взять их версию функции и снова дописать в список типов `\|\| type == JournalEntryType.TEMP_BASAL // RUS-HOOK:carelink-tempbasal`; если функцию переименовали или убрали — найти, где отправка отбирает типы записей, и дописать туда; проверка — контрактный тест `CareLinkJournalContractTest` |

Тип сборки `rus` подключается init-скриптом (`rus/rus-init.gradle`), ресурсы — своими папками `Common/src/rus/` и
`Common/src/wearRus/`: это не врезки.

Проверка — `python3 rus/check-hooks.py`: пометки и таблица в обе стороны, бюджет — не больше 15 их файлов и 60 строк
(ТЗ 3.3 п. 5), и каждая изменённая строка их файла — не дальше 3 строк от пометки `RUS-HOOK`; их файл удалён, новый
файл вне наших путей, двоичный файл, права файла — красная. Наши пути — `rus/our-paths.txt`. Её же запускает
`check-apk.py` в каждой сборке (пробной и кандидата), так что ветка блока проверяется до телефона.

---
Hooks into upstream files: carelink-tempbasal (pump basal to Nightscout). Each hook is 1–3 lines marked `RUS-HOOK:<id>` and listed in this table.
