#!/usr/bin/env python3
"""JugglucoNG-RUS: report on a range of upstream code (plan of stage 2, A2; ТЗ 3.5), Markdown in Russian.

  python3 rus/sync-report.py <old> <new> [--ours REF] [--offline] [--out FILE] [--json FILE]

<old>, <new>  upstream points (tags or commits): what main has now and what is going to be merged.
--ours REF    our tree for the conflict forecast and the hooks next to their edits (default: main).
--offline     no GitHub API (releases, CI, Issues, libjuice copy): those sections say so.
--json FILE   short machine summary (flags, counts) for rus/sync-merge.sh and the run page.
Needs git with both points fetched (and upstream tags for the release section). The sections are fixed; an empty
one says «нет». Exit 0 always when the report was written (red/green is decided by the checks, not by the report).
"""
import argparse
import json
import re
import subprocess
import sys
from collections import OrderedDict
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rus_common as rc  # noqa: E402

LIBJUICE = 'Common/src/main/cpp/libjuice'
DRIVER_DIR = 'Common/src/main/java/tk/glucodata/drivers/sibionics/'
SRC_RE = re.compile(r'^Common/src/(main|mobile|wear)/java/(.+)\.(java|kt)$')
IMPORT_RE = re.compile(r'^\s*import\s+(?:static\s+)?([A-Za-z_][\w.]*)(\.\*)?', re.M)
ISSUE_WORDS = re.compile(r'sibionics|alarm|alert|wear|watch|тревог|часы|датчик', re.I)
DEP_LINE = re.compile(r'(implementation|api|ksp|kapt|annotationProcessor|classpath|compileOnly|runtimeOnly)\b'
                      r'|^\s*[\w.-]+\s*=\s*["{]|\bid\s*[(\'"]|version\s*[=(]')
NOT_CAUGHT = ('Эталоны тревог и прогноза не проверяют: показ и звук тревоги (`Notify.java`, `AlarmActivity*`), '
              'расписание тихих часов, работу датчика с настоящими пропусками сигнала Sibionics. Их правки там видны '
              'только флагом риска выше и проверяются на эмуляторе и телефоне (ТЗ 3.6).')


def cat_files(r, paths):
    """{path: text} for paths in tree r, one `git cat-file --batch` call."""
    if not paths:
        return {}
    data = ''.join(f'{r}:{p}\n' for p in paths).encode('utf-8')
    out = subprocess.run(['git', 'cat-file', '--batch'], cwd=rc.ROOT, input=data, capture_output=True,
                         check=True).stdout
    res, pos = {}, 0
    for p in paths:
        nl = out.index(b'\n', pos)
        head = out[pos:nl].decode('utf-8', 'replace').split()
        pos = nl + 1
        if len(head) == 3 and head[1] == 'blob':
            size = int(head[2])
            res[p] = out[pos:pos + size].decode('utf-8', 'replace')
            pos += size + 1
    return res


def driver_deps(r):
    """Classes the Sibionics driver reaches by imports, two levels deep: {path: level}."""
    files = rc.git('ls-tree', '-r', '--name-only', r, '--', 'Common/src').splitlines()
    index = {}
    for f in files:
        m = SRC_RE.match(f)
        if m:
            index.setdefault(m.group(2).replace('/', '.'), []).append(f)
    driver = [f for f in files if f.startswith(DRIVER_DIR) and SRC_RE.match(f)]

    def imports_of(paths):
        found = set()
        for text in cat_files(r, paths).values():
            for name, star in IMPORT_RE.findall(text):
                if not name.startswith('tk.glucodata.') or star:
                    continue
                parts = name.split('.')
                while parts:  # tk.glucodata.Applic.foo -> tk.glucodata.Applic
                    hit = index.get('.'.join(parts))
                    if hit:
                        found.update(hit)
                        break
                    parts.pop()
        return found

    lvl1 = imports_of(driver) - set(driver)
    lvl2 = imports_of(sorted(lvl1)) - lvl1 - set(driver)
    deps = {p: 1 for p in lvl1}
    deps.update({p: 2 for p in lvl2})
    return deps, len(driver)


def permissions(r, manifests):
    out = {}
    for path, text in cat_files(r, manifests).items():
        out[path] = set(re.findall(r'<uses-permission(?:-sdk-23)?\b[^>]*android:name="([^"]+)"', text))
    return out


