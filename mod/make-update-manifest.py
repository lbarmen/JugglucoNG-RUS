#!/usr/bin/env python3
"""JugglucoNGmod: update-manifest.json for a GitHub release (read by the in-app updater,
Common/src/mobile/java/tk/glucodata/update/GithubReleaseParser.kt, schema 1).

Upstream's scripts/make-update-manifest.sh does not fit: it writes package tk.glucodata.ng and
upstream's version code. Here everything comes from the phone APK itself (aapt2 badging):
package, versionCode, versionName, minSdk. Only the phone APK goes in: the updater is phone-only
and a wear APK must never be offered to a phone install. "file" = the release asset name.

Usage (repository root, ANDROID_HOME set):
  python3 mod/make-update-manifest.py --phone dist/JugglucoNGmod-1.3.2-1.apk > dist/update-manifest.json
"""
import argparse
import glob
import hashlib
import json
import os
import re
import subprocess
import sys

PACKAGE = 'tk.glucodata.ng.mod'


def aapt2():
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    found = sorted(glob.glob(os.path.join(home or '', 'build-tools', '*', 'aapt2')))
    if not found:
        sys.exit(f'aapt2 not found under {home}/build-tools')
    return found[-1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--phone', required=True, help='phone APK, already under its release asset name')
    a = ap.parse_args()

    out = subprocess.run([aapt2(), 'dump', 'badging', a.phone], check=True,
                         capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
    m = re.search(r"package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'", out)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", out, re.M)
    if not m or not sdk:
        sys.exit('could not read package/version/minSdk from aapt2 badging')
    pkg, code, name = m.group(1), int(m.group(2)), m.group(3)
    if pkg != PACKAGE:
        sys.exit(f'phone APK has package {pkg}, expected {PACKAGE}')

    with open(a.phone, 'rb') as f:
        data = f.read()
    manifest = {
        'schema': 1,
        'versionName': name,
        'versionCode': code,
        'minSdk': int(sdk.group(1)),
        'artifacts': [{
            'applicationId': PACKAGE,
            'file': os.path.basename(a.phone),
            'size': len(data),
            'sha256': hashlib.sha256(data).hexdigest(),
        }],
    }
    json.dump(manifest, sys.stdout, indent=2)
    sys.stdout.write('\n')


if __name__ == '__main__':
    main()
