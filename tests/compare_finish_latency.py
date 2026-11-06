"""Real-time old/new growing Stop-path comparison; explicit audio, simulated insertion only."""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


def words(text):
    return re.findall(r'\w+', text.casefold())


def main(args):
    root = Path(__file__).resolve().parents[1]
    rows = []
    for number, source in enumerate(args.audio):
        pair = []
        for baseline in (True, False):
            name = f'finish-{number}-' + ('baseline' if baseline else 'optimized')
            report = args.output / (name + '.json')
            command = [sys.executable, '-u', str(root / 'replay_pc_dictate_v2.py'), str(source),
                       '--streaming-strategy', 'growing', '--report', str(report)]
            if baseline: command.append('--baseline-finish')
            with (args.output / (name + '.log')).open('w', encoding='utf-8') as log:
                subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
            pair.append(json.loads(report.read_text(encoding='utf-8')))
        before, after = pair
        row = {'audio': str(source), 'duration': after['duration_sec'],
            'before_ms': before['stop_delay_sec'] * 1000, 'after_ms': after['stop_delay_sec'] * 1000,
            'before_requests': before['progress']['requests'], 'after_requests': after['progress']['requests'],
            'exact_text_equal': before['text'] == after['text'], 'words_equal': words(before['text']) == words(after['text']),
            'one_insertion': before['insertions'] == after['insertions'] == 1,
            'before_text': before['text'], 'after_text': after['text']}
        rows.append(row)
        (args.output / 'comparison.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
        print(f'{source.name}: Stop {row["before_ms"]:.0f} -> {row["after_ms"]:.0f}ms; '
              f'requests {row["before_requests"]} -> {row["after_requests"]}; '
              f'exact={row["exact_text_equal"]} words={row["words_equal"]} insert_once={row["one_insertion"]}', flush=True)
    if not all(r['words_equal'] and r['one_insertion'] for r in rows):
        raise SystemExit('Output differences require review; inspect comparison.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('audio', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    main(args)
