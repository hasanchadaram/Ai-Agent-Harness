"""Build/validate the fresh v4 corpus. Standard library, no APIs or model calls."""
import argparse
import collections
import hashlib
import json
import random
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SPLITS = ('train', 'validation', 'test')


def normalized(text):
    return ' '.join(unicodedata.normalize('NFKC', text).casefold().split())


def read_cases():
    rows = []
    for source in sorted(ROOT.glob('cases_*.txt')):
        for line_no, line in enumerate(source.read_text(encoding='utf-8-sig').splitlines(), 1):
            if not line.strip() or line.startswith('#'):
                continue
            fields = line.split('|')
            if len(fields) != 5:
                raise ValueError(f'{source.name}:{line_no}: expected five fields')
            split, family, tags, raw, gold = fields
            # Only these authored escapes are decoded; never unicode_escape,
            # which would corrupt rupee signs, paths, and smart quotes.
            raw, gold = [s.replace('<EMPTY>', '').replace('<SPACE>', ' ').replace(r'\n', '\n')
                         for s in (raw, gold)]
            rows.append(dict(id=f'v4-{source.stem[6:]}-{line_no:04}', split=split,
                             family=family, tags=tags.split(','), input=raw, output=gold,
                             source=f'{source.name}:{line_no}', provenance='AI-authored synthetic'))
    return rows


def check(rows):
    if not rows:
        raise ValueError('No source cases')
    seen, families, ids = {}, collections.defaultdict(set), set()
    for row in rows:
        assert row['split'] in SPLITS, row
        assert row['id'] not in ids, row['id']
        ids.add(row['id'])
        # Empty and whitespace-only inputs are intentionally distinct transport
        # boundary cases, although their lexical normalization is identical.
        key = normalized(row['input']) if row['input'].strip() else 'blank:' + repr(row['input'])
        assert key not in seen, ('Duplicate normalized input', row['id'], seen.get(key))
        seen[key] = row['id']
        families[row['family']].add(row['split'])
        assert row['family'] and all(row['tags']), row['id']
        assert not row['output'] or row['output'] == row['output'].strip(), row['id']
        assert not any(c in row['output'] for c in ('\ufffd', '\r', '\x00')), row['id']
        assert r'\n' not in row['output'], ('Undecoded newline', row['id'])
        if 'identity' in row['tags']:
            assert row['input'] == row['output'], ('Broken identity target', row['id'])
        if 'empty' in row['tags']:
            assert row['output'] == '', row['id']
        if 'bullets' in row['tags']:
            assert len(re.findall(r'^- ', row['output'], re.M)) >= 2, row['id']
        if 'numbered' in row['tags']:
            numbers = re.findall(r'^(\d+)\. ', row['output'], re.M)
            assert len(numbers) >= 2 and numbers == [str(i + 1) for i in range(len(numbers))], row['id']
        if 'prose' in row['tags']:
            assert not re.search(r'^(?:- |\d+\. )', row['output'], re.M), row['id']
        assert not re.search(r'[\u0900-\u097f]', row['input'] + row['output']), ('English-only scope', row['id'])
    assert all(len(s) == 1 for s in families.values()), 'Family crosses splits'
    for split in SPLITS:
        subset = [r for r in rows if r['split'] == split]
        for tag in ('identity', 'correction', 'literal', 'bullets', 'numbered',
                    'quotes', 'brackets', 'numbers', 'no_answer', 'mixed'):
            assert any(tag in r['tags'] for r in subset), (split, 'missing coverage', tag)
    return families


def similarity_audit(rows):
    """Report cross-split surface overlap; this does not prove semantic isolation."""
    tokens = [set(re.findall(r'\w+', normalized(r['input']))) for r in rows]
    flagged = []
    for i, left in enumerate(rows):
        if len(tokens[i]) < 4:
            continue
        for j in range(i):
            if rows[j]['split'] == left['split'] or len(tokens[j]) < 4:
                continue
            score = len(tokens[i] & tokens[j]) / len(tokens[i] | tokens[j])
            if score >= .8:
                flagged.append(dict(left=left['id'], right=rows[j]['id'],
                                    token_jaccard=round(score, 4)))
    return flagged


def dumps(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2) + '\n'


