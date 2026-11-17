#!/usr/bin/env python3
"""JugglucoNGmod: the owner's personal deny list for mod/check-secrets.sh (Nightscout address, serial numbers).

The list is a file OUTSIDE this public repository: ../secrets-deny.txt next to it (the owner's private folder, kept
out of the private git too) or the file named by MOD_SECRETS_DENY. Only check-secrets.sh on the owner's computer
reads it; GitHub Actions has no such file. This script never prints a value: input is hidden and typed twice, the
list shows the number, kind, length and date only.

  python -X utf8 mod/secrets-deny.py add         ask for entries (hidden), save each, then check our commits
  python -X utf8 mod/secrets-deny.py list        entries: number, kind, length, date - never the value
  python -X utf8 mod/secrets-deny.py remove N    remove entry N (the number from list)
  python -X utf8 mod/secrets-deny.py check       bash mod/check-secrets.sh --ours with this list

Entry rules (the same in check-secrets.sh): one per line, spaces and tabs trimmed, at least 6 characters, case of
Latin letters ignored; empty lines and lines starting with '#' are skipped. A note "# <kind>, <date>" right above an entry is its kind for list.
Nightscout: only the server name is kept (https://, path, port and anything before '@' dropped).
"""
import getpass
import os
import re
import shutil
import subprocess
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIN_LEN = 6
KINDS = {'1': 'nightscout', '2': 'serial', '3': 'other'}
KIND_RU = {'nightscout': 'Nightscout address', 'serial': 'serial number', 'other': 'other'}  # labels shown to the owner
HEADER = ('# Personal deny list for mod/check-secrets.sh: these must not appear in the public repository.\n'
          '# Not in git, not in chat. One entry per line, a line with # is a note.\n'
          '# Add an entry: python -X utf8 code\\mod\\secrets-deny.py add (the value is not shown).\n')


def deny_path():
    env = os.environ.get('MOD_SECRETS_DENY')
    return Path(env) if env else ROOT.parent / 'secrets-deny.txt'


def read_lines(path):
    """Lines split on \\n only, BOM and \\r dropped: exactly as awk in check-secrets.sh sees them."""
    if not path.is_file():
        return []
    text = path.read_text(encoding='utf-8-sig')
    return [ln.rstrip('\r') for ln in text.split('\n')]


def entries(lines):
    """[(line index, value, kind, date)] in file order; numbering = position + 1, as in check-secrets.sh."""
    out = []
    for i, raw in enumerate(lines):
        v = raw.strip(' \t')
        if not v or v.startswith('#'):
            continue
        kind, day = '?', ''
        if i > 0:
            m = re.match(r'^#\s*(nightscout|serial|other)\b,?\s*(\S*)', lines[i - 1].strip())
            if m:
                kind, day = m.group(1), m.group(2)
        out.append((i, v, kind, day))
    return out


def write_lines(path, lines):
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_text('\n'.join(lines).rstrip('\n') + '\n', encoding='utf-8', newline='\n')
    os.replace(tmp, path)


def normalize(kind, value):
    v = value.strip()
    if kind == 'nightscout':
        v = re.sub(r'^[A-Za-z][A-Za-z0-9+.-]*://', '', v)
        v = re.split(r'[/?#]', v, maxsplit=1)[0]
        v = v.rsplit('@', 1)[-1]
        v = re.sub(r':\d+$', '', v).lower().rstrip('.')
        if '.' not in v:
            return None, 'this does not look like a server address (no dot)'
    elif kind == 'serial':
        v = re.sub(r'\s+', '', v)
    if len(v) < MIN_LEN:
        return None, f'shorter than {MIN_LEN} characters: such an entry would match almost everything'
    if '\t' in v:
        return None, 'the entry contains a tab'
    return v, ''


def ask_kind():
    print('\nWhat to add?  1 - Nightscout address   2 - serial number   3 - other   Enter - finish')
    while True:
        k = input('Number: ').strip()
        if not k:
            return None
        if k in KINDS:
            return KINDS[k]
        print('  type 1, 2, 3 or just Enter')


