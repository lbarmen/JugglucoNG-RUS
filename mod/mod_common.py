"""JugglucoNGmod: helpers shared by mod/*.py (git calls, our paths, risk areas, GitHub API read-only)."""
import fnmatch
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM_REPO = 'ctqvva/JugglucoNG'
OUR_REPO = 'lbarmen/JugglucoNGmod'
HOOK_RE = re.compile(r'MOD-HOOK:([A-Za-z0-9_.-]+)')

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')


def git(*args, ok_codes=(0,), cwd=None):
    """stdout of git; raises on an exit code not in ok_codes. Returns (code, out) when ok_codes has more than 0."""
    p = subprocess.run(['git', *args], cwd=cwd or ROOT, capture_output=True, text=True, encoding='utf-8',
                       errors='replace')
    if p.returncode not in ok_codes:
        raise RuntimeError(f'git {" ".join(args)}: exit {p.returncode}: {p.stderr.strip()[:500]}')
    return (p.returncode, p.stdout) if len(ok_codes) > 1 else p.stdout


def rev(r):
    return git('rev-parse', '--verify', '--quiet', f'{r}^{{commit}}').strip()


def show(r, path):
    code, out = git('show', f'{r}:{path}', ok_codes=(0, 128))
    return out if code == 0 else None


def _globs(file):
    out = []
    for line in (ROOT / 'mod' / file).read_text(encoding='utf-8').splitlines():
        line = line.split('#', 1)[0].strip()
        if line:
            out.append(line)
    return out


def our_globs():
    return [g + '*' if g.endswith('/') else g for g in _globs('our-paths.txt')]


def is_ours(path):
    return any(fnmatch.fnmatchcase(path, g) for g in our_globs())


def risk_areas():
    """[(area, glob)] in file order."""
    out = []
    for line in _globs('risk-areas.txt'):
        area, _, glob = line.partition('|')
        out.append((area.strip(), glob.strip()))
    return out


def areas_of(path, areas=None):
    low = path.lower()
    seen = []
    for area, g in areas or risk_areas():
        if fnmatch.fnmatchcase(low, g.lower()) and area not in seen:
            seen.append(area)
    return seen


def app_version(r):
    text = show(r, 'Common/build.gradle') or ''
    name = re.search(r"^\s*appVersionName\s*=\s*'([^']*)'", text, re.M)
    code = re.search(r'^\s*appVersionCode\s*=\s*(\d+)', text, re.M)
    return (name.group(1) if name else '?'), (int(code.group(1)) if code else None)


_token = []


def _default_token():
    """GH_TOKEN / GITHUB_TOKEN, else the local `gh` sign-in (kept in memory only), else none (60 requests/hour)."""
    if not _token:
        t = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
        if not t:
            try:
                t = subprocess.run(['gh', 'auth', 'token'], capture_output=True, text=True,
                                   timeout=20).stdout.strip() or None
            except (OSError, subprocess.SubprocessError):
                t = None
        _token.append(t)
    return _token[0]


def gh_api(path, token=None):
    """GET https://api.github.com/<path> -> parsed JSON. Read-only."""
    token = token or _default_token()
    req = urllib.request.Request(f'https://api.github.com/{path.lstrip("/")}',
                                 headers={'Accept': 'application/vnd.github+json',
                                          'X-GitHub-Api-Version': '2022-11-28',
                                          'User-Agent': 'jugglucong-mod-scripts'})
    if token:
        req.add_header('Authorization', f'Bearer {token}')
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode('utf-8'))


def gh_status(path, token=None):
    """HTTP status of a GET (200, 404, 422 ...), None on a network error."""
    try:
        gh_api(path, token)
        return 200
    except urllib.error.HTTPError as e:
        return e.code
    except (urllib.error.URLError, TimeoutError, OSError):
        return None