def previous_corpus_audit(rows):
    """Detect accidental reuse and capture old export hashes without changing them."""
    old_inputs, hashes = collections.defaultdict(list), {}
    for directory in ('unsloth-no-system', 'unsloth-structure-v2', 'unsloth-notation-v3'):
        for split in SPLITS:
            path = ROOT.parent / directory / (split + '.jsonl')
            if not path.exists():
                continue
            hashes[f'{directory}/{path.name}'] = hashlib.sha256(path.read_bytes()).hexdigest()
            for line in path.read_text(encoding='utf-8').splitlines():
                raw = json.loads(line)['messages'][0]['content']
                if raw.strip():
                    old_inputs[normalized(raw)].append(f'{directory}/{split}')
    overlaps = [dict(id=r['id'], split=r['split'], input=r['input'],
                     previous_sources=sorted(set(old_inputs[normalized(r['input'])])))
                for r in rows if r['input'].strip() and normalized(r['input']) in old_inputs]
    return dict(previous_export_sha256=hashes, nonblank_input_overlaps=overlaps,
                note='Blank transport cases excluded. Older adapted models are not a clean original-base comparison.')


def artifacts():
    rows = read_cases()
    families = check(rows)
    outputs, metadata, review = {}, [], []
    report = dict(total=len(rows), family_count=len(families), system_messages=0,
                  normalized_nonblank_duplicates=0, cross_split_families=0,
                  provenance='All targets are AI-authored synthetic; no real ASR recordings.',
                  limitations='Structural checks do not establish semantic label correctness or model quality.',
                  sources_sha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in sorted(ROOT.glob('cases_*.txt')) + [ROOT / 'LABELING_POLICY.md']},
                  splits={})
    for offset, split in enumerate(SPLITS):
        subset = [dict(r) for r in rows if r['split'] == split]
        random.Random(20261102 + offset).shuffle(subset)
        conversations = []
        for index, row in enumerate(subset):
            conversations.append(dict(messages=[dict(role='user', content=row['input']),
                                                 dict(role='assistant', content=row['output'])]))
            metadata.append({k: v for k, v in row.items() if k not in ('input', 'output')} |
                            dict(row_index=index, input_sha256=hashlib.sha256(row['input'].encode()).hexdigest()))
        outputs[split + '.jsonl'] = ''.join(json.dumps(c, ensure_ascii=False) + '\n' for c in conversations)
        lengths = sorted(len(r['input'].split()) for r in subset)
        report['splits'][split] = dict(rows=len(subset), families=len({r['family'] for r in subset}),
                                      empty_outputs=sum(not r['output'] for r in subset),
                                      identity=sum('identity' in r['tags'] for r in subset),
                                      mixed=sum('mixed' in r['tags'] for r in subset),
                                      language='English',
                                      input_words=dict(max=max(lengths), median=lengths[len(lengths)//2],
                                                       at_least_60=sum(n >= 60 for n in lengths)),
                                      tags=dict(sorted(collections.Counter(t for r in subset for t in r['tags']).items())))
        # A readable review sample; not additional training data.
        representatives = []
        for tag in report['splits'][split]['tags']:
            row = next(r for r in subset if tag in r['tags'])
            if row['id'] not in representatives:
                representatives.append(row['id'])
                review.append(row)
    report['cross_split_similarity_flags'] = similarity_audit(rows)
    report['previous_corpus_audit'] = previous_corpus_audit(rows)
    outputs['metadata.json'] = dumps(metadata)
    outputs['validation_report.json'] = dumps(report)
    outputs['review_sample.json'] = dumps(review)
    return outputs, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Validate sources and compare exports without writing')
    args = parser.parse_args()
    outputs, report = artifacts()
    for filename, content in outputs.items():
        path = ROOT / filename
        if args.check:
            assert path.read_text(encoding='utf-8') == content, f'Stale export: {filename}'
        else:
            path.write_text(content, encoding='utf-8', newline='\n')
        if filename.endswith('.jsonl'):
            for line in content.splitlines():
                row = json.loads(line)
                assert set(row) == {'messages'}
                assert [m['role'] for m in row['messages']] == ['user', 'assistant']
                assert all(isinstance(m['content'], str) for m in row['messages'])
    print(json.dumps({k: report[k] for k in ('total', 'family_count', 'splits', 'cross_split_similarity_flags')},
                     ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
