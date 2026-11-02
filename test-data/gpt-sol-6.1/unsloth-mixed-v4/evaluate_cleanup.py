"""Generate or score held-out cleanup predictions, without a system prompt.

Standard library only. Network requests happen only with --url. No training.
"""
import argparse
import collections
import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def load_benchmark(split):
    conversations = [json.loads(s) for s in (ROOT / f'{split}.jsonl').read_text(encoding='utf-8').splitlines()]
    metadata = sorted((m for m in json.loads((ROOT / 'metadata.json').read_text(encoding='utf-8'))
                       if m['split'] == split), key=lambda m: m['row_index'])
    if len(metadata) != len(conversations):
        raise ValueError('Metadata and exported rows disagree; rebuild the dataset')
    cases = []
    for index, (meta, row) in enumerate(zip(metadata, conversations)):
        if meta['row_index'] != index or [m['role'] for m in row['messages']] != ['user', 'assistant']:
            raise ValueError('Bad export ordering or roles')
        raw, gold = [m['content'] for m in row['messages']]
        if hashlib.sha256(raw.encode()).hexdigest() != meta['input_sha256']:
            raise ValueError('Metadata input hash mismatch')
        cases.append(dict(meta, input=raw, gold=gold))
    return cases


def score(cases, predictions):
    if not cases:
        raise ValueError('Benchmark is empty')
    by_id = {}
    for row in predictions:
        if not isinstance(row, dict) or not isinstance(row.get('id'), str):
            raise ValueError('Each prediction needs a string id')
        if row['id'] in by_id:
            raise ValueError(f"Duplicate prediction id: {row['id']}")
        if not isinstance(row.get('prediction'), str):
            raise ValueError(f"Prediction must be a string: {row['id']}")
        if row.get('finish_reason') is not None and not isinstance(row['finish_reason'], str):
            raise ValueError(f"Finish reason must be a string or null: {row['id']}")
        by_id[row['id']] = row
    expected = {c['id'] for c in cases}
    if set(by_id) != expected:
        raise ValueError(f'Prediction ids must match the full split: missing={len(expected-set(by_id))}, extra={len(set(by_id)-expected)}')
    totals = collections.Counter()
    tags = collections.defaultdict(collections.Counter)
    mismatches = []
    for case in cases:
        prediction = by_id[case['id']]
        text = prediction['prediction']
        # Missing finish metadata is unverified, not silently called complete.
        valid = (prediction.get('finish_reason') == 'stop' and not prediction.get('error')
                 and not prediction.get('reasoning_present'))
        exact = valid and text == case['gold']
        values = dict(rows=1, exact=int(exact), completed=int(valid),
                      not_completed=int(not valid),
                      errors=int(bool(prediction.get('error'))),
                      truncated=int(prediction.get('finish_reason') == 'length'),
                      unverified=int(prediction.get('finish_reason') is None),
                      reasoning_present=int(bool(prediction.get('reasoning_present'))))
        totals.update(values)
        for tag in case['tags']:
            tags[tag].update(values)
        if not exact:
            mismatches.append(dict(id=case['id'], tags=case['tags'], input=case['input'],
                                   expected=case['gold'], actual=text,
                                   finish_reason=prediction.get('finish_reason'),
                                   error=prediction.get('error'),
                                   reasoning_present=bool(prediction.get('reasoning_present'))))
    summarize = lambda counts: dict(counts, exact_match=round(counts['exact'] / counts['rows'], 6))
    return dict(overall=summarize(totals),
                by_tag={tag: summarize(counts) for tag, counts in sorted(tags.items())},
                limitation='Strict exact match, including layout and whitespace. Not a semantic-quality or production-reliability score.',
                mismatches=mismatches)


def generate(cases, args):
    url = args.url.rstrip('/') + '/v1/chat/completions'
    from urllib.parse import urlparse
    parsed = urlparse(url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname:
        raise ValueError('Use an http(s) server origin, for example http://127.0.0.1:8080')
    args.output.mkdir(parents=True, exist_ok=True)
    prediction_path = args.output / f'{args.split}-predictions.jsonl'
    report_path = args.output / f'{args.split}-report.json'
    # Refuse to silently overwrite previous measurements.
    if prediction_path.exists() or report_path.exists():
        raise ValueError('Use a new output directory; previous measurements already exist')
    rows = []
    with prediction_path.open('x', encoding='utf-8', newline='\n') as handle:
        for index, case in enumerate(cases, 1):
            payload = dict(model=args.model, messages=[dict(role='user', content=case['input'])],
                           temperature=0, reasoning_effort='none',
                           max_tokens=args.max_tokens, stream=False)
            headers = {'Content-Type': 'application/json'}
            if os.environ.get('CLEANUP_API_KEY'):
                headers['Authorization'] = 'Bearer ' + os.environ['CLEANUP_API_KEY']
            started = time.monotonic()
            result = dict(id=case['id'], prediction='', finish_reason='error')
            try:
                request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers)
                with urllib.request.urlopen(request, timeout=args.timeout) as response:
                    choice = json.load(response)['choices'][0]
                content = choice['message']['content']
                if not isinstance(content, str):
                    raise ValueError('Model returned non-string content')
                result.update(prediction=content, finish_reason=choice.get('finish_reason'),
                              reasoning_present=bool(choice['message'].get('reasoning_content')
                                                     or choice['message'].get('reasoning')))
            except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError, IndexError, TypeError) as exc:
                result['error'] = f'{type(exc).__name__}: {exc}'
            result['elapsed_ms'] = round((time.monotonic() - started) * 1000)
            rows.append(result)
            handle.write(json.dumps(result, ensure_ascii=False) + '\n')
            handle.flush()
            print(f'{index}/{len(cases)} {case["id"]}: {result["finish_reason"]}', flush=True)
    return rows, report_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--split', choices=('validation', 'test'), default='validation')
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--predictions', type=Path, help='Score existing JSONL rows: id, prediction, finish_reason')
    source.add_argument('--url', help='Generate from an OpenAI-compatible server origin; no system message is sent')
    parser.add_argument('--model')
    parser.add_argument('--output', type=Path, help='New directory for generation results, or a JSON report path when scoring')
    parser.add_argument('--max-tokens', type=int, default=1024)
    parser.add_argument('--timeout', type=float, default=120)
    args = parser.parse_args()
    if args.url and (not args.model or args.output is None):
        parser.error('--url requires --model and --output DIRECTORY')
    if args.max_tokens <= 0 or args.timeout <= 0:
        parser.error('--max-tokens and --timeout must be positive')
    cases = load_benchmark(args.split)
    if args.url:
        predictions, report_path = generate(cases, args)
    else:
        predictions = [json.loads(s) for s in args.predictions.read_text(encoding='utf-8').splitlines() if s.strip()]
        report_path = args.output
    report = score(cases, predictions)
    report.update(split=args.split, model=args.model,
                  dataset_sha256=hashlib.sha256((ROOT / f'{args.split}.jsonl').read_bytes()).hexdigest(),
                  system_prompt_sent=False if args.url else 'external predictions; not verified')
    content = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if report_path is not None:
        with report_path.open('x', encoding='utf-8', newline='\n') as handle:
            handle.write(content)
    print(json.dumps(report['overall'], indent=2))


if __name__ == '__main__':
    main()
