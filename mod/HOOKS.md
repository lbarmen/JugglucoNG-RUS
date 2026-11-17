# Hooks into JugglucoNG files (MOD-HOOK)

Everything of ours lives in our own files. If a change in the author's file cannot be avoided, it is 1–3 lines marked
`MOD-HOOK:<id>` in the code and a row here. The merge script compares the marks in the code with this table in both
directions.

| id | file | why | how to revert on conflict |
|----|------|-----|---------------------------|

There are no hooks now: the `mod` build type is attached by an init script (`mod/mod-init.gradle`), resources live in
our own folders `Common/src/mod/` and `Common/src/wearMod/`.

Check: `python3 mod/check-hooks.py`: marks and the table in both directions, a budget of at most 15 of their files and
60 lines (spec 3.3 item 5), and every changed line of their file must be within 3 lines of a `MOD-HOOK` mark; a deleted
file of theirs, a new file outside our paths, a binary file, a file mode change - red. Our paths are in
`mod/our-paths.txt`. `check-apk.py` runs it in every build (test and candidate), so a block branch is checked before it
reaches the phone.
