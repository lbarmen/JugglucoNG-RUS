#!/usr/bin/env python3
"""JugglucoNG-RUS: checks on built `rus` APKs. Every check prints OK or FAIL; any FAIL -> exit 1.

Run from the repository root after `bash rus/gradlew-rus.sh ... assembleMobileRus assembleWearRus`:
  python3 rus/check-apk.py --phone PATH --wear PATH --release N --mode test|release [--no-vendor]

--mode test     signature must differ from both upstream keys (one-time key of the run)
--mode release  additionally the signature must equal rus/rus-cert.sha256
--no-vendor     skip the vendor library check (emulator x86_64 build has no vendor libraries)
"""
import argparse
import fnmatch
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

PACKAGE = 'tk.glucodata.ng.rus'
APP_NAME = 'JugglucoNG RUS'
WATCHFACE_NAME = 'Juggluco RUS'
# Must match packagingOptions.jniLibs.excludes in upstream Common/build.gradle.
VENDOR_EXCLUDES = ['libdata-handle-lib*.so', 'libnative-algorithm-*.so', 'libnative-algorithm-jni-*.so',
                   'libnative-encrypy-decrypt-v110.so', 'libnative-sensitivity-v110.so', 'libnative-struct2json.so']

failures = []


def ok(msg):
    print(f'OK    {msg}')


def fail(msg):
    print(f'FAIL  {msg}')
    failures.append(msg)


def check(cond, msg):
    (ok if cond else fail)(msg)
    return cond


def run(cmd):
    # errors='replace': dexdump prints raw bytes of dex strings, not always valid UTF-8
    return subprocess.run(cmd, check=True, capture_output=True, text=True, encoding='utf-8', errors='replace').stdout


def build_tool(name):
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    found = sorted(glob.glob(os.path.join(home, 'build-tools', '*', name)))
    if not found:
        sys.exit(f'{name} not found under {home}/build-tools')
    return found[-1]


