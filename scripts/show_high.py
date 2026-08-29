import json
findings = json.load(open(r'C:\GitHub\Lxchat\scripts\bug-scan-report.json', 'r', encoding='utf-8'))
high = [f for f in findings if f['severity'] == 'HIGH']
from collections import defaultdict
by_file = defaultdict(list)
for f in high:
    by_file[f['file']].append(f)
for file, items in sorted(by_file.items()):
    print(f'{file} ({len(items)} issues):')
    for i in items:
        print(f'  L{i["line"]} [{i["category"]}] {i["description"]}')
    print()