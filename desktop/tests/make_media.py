"""Create deterministic local fixtures. Requires imageio-ffmpeg."""
from pathlib import Path
import subprocess
import imageio_ffmpeg
root = Path(__file__).resolve().parents[1] / '.deps'
root.mkdir(exist_ok=True)
ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
def run(args):
    subprocess.run([ffmpeg, '-y', *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
video = root / 'playback-test.mp4'
run(['-f','lavfi','-i','testsrc2=size=1280x720:rate=30','-f','lavfi','-i',
     'sine=frequency=440:sample_rate=48000','-t','20','-c:v','libx264',
     '-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac',str(video)])
for folder, options, seconds in [('hls',['-c','copy'],'3'),
        ('stallmedia',['-c:v','libx264','-preset','ultrafast','-g','30','-keyint_min','30','-sc_threshold','0','-c:a','copy'],'1')]:
    target = root / folder
    target.mkdir(exist_ok=True)
    run(['-i',str(video),*options,'-hls_time',seconds,'-hls_list_size','0',str(target/'index.m3u8')])
