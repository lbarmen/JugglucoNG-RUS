#!/usr/bin/env python3
"""JugglucoNG-RUS: monthly summary of Juggluco (j-kaltes/Juggluco) for the sync chat (plan of stage 2, D2; ТЗ 3.4).

JugglucoNG does not merge Juggluco; we do not either. A reason to port something by hand: a fix in the Sibionics
driver, alarms or the watch that JugglucoNG lacks, or a feature the owner needs. Their commit messages are version
numbers only, so the summary goes by the files each commit touched. GitHub API, read only.

  python3 rus/juggluco-summary.py [--days 31] [--out FILE]
"""
import argparse
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rus_common as rc  # noqa: E402

REPO = 'j-kaltes/Juggluco'
AREAS = [
    ('датчик Sibionics', re.compile(r'sibionic|sibi|gs1', re.I)),
    ('тревоги', re.compile(r'alarm|notify|alert', re.I)),
    ('часы', re.compile(r'wear|watch', re.I)),
    ('Nightscout', re.compile(r'nightscout|nightpost', re.I)),
    ('нативный код', re.compile(r'\.(cpp|hpp|c|h)$', re.I)),
]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--days', type=int, default=31)
    ap.add_argument('--out', type=Path)
    a = ap.parse_args()
    since = (datetime.now(timezone.utc) - timedelta(days=a.days)).strftime('%Y-%m-%dT%H:%M:%SZ')
    repo = rc.gh_api(f'repos/{REPO}')
    commits = rc.gh_api(f'repos/{REPO}/commits?sha={repo["default_branch"]}&since={since}&per_page=100')
    lines = [f'# Сводка Juggluco за {a.days} дн. (с {since[:10]})', '',
             f'Репозиторий {REPO}, ветка `{repo["default_branch"]}`: коммитов {len(commits)}. '
             'Сообщения у автора — номера версий, поэтому ниже — затронутые файлы по областям.', '']
    total = {}
    for c in reversed(commits):
        full = rc.gh_api(f'repos/{REPO}/commits/{c["sha"]}')
        files = [f['filename'] for f in full.get('files', [])]
        msg = c['commit']['message'].split('\n')[0][:80]
        hits = {}
        for f in files:
            for area, rx in AREAS:
                if rx.search(f):
                    hits.setdefault(area, []).append(f)
        for k, v in hits.items():
            total[k] = total.get(k, 0) + len(v)
        lines.append(f'## {msg} — `{c["sha"][:9]}`, {c["commit"]["committer"]["date"][:10]}, файлов {len(files)}')
        if not hits:
            lines.append('- областей риска нет')
        for area, fs in hits.items():
            shown = ', '.join(f'`{x}`' for x in fs[:8]) + (f' … ещё {len(fs) - 8}' if len(fs) > 8 else '')
            lines.append(f'- **{area}**: {shown}')
        lines.append('')
    lines += ['## Итог', '', ('Затронуто по областям: ' + ', '.join(f'{k} {v}' for k, v in total.items()))
              if total else 'Области риска не затронуты.', '',
              'Решает владелец; переносить стоит, только если правка в датчике, тревогах или часах '
              'исправляет то, чего нет в JugglucoNG (сверить с их кодом).']
    text = '\n'.join(lines) + '\n'
    if a.out:
        a.out.write_text(text, encoding='utf-8')
    else:
        sys.stdout.write(text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
