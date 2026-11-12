"""Verify deployed panel bundle includes the action dialog layout fix."""
import re
import urllib.request
base = __import__('os').environ.get('LC_WEB_BASE_URL', 'http://localhost:8765')
def fetch(path):
    return urllib.request.urlopen(base + path, timeout=15).read().decode()
index = fetch('/')
entry = '/' + re.search(r'src="\.?/?(assets/index-[^"]+\.js)"', index).group(1)
js = fetch(entry)
from pathlib import Path
css_name = 'assets/' + next(Path('web-ui/dist/assets').glob('PanelView-*.css')).name
css = fetch('/' + css_name)
assert '.action-form' in css and 'height:auto' in css and 'overflow-wrap:anywhere' in css
print('HTTP 200; deployed panel CSS has auto-height labels and wrapping:', css_name)
