"""Run compatibility and advancement probes in an already isolated test server."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import subprocess
import threading
import time
import zipfile


def run(server: Path, java: str, expected_version: str) -> bool:
    reports = [server / name for name in ('ce-compatibility-result.json', 'advancement-probe-result.json')]
    if not all((server / 'plugins' / name).is_file() for name in ('CompatProbe.jar', 'AdvancementProbe.jar')):
        raise ValueError('Use an isolated test server with both verification plugins installed.')
    for report in reports:
        report.unlink(missing_ok=True)
    pack = server / 'plugins/CraftEngine/generated/resource_pack.zip'
    pack.unlink(missing_ok=True)
    process = subprocess.Popen(
        [java, '-Xms256M', '-Xmx1G', f'-Dcookery.probe.craftengine={expected_version}', '-jar', 'paper.jar', '--nogui'],
        cwd=server, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding='utf-8', errors='replace', bufsize=1,
    )
    lines = queue.Queue()

    def read_output():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    started = time.monotonic()
    done = workflow = stopped = None
    try:
        with (server / 'ce-compatibility-console.log').open('w', encoding='utf-8') as log:
            while process.poll() is None or not lines.empty():
                try:
                    line = lines.get(timeout=1)
                except queue.Empty:
                    line = ''
                if line is None:
                    break
                if line:
                    log.write(line)
                    log.flush()
                    if any(word in line for word in ('Done (', 'COMPAT_PROBE_', 'ADVANCEMENT_PROBE_', 'FULL_PACK_ITEM_COUNT', 'ERROR', 'Exception', 'workflow completed', 'Workflow completed')):
                        print(line.rstrip(), flush=True)
                    if 'Done (' in line and done is None:
                        done = time.monotonic()
                now = time.monotonic()
                if done is not None and workflow is None and now - done > 8:
                    process.stdin.write('ce workflow default\n')
                    process.stdin.flush()
                    workflow = now
                    print('Requested complete resource pack generation.', flush=True)
                ready = all(report.is_file() for report in reports) and pack.is_file() and workflow is not None and now - workflow > 10
                if stopped is None and (ready or now - started > 240):
                    process.stdin.write('stop\n')
                    process.stdin.flush()
                    stopped = now
                if stopped is not None and now - stopped > 30:
                    process.terminate()
                    break
        process.wait(timeout=10)
    finally:
        if process.poll() is None:
            try:
                process.stdin.write('stop\n')
                process.stdin.flush()
                process.wait(timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=10)
        process.stdin.close()
        process.stdout.close()

    results = [json.loads(report.read_text(encoding='utf-8')) if report.is_file() else {} for report in reports]
    valid_pack = False
    if pack.is_file():
        with zipfile.ZipFile(pack) as archive:
            valid_pack = archive.testzip() is None and 'pack.mcmeta' in archive.namelist()
    summary = {
        'craftengine': expected_version,
        'serverExitCode': process.returncode,
        'compatibility': results[0].get('success', False),
        'compatibilityChecks': len(results[0].get('checks', [])),
        'advancements': results[1].get('success', False),
        'advancementChecks': sum(value is True for key, value in results[1].items() if key != 'success'),
        'phase': results[1].get('phase'),
        'resourcePackValid': valid_pack,
        'cookerySha256': hashlib.sha256(next((server / 'plugins').glob('KaleidoscopeCookeryPlugin-*.jar')).read_bytes()).hexdigest(),
        'errors': [result.get('error') for result in results],
    }
    summary['success'] = process.returncode == 0 and summary['compatibility'] and summary['advancements'] and valid_pack
    (server / 'ce-verification-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return summary['success']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--craftengine', required=True, help='Exact CraftEngine plugin version, including -SNAPSHOT if present.')
    args = parser.parse_args()
    raise SystemExit(0 if run(args.server.resolve(), args.java, args.craftengine) else 1)
