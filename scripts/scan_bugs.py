#!/usr/bin/env python3
"""Scan all Kotlin files for common bug patterns."""
import os, re, json

root = r"C:\GitHub\Lxchat\app\src\main\java"
findings = []

BUG_PATTERNS = [
    # (pattern, severity, description, category)
    (r'!!(?!\s*["\'])', 'HIGH', 'Force unwrap (!!): potential NPE', 'npe'),
    (r'catch\s*\([^)]+\)\s*\{\s*\}', 'HIGH', 'Empty catch block: exception swallowed', 'exception'),
    (r'runCatching\s*\{[^}]*\}\s*\.getOrNull\(\)', 'MEDIUM', 'runCatching.getOrNull(): silent failure', 'exception'),
    (r'GlobalScope', 'HIGH', 'GlobalScope usage: unstructured concurrency', 'concurrency'),
    (r'\.let\s*\{\s*it\s*\}', 'LOW', 'Useless .let { it }: no-op', 'style'),
    (r'Thread\.sleep', 'MEDIUM', 'Thread.sleep: blocks thread', 'concurrency'),
    (r'@Suppress\(["\']UNUSED', 'LOW', 'Suppressed unused warning', 'style'),
    (r'synchronized\s*\(', 'MEDIUM', 'synchronized: consider Mutex for coroutines', 'concurrency'),
    (r'\.forEach\s*\{[^}]*!!', 'HIGH', '!! inside forEach: NPE in loop', 'npe'),
    (r'println\(', 'LOW', 'println: use Log instead', 'logging'),
    (r'System\.out', 'LOW', 'System.out: use Log instead', 'logging'),
    (r'\.execute\(\)\s*$', 'MEDIUM', 'execute() without exception handling', 'exception'),
    (r'Looper\.getMainLooper', 'LOW', 'Direct Looper access', 'concurrency'),
    (r'runBlocking', 'MEDIUM', 'runBlocking: blocks thread', 'concurrency'),
    (r'Channel\(.*\)\s*$', 'LOW', 'Unlimited Channel: potential memory', 'concurrency'),
    (r'\.toString\(\)\s*\.\s*toInt\(\)', 'MEDIUM', 'toString().toInt(): NumberFormatException risk', 'exception'),
    (r'URL\([^)]+\)\.readText\(\)', 'HIGH', 'URL.readText(): blocking IO on main thread risk', 'io'),
    (r'File\([^)]+\)\.readText\(\)(?!.*runCatching)', 'MEDIUM', 'File.readText() without try-catch', 'io'),
]

for dirpath, dirs, files in os.walk(root):
    for f in files:
        if not f.endswith('.kt'):
            continue
        path = os.path.join(dirpath, f)
        rel = os.path.relpath(path, root)
        try:
            lines = open(path, 'r', encoding='utf-8').readlines()
        except:
            continue
        for i, line in enumerate(lines, 1):
            # Skip comment lines
            stripped = line.strip()
            if stripped.startswith('//') or stripped.startswith('*') or stripped.startswith('/*'):
                continue
            for pattern, severity, desc, cat in BUG_PATTERNS:
                if re.search(pattern, line):
                    findings.append({
                        'file': rel.replace('\\', '/'),
                        'line': i,
                        'severity': severity,
                        'category': cat,
                        'description': desc,
                        'code': stripped[:120],
                    })

# Summary
from collections import Counter
by_sev = Counter(f['severity'] for f in findings)
by_cat = Counter(f['category'] for f in findings)
print(f"Total findings: {len(findings)}")
print(f"By severity: {dict(by_sev)}")
print(f"By category: {dict(by_cat)}")
print()

# Show HIGH severity first
high = [f for f in findings if f['severity'] == 'HIGH']
print(f"=== HIGH severity ({len(high)}) ===")
for f in high[:50]:
    print(f"  {f['file']}:{f['line']} [{f['category']}] {f['description']}")
    print(f"    {f['code']}")
if len(high) > 50:
    print(f"  ... and {len(high) - 50} more")

# Save full report
report_path = r"C:\GitHub\Lxchat\scripts\bug-scan-report.json"
with open(report_path, 'w', encoding='utf-8') as out:
    json.dump(findings, out, indent=2, ensure_ascii=False)
print(f"\nFull report saved to: {report_path}")