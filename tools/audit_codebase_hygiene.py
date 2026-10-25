"""Read-only hygiene inventory. Findings require ownership/reachability review.

This is a lexical inventory, not a proof of termination or a Mat leak detector.
It never deletes resources: catalog/AssetManager/OBJ and reflection references
cannot safely be classified from literal resource names alone.
"""
from pathlib import Path
import argparse
import ast
import json
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java'


def audit():
    files = sorted(JAVA.rglob('*.java'))
    source = {p: p.read_text(encoding='utf-8') for p in files}
    consumers = []
    for base in (ROOT / 'app/src', ROOT / 'tools'):
        for p in base.rglob('*'):
            if p.suffix in ('.java', '.kt', '.xml', '.py', '.ps1', '.json'):
                consumers.append((p, p.read_text(encoding='utf-8-sig', errors='replace')))
    orphan_candidates = []
    loops = []
    native = []
    for path, text in source.items():
        name = path.stem
        if not any(re.search(r'\b' + re.escape(name) + r'\b', body)
                   for owner, body in consumers if owner != path):
            orphan_candidates.append(str(path.relative_to(ROOT)))
        for number, line in enumerate(text.splitlines(), 1):
            if re.search(r'\bwhile\s*\(|\bdo\s*\{', line):
                loops.append({'file': str(path.relative_to(ROOT)), 'line': number,
                              'source': line.strip()})
        allocations = len(re.findall(r'new\s+Mat(?:Of\w+)?\s*\(', text))
        if allocations:
            native.append({'file': str(path.relative_to(ROOT)),
                           'constructor_sites': allocations,
                           'release_calls': len(re.findall(r'\.release\s*\(', text)),
                           'finally_blocks': len(re.findall(r'\bfinally\s*\{', text))})
    legacy = []
    for path, text in source.items():
        for pattern in (r'\borbMetrics\s*\(', r'\bratioGoodMatchCount\b',
                        r'\bgoodMatchRatio\s*=\s*1\.0\b'):
            if re.search(pattern, text):
                legacy.append({'file': str(path.relative_to(ROOT)), 'pattern': pattern})
    python_errors = []
    for path in sorted((ROOT / 'tools').glob('*.py')):
        try:
            ast.parse(path.read_text(encoding='utf-8-sig'), filename=str(path))
        except SyntaxError as exc:
            python_errors.append(str(exc))
    return {'limitations': 'Lexical candidates only; cached/borrowed/transferred Mats need manual review. '
                           'Native allocations also occur through clone/submat/OpenCV return values. '
                           'Loop lines may include comments or emitted C++ program strings.',
            'production_java_files': len(files),
            'host_test_classes': len(list((ROOT / 'app/src/test/java').rglob('*Test.java'))),
            'asset_files': sum(p.is_file() for p in (ROOT / 'app/src/main/assets').rglob('*')),
            'unreferenced_type_candidates': orphan_candidates,
            'legacy_metric_findings': legacy,
            'loop_sites_for_manual_review': loops,
            'native_ownership_inventory': native,
            'python_syntax_errors': python_errors,
            'deletions_performed': []}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path,
                        default=ROOT / 'app/build/tmp/qa-overhaul/hygiene-inventory.json')
    args = parser.parse_args()
    result = audit()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(f"Java files: {result['production_java_files']}; host classes: {result['host_test_classes']}; "
          f"assets: {result['asset_files']}; reviewed loop candidates: {len(result['loop_sites_for_manual_review'])}")
    print(f"Unreferenced type candidates: {len(result['unreferenced_type_candidates'])}; "
          f"obsolete metric findings: {len(result['legacy_metric_findings'])}; "
          f"Python syntax errors: {len(result['python_syntax_errors'])}")
    print(args.output)
    if result['legacy_metric_findings'] or result['python_syntax_errors']:
        raise SystemExit(1)
