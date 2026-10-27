"""Run the compiled integration tests with a child-local environment."""
import os
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[1]
env = os.environ.copy()
env['PATH'] = str(ROOT / '.qt/6.8.3/msvc2022_64/bin') + os.pathsep + env['PATH']
env['SSTV_TEST_VIDEO'] = str(ROOT / '.deps/playback-test.mp4')
env['SSTV_TEST_SERVER'] = 'http://127.0.0.1:18764'
env['SSTV_TEST_REPORT'] = str(ROOT / 'out/test-report')
result = subprocess.run([str(ROOT / 'out/native/SquirrelTV.exe'), '--self-test'], env=env, timeout=120)
print('Integration test exit:', result.returncode)
print((ROOT / 'out/test-report/results.xml').read_text(encoding='utf-8'))
raise SystemExit(result.returncode)
