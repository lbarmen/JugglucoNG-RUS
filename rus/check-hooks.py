#!/usr/bin/env python3
"""JugglucoNG-RUS: hooks registry and budget of changes in upstream files (plan of stage 2, A3; ТЗ 3.3 п. 5).

  python3 rus/check-hooks.py [--ref REF] [--base UPSTREAM_POINT]

1. Every `RUS-HOOK:<id>` mark in upstream files of REF has a row in rus/HOOKS.md with the same file, and every row
   has its mark (both directions); ids are unique.
2. Budget: upstream files changed in REF against their code in it (default `git merge-base REF upstream/main`, i.e.
   their release that REF holds - not their fresh main), our paths (rus/our-paths.txt) left out: at most 15 files
   and 60 lines (added + removed).
Every check prints OK or FAIL with a plain reason; any FAIL -> exit 1.
"""
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rus_common as rc  # noqa: E402

MAX_FILES = 15
MAX_LINES = 60
failures = []


def check(cond, msg):
    print(('OK    ' if cond else 'FAIL  ') + msg)
    if not cond:
        failures.append(msg)


def table_rows(text):
    """[(id, file)] from the Markdown table in HOOKS.md (header and separator rows skipped)."""
    rows = []
    for line in text.splitlines():
        if not line.startswith('|'):
            continue
        cells = [c.strip().strip('`') for c in line.strip().strip('|').split('|')]
        if len(cells) < 2 or cells[0] in ('id', '') or set(cells[0]) <= set('-: '):
            continue
        rows.append((cells[0], cells[1]))
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--ref', default='HEAD')
    ap.add_argument('--base', help='their code in REF (default: merge-base REF upstream/main)')
    a = ap.parse_args()
    ref = rc.rev(a.ref)
    if not ref:
        sys.exit(f'unknown ref {a.ref}')
    if a.base:
        base = rc.rev(a.base)
    else:
        if not rc.rev('upstream/main'):
            sys.exit('upstream/main is not fetched: git fetch upstream main (or pass --base)')
        base = rc.git('merge-base', ref, 'upstream/main').strip()
    if not base:
        sys.exit(f'unknown base {a.base}')

    # 1. marks in code <-> rows in HOOKS.md
    code, out = rc.git('grep', '-n', '-I', '-E', r'RUS-HOOK:[A-Za-z0-9_.-]+', ref, ok_codes=(0, 1))
    marks = {}  # id -> set(files)
    for line in out.splitlines():
        _, path, _, text = line.split(':', 3)
        if rc.is_ours(path):
            continue
        for hid in rc.HOOK_RE.findall(text):
            marks.setdefault(hid, set()).add(path)
    rows = table_rows(rc.show(ref, 'rus/HOOKS.md') or '')
    ids = [r[0] for r in rows]
    dup = sorted({i for i in ids if ids.count(i) > 1})
    check(not dup, 'ids in rus/HOOKS.md are unique' + (f': repeated {", ".join(dup)}' if dup else ''))
    table = dict(rows)
    no_row = sorted(set(marks) - set(table))
    no_mark = sorted(set(table) - set(marks))
    check(not no_row, f'every RUS-HOOK mark in upstream files has a row in rus/HOOKS.md ({len(marks)} marks)' +
          ''.join(f'\n      no row for {h} in {", ".join(sorted(marks[h]))} - add it to the table' for h in no_row))
    check(not no_mark, f'every row of rus/HOOKS.md has its mark in the code ({len(table)} rows)' +
          ''.join(f'\n      row {h} ({table[h]}): no RUS-HOOK:{h} in the code - remove the row or restore the hook'
                  for h in no_mark))
    wrong = sorted(h for h in set(marks) & set(table) if table[h] not in marks[h])
    check(not wrong, 'each hook is in the file its row names' +
          ''.join(f'\n      {h}: row says {table[h]}, mark is in {", ".join(sorted(marks[h]))}' for h in wrong))

    # 2. budget
    files, lines = [], 0
    for line in rc.git('diff', '--no-renames', '--numstat', base, ref).splitlines():
        add, rem, path = line.split('\t', 2)
        if rc.is_ours(path):
            continue
        files.append(path)
        lines += (0 if add == '-' else int(add)) + (0 if rem == '-' else int(rem))
    msg = (f'budget: {len(files)} upstream file(s) and {lines} line(s) changed against their code {base[:9]} '
           f'(limit {MAX_FILES} files, {MAX_LINES} lines)')
    if len(files) > MAX_FILES or lines > MAX_LINES:
        msg += ' - too much in their files: move code to our files (ТЗ 3.3), files: ' + ', '.join(files[:20])
    check(len(files) <= MAX_FILES and lines <= MAX_LINES, msg)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
