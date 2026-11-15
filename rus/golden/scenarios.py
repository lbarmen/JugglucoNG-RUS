#!/usr/bin/env python3
"""JugglucoNG-RUS: synthetic glucose series for the golden alarm and forecast runs (plan of stage 2, B1).

Synthetic on purpose - no real readings may enter this public repository (ТЗ 3.1). Fixed seed: the same files every
time. Writes rus/golden/scenarios/<name>.csv (minute,value); `--check` only compares and exits 1 on a difference.
The JVM test Common/src/testMobile/java/rus/golden/GoldenAlarmRunTest.kt reads these files.

  python3 rus/golden/scenarios.py [--check]
"""
import argparse
import random
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent / 'scenarios'
MMOL = 18.0182
SEED = 20261115


def ramp(start, end, minutes, step):
    n = max(1, minutes // step)
    return [start + (end - start) * i / n for i in range(n)]


def flat(value, minutes, step):
    return [value] * max(1, minutes // step)


def series(parts, step, gaps=()):
    """[(minute, value)] from consecutive parts; gaps = [(from_minute, length)] with no readings."""
    values = [v for p in parts for v in p]
    out = []
    for i, v in enumerate(values):
        m = i * step
        if any(a <= m < a + n for a, n in gaps):
            continue
        out.append((m, v))
    return out


def scenarios():
    rnd = random.Random(SEED)
    s = {}
    # 1. fast drop to low, 1-minute readings (Sibionics GS1 cadence)
    s['fast_drop_low'] = series([flat(140, 20, 1), ramp(140, 55, 34, 1), flat(55, 30, 1), ramp(55, 110, 40, 1)], 1)
    # 2. slow slide over three hours
    s['slow_slide'] = series([flat(120, 15, 1), ramp(120, 62, 190, 1), flat(62, 30, 1), ramp(62, 100, 30, 1)], 1)
    # 3. plateau right at the low threshold (65 mg/dl)
    s['plateau_at_low'] = series([ramp(90, 66, 25, 1), [65 + (i % 3) - 1 for i in range(60)], ramp(65, 95, 30, 1)], 1)
    # 4. low, rebound above the rearm margin (65 + 10), low again: the alarm repeats
    s['rebound_relow'] = series([ramp(100, 60, 40, 1), flat(60, 15, 1), ramp(60, 82, 20, 1), flat(82, 10, 1),
                                 ramp(82, 58, 25, 1), flat(58, 15, 1), ramp(58, 100, 30, 1)], 1)
    # 5. low, rebound inside the margin (to 72 < 75), low again: one episode - "Запас до повторного срабатывания"
    s['rebound_inside_margin'] = series([ramp(100, 60, 40, 1), flat(60, 15, 1), ramp(60, 72, 12, 1), flat(72, 10, 1),
                                         ramp(72, 59, 15, 1), flat(59, 15, 1), ramp(59, 100, 30, 1)], 1)
    # 6. noise +-0.3 mmol/l around a slow drift down through the threshold
    base = ramp(85, 62, 120, 1) + flat(62, 30, 1) + ramp(62, 90, 30, 1)
    s['noise_near_low'] = series([[round(v + rnd.uniform(-0.3, 0.3) * MMOL) for v in base]], 1)
    # 7. gaps of 15 and 30 minutes during a drop
    s['gaps_drop'] = series([flat(130, 20, 1), ramp(130, 60, 90, 1), flat(60, 30, 1), ramp(60, 110, 40, 1)], 1,
                            gaps=[(40, 15), (85, 30)])
    # 8. rise to high and very high, then back
    s['rise_high'] = series([flat(120, 15, 1), ramp(120, 260, 95, 1), flat(260, 20, 1), ramp(260, 140, 80, 1)], 1)
    # 9. night drop, 5-minute readings (Dexcom/Libre cadence)
    s['night_drop_5min'] = series([flat(130, 30, 5), ramp(130, 60, 240, 5), flat(60, 40, 5), ramp(60, 120, 60, 5)], 5)
    # 10. fast drop, 5-minute readings
    s['fast_drop_5min'] = series([flat(150, 30, 5), ramp(150, 55, 60, 5), flat(55, 30, 5), ramp(55, 120, 60, 5)], 5)
    # 11-12. the same in mmol/l (values in mmol/l, the test runs them with isMmol = true)
    for name in ('fast_drop_low', 'rise_high', 'rebound_relow'):
        s[name + '_mmol'] = [(m, v / MMOL) for m, v in s[name]]
    return s


def render(name, points):
    mmol = name.endswith('_mmol')
    lines = ['minute,value'] + [f'{m},{v:.1f}' if mmol else f'{m},{round(v)}' for m, v in points]
    return '\n'.join(lines) + '\n'


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--check', action='store_true')
    a = ap.parse_args()
    OUT.mkdir(exist_ok=True)
    bad = 0
    all_s = scenarios()
    for name, points in all_s.items():
        text = render(name, points)
        f = OUT / f'{name}.csv'
        if a.check:
            if not f.is_file() or f.read_text(encoding='utf-8') != text:
                print(f'FAIL  {f.name} differs from the generator')
                bad = 1
        else:
            f.write_text(text, encoding='utf-8', newline='\n')
    stale = sorted(p.name for p in OUT.glob('*.csv') if p.stem not in all_s)
    if stale:
        print(f'FAIL  files without a scenario: {", ".join(stale)}')
        bad = 1
    print(('OK    ' if not bad else 'FAIL  ') + f'{len(all_s)} scenarios ' + ('checked' if a.check else 'written'))
    return bad


if __name__ == '__main__':
    sys.exit(main())
