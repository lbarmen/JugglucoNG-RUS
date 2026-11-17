#!/usr/bin/env python3
"""JugglucoNGmod: monthly summary of Juggluco (j-kaltes/Juggluco) for the sync chat (stage 2 plan, D2; spec 3.4).

JugglucoNG does not merge Juggluco; we do not either. A reason to port something by hand: a fix in the Sibionics
driver, alarms or the watch that JugglucoNG lacks, or a feature the owner needs. Their commit messages are version
numbers only, so the summary goes by the files each commit touched. GitHub API, read only.

  python3 mod/juggluco-summary.py [--days 31] [--out FILE]
"""
import argparse
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mod_common as rc  # noqa: E402

REPO = 'j-kaltes/Juggluco'
AREAS = [
    ('Sibionics sensor', re.compile(r'sibionic|sibi|gs1', re.I)),
    ('alarms', re.compile(r'alarm|notify|alert', re.I)),
    ('watch', re.compile(r'wear|watch', re.I)),
    ('Nightscout', re.compile(r'nightscout|nightpost', re.I)),
    ('native code', re.compile(r'\.(cpp|hpp|c|h)$', re.I)),
]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--days', type=int, default=31)
    ap.add_argument('--out', type=Path)
    a = ap.parse_args()
    since = (datetime.now(timezone.utc) - timedelta(days=a.days)).strftime('%Y-%m-%dT%H:%M:%SZ')
    repo = rc.gh_api(f'repos/{REPO}')
    commits = rc.gh_api(f'repos/{REPO}/commits?sha={repo["default_branch"]}&since={since}&per_page=100')
    lines = [f'# Juggluco summary for {a.days} days (since {since[:10]})', '',
             f'Repository {REPO}, branch `{repo["default_branch"]}`: commits {len(commits)}. '
             'The author\'s commit messages are version numbers, so below are the touched files by area.', '']
    if len(commits) >= 100:  # one page only: older commits of the period are not in the list
        lines += ['**100 commits or more: the summary has only the last 100** — run with a smaller `--days`.', '']
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
        lines.append(f'## {msg} — `{c["sha"][:9]}`, {c["commit"]["committer"]["date"][:10]}, files {len(files)}')
        if not hits:
            lines.append('- no risk areas')
        for area, fs in hits.items():
            shown = ', '.join(f'`{x}`' for x in fs[:8]) + (f' … {len(fs) - 8} more' if len(fs) > 8 else '')
            lines.append(f'- **{area}**: {shown}')
        lines.append('')
    lines += ['## Result', '', ('Touched by area: ' + ', '.join(f'{k} {v}' for k, v in total.items()))
              if total else 'No risk areas touched.', '',
              'The owner decides; a change is worth porting only if a fix in the sensor, alarms or watch '
              'repairs something that JugglucoNG does not have (compare with their code).']
    text = '\n'.join(lines) + '\n'
    if a.out:
        a.out.write_text(text, encoding='utf-8')
    else:
        sys.stdout.write(text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
