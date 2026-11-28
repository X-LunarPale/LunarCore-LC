import { LiquidGlassEngine } from './quick-liquid.mjs'

export function mountGlass(element, dark) {
  const solidSurfaces = matchMedia('(forced-colors: active), (prefers-contrast: more), (prefers-reduced-transparency: reduce)')
  const clear = element.parentElement?.dataset.lcGlass === 'clear'
  let engine

  function clearGlass() {
    engine?.destroy()
    engine = undefined
    element.classList.remove('optical-glass')
  }

  function refresh() {
    if (solidSurfaces.matches) {
      clearGlass()
      return
    }
    const appearance = dark ? 'dark' : 'light'
    if (engine) {
      engine.updateConfig({ appearance })
      return
    }
    const borderRadius = parseFloat(getComputedStyle(element).borderTopRightRadius) || 18
    engine = new LiquidGlassEngine(element, {
      appearance,
      borderRadius,
      material: 'clear',
      quality: 'medium',
      blur: clear ? 2 : 6,
      refractionStrength: clear ? 38 : 28,
      bezelWidth: clear ? 24 : 17,
      lightAngle: -60,
      specularStrength: 0.75,
      chromaticAberration: clear ? 0.18 : 0,
      elevation: 0
    })
    element.classList.add('optical-glass')
  }

  refresh()
  solidSurfaces.addEventListener('change', refresh)
  return {
    setDark(value) {
      dark = value
      refresh()
    },
    destroy() {
      solidSurfaces.removeEventListener('change', refresh)
      clearGlass()
    }
  }
}
