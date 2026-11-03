(() => {
  'use strict';
  const input = document.getElementById('name');
  const output = document.getElementById('preview');
  const colors = ['#000000','#0000aa','#00aa00','#00aaaa','#aa0000','#aa00aa','#ffaa00','#aaaaaa','#555555','#5555ff','#55ff55','#55ffff','#ff5555','#ff55ff','#ffff55','#ffffff'];
  function render() {
    output.replaceChildren();
    let state = {}, buffer = '';
    function flush() {
      if (!buffer) return;
      const span = document.createElement('span');
      span.textContent = buffer;
      Object.assign(span.style, state);
      output.appendChild(span);
      buffer = '';
    }
    const value = input.value;
    for (let i = 0; i < value.length; i++) {
      if ((value[i] === '&' || value[i] === '§') && i + 1 < value.length) {
        const code = value[i + 1].toLowerCase();
        const hex = value.slice(i + 1).match(/^#([0-9a-f]{6})/i);
        const color = parseInt(code, 16);
        if (hex) { flush(); state = { color: '#' + hex[1] }; i += 7; continue; }
        if (/^[0-9a-f]$/.test(code)) { flush(); state = { color: colors[color] }; i++; continue; }
        if ('lmnor'.includes(code)) {
          flush();
          if (code === 'r') state = {};
          else if (code === 'l') state.fontWeight = 'bold';
          else if (code === 'o') state.fontStyle = 'italic';
          else if (code === 'm') state.textDecoration = 'line-through';
          else if (code === 'n') state.textDecoration = 'underline';
          i++; continue;
        }
      }
      buffer += value[i];
    }
    flush();
  }
  input.addEventListener('input', render);
  render();
})();