def badging(apk):
    out = run([build_tool('aapt2'), 'dump', 'badging', apk])
    m = re.search(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", out)
    labels = dict(re.findall(r"^application-label(-[^:]*)?:'([^']*)'", out, re.M))
    return m.group(1), int(m.group(2)), m.group(3), labels


def norm_hex(s):
    return re.sub(r'[^0-9a-f]', '', s.lower())


def apk_cert(apk):
    out = run([build_tool('apksigner'), 'verify', '--print-certs', apk])
    certs = re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', out)
    return {norm_hex(c) for c in certs}


def upstream_certs():
    certs = {}
    certs['upstream production (scripts/dist/production-cert.sha256)'] = norm_hex(
        open('scripts/dist/production-cert.sha256').read())
    out = run(['keytool', '-list', '-v', '-keystore', 'Common/everyone.keystore',
               '-storepass', 'IDontKnow', '-alias', 'thealias'])
    m = re.search(r'SHA256:\s*([0-9A-F:]+)', out)
    certs['upstream everyone.keystore'] = norm_hex(m.group(1))
    return certs


def build_gradle_value(name):
    text = open('Common/build.gradle', encoding='utf-8').read()
    m = re.search(rf"^\s*{name}\s*=\s*'?([^'\s]+)'?", text, re.M)
    return m.group(1)


def buildconfig_version_code(variant_dir):
    """VERSION_CODE from the generated BuildConfig: .java source, or compiled .class / .jar (AGP may emit bytecode)."""
    rx = re.compile(r'VERSION_CODE\s*=\s*(\d+)')
    for f in glob.glob(f'Common/build/generated/**/{variant_dir}/**/BuildConfig.java', recursive=True):
        m = rx.search(open(f, encoding='utf-8').read())
        if m:
            print(f'      BuildConfig: {f}')
            return int(m.group(1))
    targets = glob.glob(f'Common/build/intermediates/**/{variant_dir}/**/tk/glucodata/BuildConfig.class', recursive=True)
    for jar in glob.glob(f'Common/build/intermediates/**/{variant_dir}/**/*.jar', recursive=True):
        try:
            with zipfile.ZipFile(jar) as z:
                if 'tk/glucodata/BuildConfig.class' in z.namelist():
                    targets.append(jar)
        except zipfile.BadZipFile:
            pass
    for t in targets:
        cmd = ['javap', '-constants', '-cp', t, 'tk.glucodata.BuildConfig'] if t.endswith('.jar') \
            else ['javap', '-constants', t]
        m = rx.search(run(cmd))
        if m:
            print(f'      BuildConfig: {t}')
            return int(m.group(1))
    found = [f for f in glob.glob('Common/build/**/BuildConfig*', recursive=True) if variant_dir in f]
    print(f'      BuildConfig not found for {variant_dir}; candidates: {found[:10]}')
    return None


def check_names():
    """For every upstream values* dir that defines app_name, our rus source set defines it too."""
    upstream = set()
    for f in glob.glob('Common/src/*/res/values*/strings.xml'):
        src = f.split('/')[2]
        if src in ('rus', 'mobileRus', 'wearRus'):
            continue
        if re.search(r'name="app_name"', open(f, encoding='utf-8').read()):
            upstream.add(f.split('/')[4])
    ours = set()
    for f in glob.glob('Common/src/rus/res/values*/strings.xml'):
        if re.search(rf'name="app_name">{re.escape(APP_NAME)}<', open(f, encoding='utf-8').read()):
            ours.add(f.split('/')[4])
    missing = sorted(upstream - ours)
    check(not missing and upstream, f'app_name "{APP_NAME}" for all {len(upstream)} upstream values* dirs'
          + (f' (missing: {missing})' if missing else ''))


def check_icon():
    for f in glob.glob('Common/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml'):
        check('@drawable/ic_launcher_background' in open(f, encoding='utf-8').read(),
              f'{f} still uses @drawable/ic_launcher_background (our icon background applies)')
    check(os.path.isfile('Common/src/rus/res/drawable/ic_launcher_background.xml'), 'our icon background exists')


def check_native():
    files = glob.glob('Common/.cxx/**/compile_commands.json', recursive=True)
    if not check(bool(files), f'compile_commands.json found ({len(files)})'):
        return
    for f in files:
        t = open(f, encoding='utf-8').read()
        check('-DAPPSUFFIX' in t and 'quotes(.rus)' in t and '-DRELEASEDIR=1' in t,
              f'native flags -DAPPSUFFIX=.rus and -DRELEASEDIR=1 in {f}')


def check_vendor(apk):
    inputs = json.load(open('scripts/dist/build-inputs.json'))['files']
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        bad = []
        expected = 0
        for item in inputs:
            parts = item['path'].split('/')
            abi, lib = parts[-2], parts[-1]
            entry = f'lib/{abi}/{lib}'
            excluded = any(fnmatch.fnmatch(lib, pat) for pat in VENDOR_EXCLUDES)
            if excluded:
                if entry in names:
                    bad.append(f'{entry} should be excluded')
                continue
            expected += 1
            if entry not in names:
                bad.append(f'{entry} missing')
            elif hashlib.sha256(z.read(entry)).hexdigest() != item['sha256']:
                bad.append(f'{entry} sha256 differs')
    check(not bad, f'vendor libraries in phone APK: {expected} expected, hashes = scripts/dist/build-inputs.json'
          + (f' — {bad}' if bad else ''))


def check_watchface_name(apk):
    out = run([build_tool('aapt2'), 'dump', 'resources', apk])
    m = re.search(r'string/watch_face_name.*?\n\s*\(\)\s*"([^"]*)"', out, re.S)
    check(m and m.group(1) == WATCHFACE_NAME, f'watch face name "{WATCHFACE_NAME}" in wear APK'
          + ('' if m else ' (not found)') + (f' (got "{m.group(1)}")' if m and m.group(1) != WATCHFACE_NAME else ''))


def const_only_resources():
    """Resources the wear sources reach via `const val X = R.type.name`, plus every upstream *_style_name.

    R8 resource shrinking misses these references (upstream 1.3.2 watch face crash: "String resource ID #0x0");
    Common/src/wearRus/res/raw/rus_keep.xml keeps them. Taken from the sources, so a new color fails loudly."""
    res = set()
    for f in glob.glob('Common/src/wear/java/**/*.kt', recursive=True):
        res.update(re.findall(r'const\s+val\s+\w+\s*(?::\s*Int\s*)?=\s*R\.(\w+)\.(\w+)',
                              open(f, encoding='utf-8').read()))
    strings = open('Common/src/wear/res/values/strings.xml', encoding='utf-8').read()
    res.update(('string', n) for n in re.findall(r'name="(\w+_style_name)"', strings))
    return sorted(res)


def check_watchface_resources(apk):
    """Each const-only resource exists in the wear APK and its id is used as a literal in the wear dex code."""
    wanted = const_only_resources()
    if not check(len(wanted) >= 13, f'const-only watch face resources found in sources: {len(wanted)}'):
        return
    out = run([build_tool('aapt2'), 'dump', 'resources', apk])
    ids = {(t, n): int(i, 16) for i, t, n in re.findall(r'resource (0x[0-9a-f]+) (\w+)/(\w+)', out)}
    missing = [f'{t}/{n}' for t, n in wanted if (t, n) not in ids]
    check(not missing, f'wear APK keeps {len(wanted)} watch face resources (rus_keep.xml)'
          + (f' (missing: {missing})' if missing else ''))
    literals = set()
    with zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
        tmp = os.path.join(os.environ.get('RUNNER_TEMP', '/tmp'), 'rus-wear-dex')
        os.makedirs(tmp, exist_ok=True)
        for n in dexes:
            path = z.extract(n, tmp)
            code = run([build_tool('dexdump'), '-d', path])
            literals.update(int(h, 16) for h in re.findall(r'\bconst v\d+, .*// #([0-9a-f]{8})$', code, re.M))
            literals.update(int(h, 16) << 16 for h in re.findall(r'\bconst/high16 v\d+, .*// #([0-9a-f]{4})$', code, re.M))
            os.remove(path)
    unused = [f'{t}/{n}' for t, n in wanted if (t, n) in ids and ids[(t, n)] not in literals]
    check(not missing and not unused, f'wear code uses the real ids of {len(wanted)} watch face resources (not 0)'
          + (f' (not referenced: {unused})' if unused else ''))


def check_upstream_untouched():
    """Only added files compared with the common ancestor with upstream main."""
    try:
        base = run(['git', 'merge-base', 'HEAD', 'upstream/main']).strip()
    except subprocess.CalledProcessError:
        fail('git merge-base HEAD upstream/main failed (fetch upstream first)')
        return
    changed = run(['git', 'diff', '--name-status', '--no-renames', '--diff-filter=a', base, 'HEAD']).split('\n')
    changed = [c for c in changed if c.strip()]
    check(not changed, f'0 changes in upstream files since {base[:7]}' + (f': {changed}' if changed else ''))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--phone', required=True)
    ap.add_argument('--wear', required=True)
    ap.add_argument('--release', type=int, required=True)
    ap.add_argument('--mode', choices=['test', 'release'], required=True)
    ap.add_argument('--no-vendor', action='store_true')
    a = ap.parse_args()

    up_code = int(build_gradle_value('appVersionCode'))
    expect = {'phone': up_code * 100 + a.release, 'wear': 1000000 + up_code * 100 + a.release}
    variant_dirs = {'phone': 'mobileRus', 'wear': 'wearRus'}

    for kind, apk in (('phone', a.phone), ('wear', a.wear)):
        pkg, code, name, labels = badging(apk)
        check(pkg == PACKAGE, f'{kind}: package {pkg}')
        check(code == expect[kind], f'{kind}: versionCode {code} (expected {expect[kind]})')
        check(name.endswith(f'-rus{a.release}'), f'{kind}: versionName {name} ends with -rus{a.release}')
        bc = buildconfig_version_code(variant_dirs[kind])
        check(bc == code, f'{kind}: BuildConfig.VERSION_CODE {bc} = APK {code}')
        wrong = {k or 'default': v for k, v in labels.items() if v != APP_NAME}
        check(labels and not wrong, f'{kind}: app label "{APP_NAME}" in {len(labels)} locales'
              + (f' (other: {wrong})' if wrong else ''))
        certs = apk_cert(apk)
        for what, digest in upstream_certs().items():
            check(digest not in certs, f'{kind}: not signed with {what}')
        if a.mode == 'release':
            ours = norm_hex(open('rus/rus-cert.sha256').read())
            check(certs == {ours}, f'{kind}: signed with our key (rus/rus-cert.sha256)')

    check_names()
    check_icon()
    check_native()
    check_watchface_name(a.wear)
    check_watchface_resources(a.wear)
    if not a.no_vendor:
        check_vendor(a.phone)
    check_upstream_untouched()

    print(f'\n{len(failures)} check(s) failed' if failures else '\nAll checks passed')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