def our_string_names(r):
    names = set()
    for f in rc.git('ls-tree', '-r', '--name-only', r, '--', 'Common/src/rus', 'Common/src/wearRus').splitlines():
        if re.search(r'/res/values[^/]*/[^/]+\.xml$', f):
            text = rc.show(r, f) or ''
            names.update(re.findall(r'<string\b[^>]*\bname="([^"]+)"', text))
    return names


def md_quote(text, limit=25):
    lines = (text or '').strip().splitlines()
    more = len(lines) - limit
    lines = lines[:limit] + ([f'… ещё {more} строк'] if more > 0 else [])
    return '\n'.join('> ' + ln for ln in lines) if lines else '> (текста нет)'


class Report:
    def __init__(self):
        self.parts = []

    def h(self, title):
        self.parts.append(f'\n## {title}\n')

    def p(self, *lines):
        self.parts.extend(lines)

    def text(self):
        return '\n'.join(self.parts).strip() + '\n'


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('old')
    ap.add_argument('new')
    ap.add_argument('--ours', default='main')
    ap.add_argument('--offline', action='store_true')
    ap.add_argument('--out', type=Path)
    ap.add_argument('--json', type=Path)
    a = ap.parse_args()

    old, new = rc.rev(a.old), rc.rev(a.new)
    ours = rc.rev(a.ours)
    if not old or not new or not ours:
        sys.exit(f'unknown point: old={a.old!r} new={a.new!r} ours={a.ours!r} (fetch upstream first)')
    summary = OrderedDict(old=old, new=new, ours=ours)
    areas = rc.risk_areas()
    R = Report()
    vo, vn = rc.app_version(old), rc.app_version(new)
    summary['version_old'], summary['version_new'] = vo[0], vn[0]
    commits = int(rc.git('rev-list', '--count', f'{old}..{new}').strip())
    summary['commits'] = commits
    R.p(f'# Отчёт вливания: {a.old} → {a.new}', '',
        f'Их код `{old[:9]}` → `{new[:9]}`, наш `{a.ours}` = `{ours[:9]}`, '
        f'{datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC. Коммитов автора в диапазоне: {commits}.')
    summary_at = len(R.parts)  # the short summary goes here at the end
    if old != new and rc.git('merge-base', '--is-ancestor', new, old, ok_codes=(0, 1))[0] == 0:
        R.p('', f'**Сводка:** их `{a.new}` ({vn[0]}) уже внутри `{a.old}` ({vo[0]}): вливать нечего, разделы отчёта '
                'не строятся.')
        summary.update(already_in=True, prs=0, files=0, flags={}, conflicts=[])
        text = R.text()
        if a.out:
            a.out.write_text(text, encoding='utf-8')
        else:
            sys.stdout.write(text)
        if a.json:
            a.json.write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
        return 0

    # 1. version
    R.h('1. Их версия')
    R.p(f'- было: {vo[0]} (код {vo[1]})', f'- стало: {vn[0]} (код {vn[1]})')

    # 2. releases in range
    R.h('2. Их выпуски в диапазоне')
    if a.offline:
        R.p('не проверялось (--offline)')
        summary['releases'] = None
    else:
        try:
            rels = rc.gh_api(f'repos/{rc.UPSTREAM_REPO}/releases?per_page=100')
            inside, nightly, missing = [], 0, []
            for rel in rels:
                tag = rel['tag_name']
                c = rc.rev(tag)
                if not c:
                    if not tag.startswith('nightly-'):
                        missing.append(tag)
                    continue
                in_new = rc.git('merge-base', '--is-ancestor', c, new, ok_codes=(0, 1))[0] == 0
                in_old = rc.git('merge-base', '--is-ancestor', c, old, ok_codes=(0, 1))[0] == 0
                if in_new and not in_old:
                    if tag.startswith('nightly-'):
                        nightly += 1
                    else:
                        inside.append(rel)
            summary['releases'] = [r['tag_name'] for r in inside]
            if not inside:
                R.p('нет')
            for rel in sorted(inside, key=lambda x: x['published_at'] or ''):
                mark = 'пред-выпуск' if rel['prerelease'] else 'выпуск'
                R.p(f'### {rel["tag_name"]} — {mark}, {(rel["published_at"] or "?")[:10]}', md_quote(rel['body']), '')
            if nightly:
                R.p(f'Ночных пред-сборок `nightly-*` в диапазоне: {nightly} (не берём).')
            if missing:
                R.p(f'Меток нет в git (не подтянуты `git fetch upstream --tags`): {", ".join(missing[:10])}.')
        except Exception as e:  # network / API: the report still goes out
            R.p(f'не удалось прочитать API GitHub: {e}')
            summary['releases'] = None

    # 3. PRs
    R.h('3. Их PR (по `--first-parent`)')
    log = rc.git('log', '--first-parent', '--format=%H%x1f%P%x1f%s%x1f%b%x1e', f'{old}..{new}')
    prs, direct = [], 0
    for rec in log.split('\x1e'):
        if not rec.strip():
            continue
        h, parents, subj, body = (rec.strip('\n').split('\x1f') + ['', '', ''])[:4]
        m = re.match(r'Merge pull request #(\d+) from (\S+)', subj)
        if m:
            title = (body.strip().splitlines() or [m.group(2)])[0]
            prs.append(f'- #{m.group(1)} {title}')
        elif len(parents.split()) == 1:
            direct += 1
    summary['prs'] = len(prs)
    R.p(*(prs or ['нет']))
    if direct:
        R.p('', f'Прямых коммитов в их `main` без PR: {direct}.')

    # 4. files by area
    R.h('4. Файлы по областям (флаги риска)')
    names = rc.git('diff', '--no-renames', '--name-status', old, new).splitlines()
    numstat = {}
    for line in rc.git('diff', '--no-renames', '--numstat', old, new).splitlines():
        add, rem, path = line.split('\t', 2)
        numstat[path] = (0 if add == '-' else int(add), 0 if rem == '-' else int(rem))
    changed = OrderedDict()
    for line in names:
        st, path = line.split('\t', 1)
        if not rc.is_ours(path):
            changed[path] = st[0]
    deps, n_driver = driver_deps(new)
    by_area = OrderedDict((ar, []) for ar, _ in areas)
    by_area['датчик (зависимость драйвера)'] = []
    other = []
    for path, st in changed.items():
        hit = rc.areas_of(path, areas)
        if path in deps and 'датчик' not in hit:
            hit.append('датчик (зависимость драйвера)')
        for ar in hit:
            by_area[ar].append((path, st))
        if not hit:
            other.append(path)
    flags = OrderedDict((ar, len(v)) for ar, v in by_area.items() if v)
    summary['flags'] = flags
    summary['files'] = len(changed)
    if not flags:
        R.p('флагов нет')
    for ar, items in by_area.items():
        if not items:
            continue
        plus = sum(numstat.get(p, (0, 0))[0] for p, _ in items)
        minus = sum(numstat.get(p, (0, 0))[1] for p, _ in items)
        R.p(f'**⚑ {ar}** — {len(items)} файл(ов), +{plus} −{minus}')
        for p, st in items[:30]:
            ad, rm = numstat.get(p, (0, 0))
            R.p(f'- `{st}` {p} (+{ad} −{rm})')
        if len(items) > 30:
            R.p(f'- … ещё {len(items) - 30}')
        R.p('')
    dirs = OrderedDict()
    for p in other:
        parts = p.split('/')[:-1]
        d = '/'.join(parts[:4]) + '/' if parts else '(корень)'
        dirs[d] = dirs.get(d, 0) + 1
    R.p(f'Вне областей риска: {len(other)} файл(ов)' + (':' if other else '.'))
    for d, n in sorted(dirs.items(), key=lambda x: -x[1])[:12]:
        R.p(f'- {d} — {n}')

    # 5. driver deps
    R.h('5. Классы, от которых зависит драйвер Sibionics')
    lvl1 = sum(1 for v in deps.values() if v == 1)
    R.p(f'По импортам {n_driver} файлов драйвера на два уровня: {lvl1} напрямую, {len(deps) - lvl1} через них '
        f'(в коде `{a.new}`).')
    touched = [p for p in changed if p in deps]
    R.p(*([f'- изменён: {p} (уровень {deps[p]})' for p in touched] or ['- из них в диапазоне не менялись']))

    # 6. conflicts and hooks
    R.h('6. Прогноз конфликтов и наши врезки рядом')
    code, out = rc.git('merge-tree', '--write-tree', '--name-only', '--no-messages', ours, new, ok_codes=(0, 1))
    conflicts = [ln for ln in out.splitlines()[1:] if ln.strip()] if code == 1 else []
    summary['conflicts'] = conflicts
    R.p(f'`git merge-tree {a.ours} {a.new}`: ' + ('конфликтов нет' if not conflicts else
                                                  f'конфликты в {len(conflicts)} файл(ах):'))
    R.p(*[f'- {c}' for c in conflicts])
    hooked = []
    ours_files = set(rc.git('ls-tree', '-r', '--name-only', ours).splitlines())
    texts = cat_files(ours, [p for p in changed if p in ours_files and not rc.is_ours(p)])
    for p, t in texts.items():
        ids = sorted(set(rc.HOOK_RE.findall(t)))
        if ids:
            hooked.append(f'- {p}: {", ".join(ids)}')
    summary['hooks_touched'] = len(hooked)
    R.p('', 'Их правки в файлах с нашими врезками: ' + ('нет' if not hooked else ''), *hooked)

    # 7. permissions
    R.h('7. Разрешения в манифестах')
    mans = sorted({p for p in rc.git('ls-tree', '-r', '--name-only', old).splitlines() +
                   rc.git('ls-tree', '-r', '--name-only', new).splitlines()
                   if p.endswith('AndroidManifest.xml') and not rc.is_ours(p)})
    po, pn = permissions(old, mans), permissions(new, mans)
    perm_lines = []
    for m in mans:
        add = sorted(pn.get(m, set()) - po.get(m, set()))
        rem = sorted(po.get(m, set()) - pn.get(m, set()))
        perm_lines += [f'- **новое** {x} — {m}' for x in add] + [f'- убрано {x} — {m}' for x in rem]
    summary['permissions_added'] = sum(1 for ln in perm_lines if '**новое**' in ln)
    R.p(*(perm_lines or ['нет']))

    # 8. schemas
    R.h('8. Новые схемы базы данных (`Common/schemas`)')
    schemas = rc.git('diff', '--no-renames', '--name-only', '--diff-filter=A', old, new, '--',
                     'Common/schemas').splitlines()
    summary['schemas_added'] = schemas
    R.p(*([f'- {s}' for s in schemas] or ['нет']))

    # 9. libraries
    R.h('9. Библиотеки и плагины сборки')
    dif = rc.git('diff', '-U0', '--no-color', old, new, '--', '*.gradle', '*.gradle.kts', 'gradle/libs.versions.toml')
    lib_lines, cur = [], None
    for ln in dif.splitlines():
        if ln.startswith('+++ '):
            cur = ln[6:]
        elif ln[:1] in '+-' and not ln.startswith('---') and DEP_LINE.search(ln[1:]):
            lib_lines.append(f'- `{cur}`: `{ln[:160]}`')
    summary['library_lines'] = len(lib_lines)
    R.p(*(lib_lines[:40] or ['нет']))
    if len(lib_lines) > 40:
        R.p(f'- … ещё {len(lib_lines) - 40} строк')

    # 10. their workflows
    R.h('10. Их задачи GitHub (`.github/workflows`)')
    wf = [ln.split('\t', 1) for ln in rc.git('diff', '--no-renames', '--name-status', old, new, '--',
                                               '.github/workflows').splitlines()]
    wf = [(st, p) for st, p in wf if not rc.is_ours(p)]
    summary['workflows_new'] = [p for st, p in wf if st == 'A']
    for st, p in wf:
        R.p(f'- {"**новая** — после вливания в `main` включится сама, чат вливания её выключает" if st == "A" else "изменена" if st == "M" else "удалена"}: {p}')
    if not wf:
        R.p('нет')

    # 11. libjuice
    R.h('11. Подмодуль libjuice')
    def sub(r):
        o = rc.git('ls-tree', r, LIBJUICE).split()
        return o[2] if len(o) >= 3 else None
    so, sn = sub(old), sub(new)
    if so == sn:
        R.p(f'не менялся (`{(sn or "?")[:9]}`)')
    else:
        R.p(f'**изменён**: `{(so or "?")[:9]}` → `{(sn or "?")[:9]}`')
    if a.offline or not sn:
        R.p('наша копия `lbarmen/libjuice`: не проверялась' + (' (--offline)' if a.offline else ''))
        summary['libjuice_ok'] = None
    else:
        st = rc.gh_status(f'repos/lbarmen/libjuice/commits/{sn}')
        summary['libjuice_ok'] = st == 200
        R.p('наша копия `lbarmen/libjuice`: ' + (
            'нужный коммит есть' if st == 200 else
            '**нужного коммита нет** — чат вливания: `gh repo sync lbarmen/libjuice`' if st in (404, 422) else
            f'не удалось проверить (ответ {st})'))

    # 12. strings we override
    R.h('12. Строки, которые мы перекрыли, а автор изменил (ТЗ 3.3 п. 6)')
    ours_names = our_string_names(ours)
    str_lines = []
    files = [p for p in changed if re.search(r'/res/values[^/]*/strings[^/]*\.xml$', p)]
    for p in files:
        for ln in rc.git('diff', '-U0', '--no-color', old, new, '--', p).splitlines():
            if ln[:1] in '+-' and not ln.startswith(('+++', '---')):
                m = re.search(r'name="([^"]+)"', ln)
                if m and m.group(1) in ours_names:
                    str_lines.append(f'- {p}: `{ln[:160]}`')
    summary['strings_touched'] = len(str_lines)
    R.p(f'Наши перекрытые строки: {", ".join(sorted(ours_names)) or "нет"}.', *(str_lines or ['Автор их не менял.']))

    # 13. their CI
    R.h('13. Их проверка (CI) на этом коммите')
    if a.offline:
        R.p('не проверялось (--offline)')
        summary['upstream_ci'] = None
    else:
        try:
            runs = rc.gh_api(f'repos/{rc.UPSTREAM_REPO}/commits/{new}/check-runs?per_page=100')['check_runs']
            bad = sorted({r['name'] for r in runs if r['conclusion'] not in ('success', 'skipped', 'neutral', None)})
            pending = sum(1 for r in runs if r['status'] != 'completed')
            summary['upstream_ci'] = 'red' if bad else 'none' if not runs else 'pending' if pending else 'green'
            if not runs:
                R.p('проверок на этом коммите нет')
            elif bad:
                R.p(f'**красная у автора**: {", ".join(bad)} — наше красное там же, возможно, не наша вина')
            else:
                R.p(f'зелёная ({len(runs)} проверок' + (f', {pending} ещё идут' if pending else '') + ')')
        except Exception as e:
            R.p(f'не удалось прочитать API GitHub: {e}')
            summary['upstream_ci'] = None

    # 14. issues
    R.h('14. Их новые Issues за 7 дней (sibionics / alarm / wear)')
    if a.offline:
        R.p('не проверялось (--offline)')
    else:
        try:
            since = (datetime.now(timezone.utc) - timedelta(days=7)).strftime('%Y-%m-%dT%H:%M:%SZ')
            items = rc.gh_api(f'repos/{rc.UPSTREAM_REPO}/issues?state=all&since={since}&per_page=100')
            hits = [i for i in items if 'pull_request' not in i and i['created_at'] >= since and
                    ISSUE_WORDS.search(i['title'] + ' ' + (i.get('body') or ''))]
            summary['issues'] = len(hits)
            R.p(*([f'- [#{i["number"]} {i["title"]}]({i["html_url"]}) — {i["state"]}' for i in hits] or ['нет']))
        except Exception as e:
            R.p(f'не удалось прочитать API GitHub: {e}')

    R.h('Чего эталоны не ловят')
    R.p(NOT_CAUGHT)
    R.h('Разбор (чат вливания)')
    R.p('- сверить новые функции с `rus/CHANGES.md` и открытыми строками `неудобства-jugglucong.md` (ТЗ 3.4);',
        '- для тебя — 3–5 строк «что поменялось»: датчик и тревоги первыми.')

    flag_txt = ', '.join(f'{k} {v}' for k, v in flags.items()) or 'нет'
    R.parts[summary_at:summary_at] = [
        '', f'**Сводка:** {vo[0]} → {vn[0]}; PR {len(prs)}; файлов автора {len(changed)}; флаги: {flag_txt}; '
        f'конфликтов {len(conflicts)}; новых задач GitHub {len(summary["workflows_new"])}; '
        f'новых разрешений {summary["permissions_added"]}; новых схем БД {len(schemas)}.']
    text = R.text()
    if a.out:
        a.out.write_text(text, encoding='utf-8')
    else:
        sys.stdout.write(text)
    if a.json:
        a.json.write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')
    return 0


if __name__ == '__main__':
    sys.exit(main())
