#!/usr/bin/env python3
"""JugglucoNG-RUS: keep the last 5 candidate APKs (phone + wear) on the owner's computer.

Owner's decision 15.11.2026: no backup copy of the repository; instead the APKs of the last 5 candidates stay on the
computer. A candidate is a successful `RUS build` run started by hand with release N > 0; its artifact is named
`rus-candidate-X.Y.Z-N` and lives on the run page for 14 days only.

  python rus/apk-keep.py fetch [--run ID ...]      download every candidate not yet kept (or just these runs),
                                                    check it, then prune (to the Recycle Bin)
  python rus/apk-keep.py list                      kept candidates, newest first
  python rus/apk-keep.py installed rus-X.Y.Z-N     mark the candidate that is on the phone (never pruned)
  python rus/apk-keep.py prune [--delete]          dry run by default; --delete moves the extra ones to the Recycle Bin

--dest DIR   where candidates are kept (default: ../apk next to this repository, i.e. the owner's private folder).
Checks on each download: package, signature = rus/rus-cert.sha256, versionName ends with -rusN, versionCode % 100 = N
(wear: + 1 000 000), file hashes = the run's candidate.txt. Hashes and run data are written to record.txt.
Needs: gh (signed in), Android build-tools (aapt2, apksigner) under ANDROID_HOME or %LOCALAPPDATA%\\Android\\Sdk, Java
(JAVA_HOME, PATH or Android Studio's jbr). Exit 0 - all OK, 1 - a check failed or something could not be done.
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = 'lbarmen/JugglucoNG-RUS'
WORKFLOW = 'rus-build.yml'
PACKAGE = 'tk.glucodata.ng.rus'
KEEP = 5
ART_RE = re.compile(r'^rus-candidate-(\d+\.\d+\.\d+)-(\d+)$')
DIR_RE = re.compile(r'^rus-(\d+\.\d+\.\d+)-(\d+)$')
MARK = 'INSTALLED'
ROOT = Path(__file__).resolve().parent.parent
MAX_DELETE = 1 << 30  # owner's rule: at most 1 GB to the Recycle Bin at a time


def say(msg):
    print(msg, flush=True)


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, encoding='utf-8', errors='replace',
                          **kw).stdout


def gh_api(path):
    return json.loads(run(['gh', 'api', path]))


# ---------- tools ----------
def sdk_home():
    for h in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
              os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')):
        if h and os.path.isdir(os.path.join(h, 'build-tools')):
            return h
    sys.exit('Android build-tools not found (ANDROID_HOME or %LOCALAPPDATA%\\Android\\Sdk)')


def build_tool(name):
    home = sdk_home()
    for n in (name, name + '.exe', name + '.bat'):
        found = sorted(glob.glob(os.path.join(home, 'build-tools', '*', n)))
        if found:
            return found[-1]
    sys.exit(f'{name} not found under {home}\\build-tools')


def java_env():
    env = dict(os.environ)
    if shutil.which('java') or env.get('JAVA_HOME'):
        return env
    for jbr in (r'C:\Program Files\Android\Android Studio\jbr',
                os.path.join(env.get('LOCALAPPDATA', ''), 'Programs', 'Android Studio', 'jbr')):
        if os.path.isfile(os.path.join(jbr, 'bin', 'java.exe')):
            env['JAVA_HOME'] = jbr
            env['PATH'] = os.path.join(jbr, 'bin') + os.pathsep + env.get('PATH', '')
            return env
    sys.exit('Java not found (JAVA_HOME, PATH or Android Studio jbr): apksigner needs it')


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def norm_hex(s):
    return re.sub(r'[^0-9a-f]', '', s.lower())


# ---------- kept candidates ----------
def kept(dest):
    """[(version tuple, N, Path)] newest first."""
    out = []
    if dest.is_dir():
        for d in dest.iterdir():
            m = DIR_RE.match(d.name)
            if d.is_dir() and m:
                out.append((tuple(int(x) for x in m.group(1).split('.')), int(m.group(2)), d))
    return sorted(out, reverse=True)


def candidate_runs(only_ids):
    """[(run id, version, N, artifact)] of successful manual runs with a live candidate artifact."""
    if only_ids:
        runs = [gh_api(f'repos/{REPO}/actions/runs/{i}') for i in only_ids]
    else:
        runs = gh_api(f'repos/{REPO}/actions/workflows/{WORKFLOW}/runs?event=workflow_dispatch&status=success'
                      f'&per_page=100')['workflow_runs']
    out = []
    for r in runs:
        if r.get('conclusion') != 'success' or r.get('event') != 'workflow_dispatch' or \
                not r.get('path', '').endswith(WORKFLOW):
            say(f'skip  run {r["id"]}: not a successful manual {WORKFLOW} run')
            continue
        for a in gh_api(f'repos/{REPO}/actions/runs/{r["id"]}/artifacts?per_page=100')['artifacts']:
            m = ART_RE.match(a['name'])
            if not m:
                continue
            if a['expired']:
                say(f'gone  {a["name"]} (run {r["id"]}): artifact expired on the run page')
                continue
            out.append((r, m.group(1), int(m.group(2)), a))
    return out


def check_candidate(folder, version, n, run_info):
    """Checks on a downloaded candidate; returns (problems, record lines)."""
    problems, rec = [], []
    dist = folder / 'dist'
    phone = dist / f'JugglucoNG-RUS-{version}-{n}.apk'
    wear = dist / f'JugglucoNG-RUS-{version}-{n}-wear.apk'
    ours = norm_hex((ROOT / 'rus' / 'rus-cert.sha256').read_text())
    env = java_env()
    for kind, apk, extra in (('phone', phone, 0), ('wear', wear, 1000000)):
        if not apk.is_file():
            problems.append(f'{kind}: {apk.name} missing')
            continue
        out = run([build_tool('aapt2'), 'dump', 'badging', str(apk)])
        m = re.search(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", out)
        pkg, code, name = m.group(1), int(m.group(2)), m.group(3)
        if pkg != PACKAGE:
            problems.append(f'{kind}: package {pkg}, expected {PACKAGE}')
        if not name.endswith(f'-rus{n}'):
            problems.append(f'{kind}: versionName {name} does not end with -rus{n}')
        if code % 100 != n or (code >= 1000000) != bool(extra):
            problems.append(f'{kind}: versionCode {code} does not fit release {n}')
        certs = set(norm_hex(c) for c in re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]+)',
                                                    run([build_tool('apksigner'), 'verify', '--print-certs',
                                                         str(apk)], env=env)))
        if certs != {ours}:
            problems.append(f'{kind}: signature {sorted(certs)} is not ours ({ours[:8]}...)')
        rec.append(f'{kind}: {apk.name} {pkg} {name} code {code} cert {ours[:8]}...{ours[-4:]}')
    # hashes against the run's own candidate.txt
    listed = {}
    cand = dist / 'candidate.txt'
    if cand.is_file():
        for line in cand.read_text(encoding='utf-8', errors='replace').splitlines():
            mm = re.match(r'^([0-9a-f]{64})\s+\*?(\S+)$', line.strip())
            # candidate.txt lists itself too, hashed while tee was still writing it: not comparable
            if mm and mm.group(2) != 'candidate.txt':
                listed[mm.group(2)] = mm.group(1)
    else:
        problems.append('dist/candidate.txt missing')
    rec.append('sha256:')
    for f in sorted(p for p in folder.rglob('*') if p.is_file() and p.name != 'record.txt'):
        h = sha256(f)
        rel = f.relative_to(folder).as_posix()
        rec.append(f'  {h}  {rel}')
        if f.parent == dist and f.name in listed and listed[f.name] != h:
            problems.append(f'{rel}: sha256 differs from candidate.txt')
    for name in listed:
        if not (dist / name).is_file():
            problems.append(f'dist/{name} listed in candidate.txt but not downloaded')
    head = [f'candidate {version}-{n}',
            f'run:       {run_info["html_url"]}',
            f'commit:    {run_info["head_sha"]} ({run_info["head_branch"]})',
            f'built:     {run_info["created_at"]}',
            f'kept:      {datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")} by rus/apk-keep.py',
            f'checks:    {"OK" if not problems else "FAIL: " + "; ".join(problems)}']
    return problems, head + rec


def fetch(args, dest):
    dest.mkdir(parents=True, exist_ok=True)
    have = {(v, n) for v, n, _ in ((d[0], d[1], d[2]) for d in kept(dest))}
    status = 0
    for r, version, n, art in candidate_runs(args.run):
        vt = tuple(int(x) for x in version.split('.'))
        if (vt, n) in have:
            say(f'have  rus-{version}-{n} (run {r["id"]})')
            continue
        tmp = dest / f'.partial-{version}-{n}'
        if tmp.exists():
            shutil.rmtree(tmp)
        say(f'get   rus-{version}-{n} from run {r["id"]} ({art["size_in_bytes"] // (1 << 20)} MB)...')
        run(['gh', 'run', 'download', str(r['id']), '-R', REPO, '-n', art['name'], '-D', str(tmp)])
        problems, record = check_candidate(tmp, version, n, r)
        (tmp / 'record.txt').write_text('\n'.join(record) + '\n', encoding='utf-8')
        if problems:
            say(f'FAIL  rus-{version}-{n}: ' + '; '.join(problems) + f' (left in {tmp.name} for a look)')
            status = 1
            continue
        tmp.rename(dest / f'rus-{version}-{n}')
        have.add((vt, n))
        say(f'OK    rus-{version}-{n}: signature ours, version -rus{n}, hashes = candidate.txt')
    return max(status, prune(dest, args.keep, delete=True))


def to_prune(dest, keep):
    items = kept(dest)
    return [d for i, (_, _, d) in enumerate(items) if i >= keep and not (d / MARK).exists()]


def dir_size(d):
    return sum(f.stat().st_size for f in d.rglob('*') if f.is_file())


def recycle_bin_room(drive):
    """Free bytes in the Recycle Bin of this drive, or None when unknown / bin switched off."""
    ps = (
        "$ErrorActionPreference='Stop';"
        f"$v=(Get-Volume -DriveLetter '{drive}').Path -replace '^\\\\\\\\\\?\\\\Volume','' -replace '\\\\$','';"
        "$k='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\BitBucket\\Volume\\'+$v;"
        "$p=Get-ItemProperty -LiteralPath $k;"
        "if($p.NukeOnDelete -eq 1){'off'}else{"
        f"$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;"
        f"$b='{drive}:\\$Recycle.Bin\\'+$sid;"
        "$u=(Get-ChildItem -LiteralPath $b -Force -Recurse -File -ErrorAction SilentlyContinue|"
        "Measure-Object Length -Sum).Sum;"
        "[string]([int64]$p.MaxCapacity*1MB-[int64]$u)}"
    )
    try:
        out = run(['powershell', '-NoProfile', '-Command', ps]).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None
    return None if out == 'off' or not re.fullmatch(r'-?\d+', out) else int(out)


def to_recycle_bin(path):
    ps = ("Add-Type -AssemblyName Microsoft.VisualBasic;"
          "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($env:RUS_PRUNE_PATH,'OnlyErrorDialogs',"
          "'SendToRecycleBin')")
    subprocess.run(['powershell', '-NoProfile', '-Command', ps], check=True,
                   env=dict(os.environ, RUS_PRUNE_PATH=str(path)))


def prune(dest, keep, delete):
    extra = to_prune(dest, keep)
    if not extra:
        say(f'prune: nothing to remove ({len(kept(dest))} kept, limit {keep})')
        return 0
    size = sum(dir_size(d) for d in extra)
    names = ', '.join(d.name for d in extra)
    if not delete:
        say(f'prune (dry run): would move to the Recycle Bin {names} ({size // (1 << 20)} MB); --delete to do it')
        return 0
    if os.name != 'nt':
        say(f'prune: not on Windows, not removing {names}')
        return 1
    room = recycle_bin_room(str(dest.resolve())[0].upper())
    if size > MAX_DELETE or room is None or room < size:
        say(f'prune: NOT removed {names} ({size // (1 << 20)} MB): '
            + ('over 1 GB at once' if size > MAX_DELETE else
               'Recycle Bin size unknown or switched off' if room is None else
               f'only {max(room, 0) // (1 << 20)} MB free in the Recycle Bin') + ' - remove them by hand')
        return 1
    for d in extra:
        to_recycle_bin(d.resolve())
        say(f'prune: {d.name} -> Recycle Bin')
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('cmd', choices=['fetch', 'list', 'installed', 'prune'])
    ap.add_argument('name', nargs='?', help='installed: rus-X.Y.Z-N')
    ap.add_argument('--run', type=int, action='append', help='fetch: only this run id (repeatable)')
    ap.add_argument('--dest', type=Path, default=ROOT.parent / 'apk')
    ap.add_argument('--keep', type=int, default=KEEP)
    ap.add_argument('--delete', action='store_true', help='prune: really move to the Recycle Bin')
    a = ap.parse_args()
    dest = a.dest
    if a.cmd == 'fetch':
        return fetch(a, dest)
    if a.cmd == 'prune':
        return prune(dest, a.keep, a.delete)
    if a.cmd == 'installed':
        if not a.name or not (dest / a.name).is_dir() or not DIR_RE.match(a.name):
            sys.exit(f'no kept candidate {a.name!r} in {dest}')
        for _, _, d in kept(dest):
            (d / MARK).unlink(missing_ok=True)
        (dest / a.name / MARK).write_text(datetime.now().strftime('%Y-%m-%d %H:%M') + ' installed on the phone\n',
                                          encoding='utf-8')
        say(f'{a.name}: marked installed (never pruned); mark removed from the others')
        return 0
    for vt, n, d in kept(dest):
        say(f'{d.name}  {dir_size(d) // (1 << 20)} MB' + ('  INSTALLED' if (d / MARK).exists() else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main())
