#!/usr/bin/env python3
"""Opt-in acceptance against an existing, labelled disposable MySQL container.

Uses only Python stdlib, Docker CLI and an extracted Flydb distribution. Does not
download drivers, create containers, remove files or touch an unlabelled database.
"""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli', required=True, type=Path)
    parser.add_argument('--container', required=True)
    parser.add_argument('--allow-temporary-writes', action='store_true', required=True)
    args = parser.parse_args()
    cli = args.cli.resolve()
    container = json.loads(subprocess.check_output(['docker', 'inspect', args.container]))[0]
    assert container['Config']['Labels'].get('com.flydb.purpose') == 'gui-acceptance', 'Container must carry acceptance label'
    assert not any(m['Type'] == 'bind' for m in container['Mounts']), 'User bind mounts are forbidden'
    ports = container['NetworkSettings']['Ports']['3306/tcp']
    assert len(ports) == 1 and ports[0]['HostIp'] == '127.0.0.1', 'Require loopback-only MySQL'
    port = ports[0]['HostPort']
    root = Path(tempfile.mkdtemp(prefix='flydb-web-candidate-')).resolve()
    state = root / 'state'
    env = {**os.environ, 'FLYDB_WORKBENCH_DIR': str(state), 'FLYDB_PASSWORD': ''}
    base_url = f'jdbc:mysql://127.0.0.1:{port}/flydb_gui?useSSL=false&allowPublicKeyRetrieval=true'
    suffix = uuid.uuid4().hex[:10]
    table = 'web_qa_' + suffix
    evidence = []

    def check(condition, name):
        assert condition, name
        evidence.append(name)
        print('PASS ' + name, flush=True)

    def project(name, scripts):
        path = root / name
        (path / 'db').mkdir(parents=True)
        for filename, sql in scripts.items():
            (path / 'db' / filename).write_text(sql)
        (path / 'flydb.conf').write_text('\n'.join([
            '# isolated GUI acceptance fixture', 'flydb.url=' + base_url,
            'flydb.user=root', 'flydb.database-type=mysql', 'flydb.offline=true',
            'flydb.table=' + table + '_' + name, 'flydb.locations=filesystem:db', '',
        ]))
        return path

    def terminal(path, *command):
        result = subprocess.run([str(cli), '--json', '--config', str(path / 'flydb.conf'), *command],
                                cwd=path, env=env, text=True, capture_output=True, timeout=60)
        assert result.returncode == 0, result.stderr
        lines = result.stdout.strip().splitlines()
        assert len(lines) == 1, result.stdout
        return json.loads(lines[0])

    # CLI records independently of Web, and remains a single JSON envelope.
    pre = project('cli', {'V1__cli.sql': f'CREATE TABLE {table}_cli_data (id INT);'})
    terminal(pre, 'version')
    terminal(pre, 'validate')
    terminal(pre, '--dry-run', 'migrate')
    terminal(pre, 'migrate')
    records = [json.loads(p.read_text()) for p in state.glob('runs/*/summary.json')]
    check(any(r['source'] == 'CLI' and r['command'] == 'migrate' and r['status'] == 'SUCCEEDED' for r in records),
          'CLI writes a terminal record while Web is stopped')

    process = subprocess.Popen([str(cli), 'web', '--no-open', '--state-dir', str(state)], cwd=root,
                               env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = queue.Queue()
    threading.Thread(target=lambda: [output.put(line) for line in process.stdout], daemon=True).start()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    try:
        url = None
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            line = output.get(timeout=5)
            match = re.search(r'(http://127\.0\.0\.1:\d+)/#key=([^\s]+)', line)
            if match:
                url, key = match.groups()
                break
        assert url, 'Web did not print a startup address'

        def api(path, value=None, method=None):
            request = urllib.request.Request(url + '/api' + path,
                data=None if value is None else json.dumps(value).encode(),
                headers={} if value is None else {'Content-Type': 'application/json'}, method=method)
            with opener.open(request, timeout=10) as response:
                return json.load(response)

        api('/session', {'key': key})
        check(any(r['source'] == 'CLI' for r in api('/bootstrap')['runs']), 'Web discovers earlier CLI records')
        with opener.open(url, timeout=10) as response:
            html = response.read().decode()
        assets = re.findall(r'(?:src|href)="((?:\./|/)assets/[^\"]+)"', html)
        check(bool(assets), 'Distribution embeds production assets')
        for asset in assets:
            with opener.open(url + '/' + asset.lstrip('./'), timeout=10) as response:
                assert response.status == 200 and response.read()

        def register(path):
            return api('/profiles', {'mode': 'import', 'workingDirectory': str(path),
                                    'configPath': str(path / 'flydb.conf'), 'name': path.name})[0]['id']

        def submit(profile, command, **extra):
            body = {'command': command, 'revision': api('/profiles/' + profile + '/config')['revision'],
                    'requestId': str(uuid.uuid4()), **extra}
            run = api('/profiles/' + profile + '/actions', body)
            return run, body

        def finished(run):
            deadline = time.monotonic() + 45
            while run['status'] == 'RUNNING' and time.monotonic() < deadline:
                time.sleep(0.2)
                run = api('/runs/' + run['id'])
            assert run['status'] != 'RUNNING', run
            return run

        def action(profile, command, **extra):
            return finished(submit(profile, command, **extra)[0])

        def observe_cli(path, crash=False):
            terminal(path, 'validate')
            terminal(path, '--dry-run', 'migrate')
            child = subprocess.Popen([str(cli), '--json', '--config', str(path / 'flydb.conf'), 'migrate'],
                cwd=path, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 5
                live = None
                while time.monotonic() < deadline:
                    live = next((r for r in api('/bootstrap')['runs'] if r.get('configPath') == str(path / 'flydb.conf')
                                 and r['command'] == 'migrate' and r['status'] == 'RUNNING'), None)
                    if live:
                        break
                    time.sleep(0.1)
                assert live, 'Live CLI execution was not observed'
                if crash:
                    child.kill()
                    child.communicate(timeout=15)
                    check(api('/runs/' + live['id'])['status'] == 'UNKNOWN', 'Killed CLI process is marked unknown without replay')
                else:
                    stdout, stderr = child.communicate(timeout=20)
                    assert child.returncode == 0, stderr
                    assert len(stdout.strip().splitlines()) == 1
                    check(finished(live)['status'] == 'SUCCEEDED', 'GUI observes a live CLI migration and its terminal result')
            finally:
                if child.poll() is None:
                    child.kill()
                    child.wait(timeout=15)

        (pre / 'db' / 'V2__long.sql').write_text('SELECT SLEEP(4);')
        observe_cli(pre)
        observe_cli(project('interrupted', {'V1__sleep.sql': 'SELECT SLEEP(10);'}), crash=True)

        path = project('web', {'V1__create.sql': f'CREATE TABLE {table}_data (id INT PRIMARY KEY);',
            'V2__insert.sql': f'INSERT INTO {table}_data VALUES (1); SELECT SLEEP(4);',
            'U2__remove.sql': f'DELETE FROM {table}_data WHERE id=1;'})
        profile = register(path)
        plan = action(profile, 'plan')
        check(plan['status'] == 'SUCCEEDED', 'Validate and preview before Web migration')
        run, request = submit(profile, 'migrate', planId=plan['result']['planId'])
        check(api('/profiles/' + profile + '/actions', request)['id'] == run['id'], 'Duplicate submission returns the same run')
        check(any(r['id'] == run['id'] for r in api('/bootstrap')['runs']), 'Page refresh can recover active run')
        run = finished(run)
        check(run['status'] == 'SUCCEEDED' and run['verification'] == 'PASSED', 'Web migration and independent verification succeed')
        check(run['driver']['className'] == 'com.mysql.cj.jdbc.Driver', 'Actual JDBC driver source is recorded')
        event_request = urllib.request.Request(url + '/api/runs/' + run['id'] + '/events', headers={'Last-Event-ID': '1'})
        with opener.open(event_request, timeout=10) as response:
            events = response.read().decode()
        ids = [int(x) for x in re.findall(r'^id: (\d+)$', events, re.MULTILINE)]
        check(ids and min(ids) > 1 and ids == sorted(set(ids)) and 'event: finished' in events, 'SSE replays ordered events after Last-Event-ID')
        undo = action(profile, 'undo-plan')
        run = action(profile, 'undo', planId=undo['result']['planId'])
        check(run['status'] == 'SUCCEEDED' and run['verification'] == 'PASSED', 'Undo preview and execution succeed')
        plan = action(profile, 'plan')
        with (path / 'db' / 'V2__insert.sql').open('a') as out:
            out.write('\nSELECT 9;')
        run = action(profile, 'migrate', planId=plan['result']['planId'])
        check(run['status'] == 'FAILED' and run['result']['error']['code'] == 'FLYDB-2011', 'Changed SQL rejects a stale plan')

        base = register(project('baseline', {}))
        run = action(base, 'baseline', baselineVersion='2', confirmed=True)
        check(run['status'] == 'SUCCEEDED' and run['verification'] == 'PASSED', 'Baseline uses an independent empty history table')
        failpath = project('failure', {'V1__broken.sql': 'SELECT * FROM definitely_missing_web_qa_table;'})
        failure = register(failpath)
        plan = action(failure, 'plan')
        run = action(failure, 'migrate', planId=plan['result']['planId'])
        check(run['status'] == 'FAILED' and run['result']['error']['code'] == 'FLYDB-2010', 'SQL failure persists a truthful terminal diagnostic')
        (failpath / 'db' / 'V1__broken.sql').write_text('SELECT 1;')
        run = action(failure, 'repair', confirmed=True)
        check(run['status'] == 'SUCCEEDED' and run['verification'] == 'PASSED', 'Explicit repair succeeds after inspecting and correcting the failed script')

        configuration = api('/profiles/' + profile + '/config')
        with (path / 'flydb.conf').open('a') as out:
            out.write('# external editor\n')
        try:
            api('/profiles/' + profile + '/config', {'revision': configuration['revision'], 'values': {'flydb.user': 'changed'}}, 'PUT')
            raise AssertionError('Expected conflict')
        except urllib.error.HTTPError as error:
            check(error.code == 409 and json.load(error)['code'] == 'CONFIG_CONFLICT', 'External configuration changes are never overwritten')
        api('/profiles/' + base, {}, 'DELETE')
        check((root / 'baseline' / 'flydb.conf').exists(), 'Removing registration preserves original files')
        report = {'container': args.container, 'target': base_url, 'javaHome': env.get('JAVA_HOME'),
                  'cli': str(cli), 'checks': evidence, 'workspace': str(root)}
        (root / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print('Evidence: ' + str(root / 'evidence.json'), flush=True)
    finally:
        process.terminate()
        process.wait(timeout=15)


if __name__ == '__main__':
    main()