def cmd_add(path):
    print(f'List: {path}')
    print('The value is not visible while typing (pasting works: Ctrl+V or right mouse button) - enter it twice.')
    added = 0
    while True:
        kind = ask_kind()
        if kind is None:
            break
        first = getpass.getpass(f'{KIND_RU[kind]}: ')
        if not first.strip():
            print('  empty - skipping')
            continue
        second = getpass.getpass('once more: ')
        v1, why = normalize(kind, first)
        v2, _ = normalize(kind, second)
        if v1 is None:
            print(f'  not added: {why}')
            continue
        if v1 != v2:
            print('  not added: the two entries differ')
            continue
        lines = read_lines(path)
        have = entries(lines)
        dup = [n for n, (_, v, _, _) in enumerate(have, 1) if v.lower() == v1.lower()]
        if dup:
            print(f'  already there: entry {dup[0]}')
            continue
        if not lines:
            lines = HEADER.rstrip('\n').split('\n')
        lines += ['', f'# {kind}, {date.today().isoformat()}', v1]
        path.parent.mkdir(parents=True, exist_ok=True)
        write_lines(path, lines)
        added += 1
        print(f'  added: entry {len(have) + 1}, {KIND_RU[kind]}, {len(v1)} characters')
    total = len(entries(read_lines(path)))
    print(f'\nEntries added: {added}; in the list in all: {total}.')
    if total == 0:
        return 0
    return cmd_check(path)


def cmd_list(path):
    have = entries(read_lines(path))
    if not path.is_file():
        print(f'No list: {path}')
        return 1
    print(f'{path}: entries {len(have)} (values are not shown)')
    for n, (_, v, kind, day) in enumerate(have, 1):
        short = '  SHORTER THAN 6 - check-secrets.sh will stop' if len(v.encode('utf-8')) < MIN_LEN else ''
        print(f'  {n}. {KIND_RU.get(kind, "no note")}, {len(v)} characters' + (f', {day}' if day else '') + short)
    return 0


def cmd_remove(path, number):
    lines = read_lines(path)
    have = entries(lines)
    if not number.isdigit() or not 1 <= int(number) <= len(have):
        print(f'No entry {number!r}: the list has {len(have)} (python -X utf8 mod/secrets-deny.py list)')
        return 1
    i, _, kind, _ = have[int(number) - 1]
    drop = {i}
    if kind != '?':
        drop.add(i - 1)  # its note "# <kind>, <date>"
        if i - 2 >= 0 and not lines[i - 2].strip():
            drop.add(i - 2)  # and the empty line add put above it
    write_lines(path, [ln for j, ln in enumerate(lines) if j not in drop])
    print(f'Entry {number} removed; {len(have) - 1} left. The numbers after it moved down by one.')
    return 0


def git_bash():
    for b in (r'C:\Program Files\Git\bin\bash.exe', r'C:\Program Files\Git\usr\bin\bash.exe'):
        if os.name == 'nt' and os.path.isfile(b):
            return b
    found = shutil.which('bash')
    if found and os.name == 'nt' and 'system32' in found.lower():
        return None  # WSL's bash: another system, it would not see this repository the same way
    return found


def cmd_check(path):
    bash = git_bash()
    if not bash:
        print('bash from Git for Windows not found - run the check yourself: bash mod/check-secrets.sh --ours')
        return 1
    print('\nChecking our commits and files in the public repository (values are not printed)...')
    subprocess.run(['git', '-C', str(ROOT), 'fetch', '--quiet', '--no-tags', 'upstream', 'main'], check=False)
    env = dict(os.environ, MOD_SECRETS_DENY=str(path))
    rc = subprocess.run([bash, str(ROOT / 'mod' / 'check-secrets.sh'), '--ours'], env=env).returncode
    print('\nResult: ' + ('no matches.' if rc == 0 else
                         'there are matches - the FAIL lines above: entry number and file. Values are not shown.'))
    return rc


def main():
    args = sys.argv[1:]
    if not args or args[0] not in ('add', 'list', 'remove', 'check') or (args[0] == 'remove') != (len(args) == 2) \
            or len(args) > 2:
        print(__doc__)
        return 2
    path = deny_path()
    if path.resolve().is_relative_to(ROOT.resolve()):
        print(f'The list is inside the public repository ({path}) - not allowed: it would go to GitHub.')
        return 1
    try:
        if args[0] == 'add':
            return cmd_add(path)
        if args[0] == 'list':
            return cmd_list(path)
        if args[0] == 'remove':
            return cmd_remove(path, args[1])
        return cmd_check(path)
    except (KeyboardInterrupt, EOFError):
        print('\nInterrupted; the entries already added are saved.')
        return 1


if __name__ == '__main__':
    sys.exit(main())
