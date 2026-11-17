#!/usr/bin/env python3
"""JugglucoNGmod: hooks registry and budget of changes in upstream files (stage 2 plan, A3; spec 3.3 item 5).

  python3 mod/check-hooks.py [--ref REF] [--base UPSTREAM_POINT]

1. Every `MOD-HOOK:<id>` mark in upstream files of REF has a row in mod/HOOKS.md with the same file, and every row
   has its mark (both directions); ids are unique.
2. Budget: upstream files changed in REF against their code in it (default `git merge-base REF upstream/main`, i.e.
   their release that REF holds - not their fresh main), our paths (mod/our-paths.txt) left out: at most 15 files
   and 60 lines (added + removed).
3. Every change in an upstream file is a hook (spec 3.3: a hook is 1-3 lines with a MOD-HOOK mark): each changed piece
   (hunk) adds and removes at most 3 lines, and carries the mark in its own new lines - or, when it only removes
   lines, has the mark on the line right above or below. Their file deleted, a new file outside our paths, a binary
   file, a mode or submodule change -> FAIL: none of them can carry a mark.
Every check prints OK or FAIL with a plain reason; any FAIL -> exit 1. mod/check-apk.py runs this script on every
build (test and candidate), so a block branch is checked before it reaches the phone.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mod_common as rc  # noqa: E402

MAX_FILES = 15
MAX_LINES = 60
HOOK_LINES = 3  # spec 3.3: a hook is 1-3 lines
HUNK_RE = re.compile(r'^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', re.M)
failures = []


def check(cond, msg):
    print(('OK    ' if cond else 'FAIL  ') + msg)
    if not cond:
        failures.append(msg)


def git_text(*args):
    """(exit code, output) of git, split on \\n only: a lone \\r in their files must not break a line."""
    p = subprocess.run(['git', '-c', 'core.quotepath=off', *args], cwd=rc.ROOT, capture_output=True)
    if p.returncode not in (0, 1):
        raise RuntimeError(f'git {" ".join(args)}: exit {p.returncode}: {p.stderr.decode("utf-8", "replace")[:500]}')
    return p.returncode, p.stdout.decode('utf-8', 'replace')


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


def hunk_problems(path, diff, new_lines):
    """Reasons why the changed pieces of one upstream file are not hooks (empty list = all are hooks)."""
    out = []
    marked = {n for n, ln in enumerate(new_lines, 1) if rc.HOOK_RE.search(ln)}
    for b, c, d in HUNK_RE.findall(diff):
        removed, start, added = (1 if b == '' else int(b)), int(c), (1 if d == '' else int(d))
        where = f'line {start}' if added <= 1 else f'lines {start}-{start + added - 1}'
        if added > HOOK_LINES or removed > HOOK_LINES:
            out.append(f'{path}: {where}: {added} line(s) added, {removed} removed in one piece - a hook is 1-'
                       f'{HOOK_LINES} lines (spec 3.3); move the code to our files')
        elif added == 0:  # lines removed after line `start`: the mark right above or below
            if not marked & {start, start + 1}:
                out.append(f'{path}: lines removed after line {start} with no MOD-HOOK mark right above or below')
        elif not marked & set(range(start, start + added)):
            out.append(f'{path}: {where} changed without a MOD-HOOK mark in them - make it a hook (mark + row in '
                       f'mod/HOOKS.md) or move the code to our files')
    return out


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
    _, out = git_text('grep', '-n', '-I', '-E', r'MOD-HOOK:[A-Za-z0-9_.-]+', ref)
    marks = {}  # id -> set(files)
    for line in out.split('\n'):
        if not line:
            continue
        _, path, _, text = line.split(':', 3)
        if rc.is_ours(path):
            continue
        for hid in rc.HOOK_RE.findall(text):
            marks.setdefault(hid, set()).add(path)
    rows = table_rows(rc.show(ref, 'mod/HOOKS.md') or '')
    ids = [r[0] for r in rows]
    dup = sorted({i for i in ids if ids.count(i) > 1})
    check(not dup, 'ids in mod/HOOKS.md are unique' + (f': repeated {", ".join(dup)}' if dup else ''))
    table = dict(rows)
    no_row = sorted(set(marks) - set(table))
    no_mark = sorted(set(table) - set(marks))
    check(not no_row, f'every MOD-HOOK mark in upstream files has a row in mod/HOOKS.md ({len(marks)} marks)' +
          ''.join(f'\n      no row for {h} in {", ".join(sorted(marks[h]))} - add it to the table' for h in no_row))
    check(not no_mark, f'every row of mod/HOOKS.md has its mark in the code ({len(table)} rows)' +
          ''.join(f'\n      row {h} ({table[h]}): no MOD-HOOK:{h} in the code - remove the row or restore the hook'
                  for h in no_mark))
    wrong = sorted(h for h in set(marks) & set(table) if table[h] not in marks[h])
    check(not wrong, 'each hook is in the file its row names' +
          ''.join(f'\n      {h}: row says {table[h]}, mark is in {", ".join(sorted(marks[h]))}' for h in wrong))

    # 2. budget
    files, lines = [], 0
    _, out = git_text('diff', '--no-renames', '--numstat', '-z', base, ref)
    for item in out.split('\0'):
        if not item:
            continue
        add, rem, path = item.split('\t', 2)
        if rc.is_ours(path):
            continue
        files.append(path)
        lines += (0 if add == '-' else int(add)) + (0 if rem == '-' else int(rem))
    msg = (f'budget: {len(files)} upstream file(s) and {lines} line(s) changed against their code {base[:9]} '
           f'(limit {MAX_FILES} files, {MAX_LINES} lines)')
    if len(files) > MAX_FILES or lines > MAX_LINES:
        msg += ' - too much in their files: move code to our files (spec 3.3), files: ' + ', '.join(files[:20])
    check(len(files) <= MAX_FILES and lines <= MAX_LINES, msg)

    # 3. every change in an upstream file is a hook
    bad, seen = [], 0
    _, out = git_text('diff', '--no-renames', '--name-status', '-z', base, ref)
    parts = out.split('\0')
    for st, path in zip(parts[0::2], parts[1::2]):
        if not path or rc.is_ours(path):
            continue
        seen += 1
        if st.startswith('D'):
            bad.append(f'{path}: their file deleted - restore it (a hook changes 1-3 lines, never removes a file)')
            continue
        if st.startswith('A'):
            bad.append(f'{path}: a new file outside our paths - move it to our folders or add its path to '
                       f'mod/our-paths.txt')
            continue
        _, diff = git_text('--literal-pathspecs', 'diff', '--no-renames', '-U0', '--no-color', '--no-ext-diff',
                           '--no-textconv', base, ref, '--', path)
        header = diff.split('\n@@', 1)[0]
        kind = ('submodule' if re.search(r'^index \S+ 160000$', header, re.M) else
                'binary file' if '\nBinary files ' in header else
                'file mode' if '\nold mode ' in header else
                'file type' if not HUNK_RE.search(diff) else '')
        if kind:
            bad.append(f'{path}: {kind} changed - no hook can mark it; use our own files or resource folders')
            continue
        _, text = git_text('--literal-pathspecs', 'show', f'{ref}:{path}')
        bad += hunk_problems(path, diff, text.split('\n'))
    check(not bad, f'every change in upstream files is a MOD-HOOK ({seen} file(s) changed)' +
          ''.join(f'\n      {b}' for b in bad[:30]) + (f'\n      ... and {len(bad) - 30} more' if len(bad) > 30 else ''))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
