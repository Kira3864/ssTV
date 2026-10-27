"""Smoke-test shipped binaries without the Qt SDK on PATH."""
from pathlib import Path
import subprocess
import zipfile
import json
import os
import time
import psutil

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'out/package-check'
OUT.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env['PATH'] = os.pathsep.join(p for p in env['PATH'].split(os.pathsep) if '.qt' not in p.lower())
video = ROOT / '.deps/playback-test.mp4'

def smoke(folder):
    exe = folder / 'SquirrelTV.exe'
    assert exe.is_file()
    assert not (folder / 'Qt6Test.dll').exists()
    process = subprocess.Popen([str(exe), '--portable', '--smoke-test', str(video)], cwd=folder, env=env)
    time.sleep(2)
    memory = psutil.Process(process.pid).memory_info().rss
    code = process.wait(timeout=30)
    assert code == 0, (folder, code)
    assert (folder / 'data/smoke.png').is_file()
    log = folder / 'startup.log'
    if log.exists(): assert 'failed to load component' not in log.read_text(encoding='utf-8')
    return {'exit':code, 'working_set_mib':round(memory/1048576,1)}

portable = OUT / 'portable'
with zipfile.ZipFile(ROOT / 'dist/ssTV-0.1.0-Windows-x64-Portable.zip') as z:
    assert all('/data/' not in '/'+n and not n.endswith('startup.log') for n in z.namelist())
    z.extractall(portable)
report = {'portable':smoke(portable)}
installed = OUT / 'installed'
setup = ROOT / 'dist/ssTV-0.1.0-Windows-x64-Setup.exe'
subprocess.run([str(setup), '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/NOICONS', '/TASKS=', '/DIR='+str(installed)], timeout=120, check=True)
try:
    report['installed'] = smoke(installed)
finally:
    subprocess.run([str(installed / 'unins000.exe'), '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'], timeout=60, check=True)
report['sizes'] = {p.name:p.stat().st_size for p in (ROOT / 'dist').iterdir() if p.suffix in ('.exe','.zip')}
(OUT / 'results.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
