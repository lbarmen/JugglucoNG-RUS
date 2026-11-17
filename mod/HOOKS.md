# Врезки в файлы JugglucoNG (MOD-HOOK)

Всё наше — в своих файлах. Если без правки файла автора не обойтись, правка — 1–3 строки с пометкой `MOD-HOOK:<id>`
в коде и строкой здесь. Скрипт вливания сверяет пометки в коде с этой таблицей в обе стороны.

| id | файл | зачем | как вернуть при конфликте |
|----|------|-------|---------------------------|
| carelink-settings | `Common/src/mobile/java/tk/glucodata/ui/ExpressiveSettingsScreen.kt` | раздел обмена данными (`sharing_group`): строка «CareLink» — наш `ModCareLinkEntry(sharingColor)` рисует их `SettingsItem` и открывает окно CareLink; в их сборках без окна в манифесте не рисуется ничего | взять их версию файла и снова вставить одну строку `tk.glucodata.mod.carelink.ModCareLinkEntry(sharingColor) // MOD-HOOK:carelink-settings` в раздел обмена между пунктами, видимыми всегда (после закрывающей `}` блока `if (showLumiflex)` или рядом с соседним пунктом), не внутри чужого `if`; если раздел переименовали — туда, где их пункт Nightscout; `sharingColor` — их локальная `val` раздела |
| carelink-tempbasal | `Common/src/mobile/java/tk/glucodata/data/journal/JournalTreatmentUploader.kt` | функция `isSendableType`: базал помпы из CareLink (записи `TEMP_BASAL` с нашим источником `HEALTH_CONNECT`) уходит в Nightscout как `Temp Basal`; записи AAPS и Nightscout зеркальные и по-прежнему не уходят | взять их версию функции и снова дописать в список типов `\|\| type == JournalEntryType.TEMP_BASAL // MOD-HOOK:carelink-tempbasal`; если функцию переименовали или убрали — найти, где отправка отбирает типы записей, и дописать туда; проверка — контрактный тест `CareLinkJournalContractTest` |

Тип сборки `mod` подключается init-скриптом (`mod/mod-init.gradle`), ресурсы — своими папками `Common/src/mod/` и
`Common/src/wearMod/`: это не врезки.

Проверка — `python3 mod/check-hooks.py`: пометки и таблица в обе стороны, бюджет — не больше 15 их файлов и 60 строк
(ТЗ 3.3 п. 5), и каждая изменённая строка их файла — не дальше 3 строк от пометки `MOD-HOOK`; их файл удалён, новый
файл вне наших путей, двоичный файл, права файла — красная. Наши пути — `mod/our-paths.txt`. Её же запускает
`check-apk.py` в каждой сборке (пробной и кандидата), так что ветка блока проверяется до телефона.

---
Hooks into upstream files: carelink-settings (CareLink row in settings), carelink-tempbasal (pump basal to Nightscout). Each hook is 1–3 lines marked `MOD-HOOK:<id>` and listed in this table.
