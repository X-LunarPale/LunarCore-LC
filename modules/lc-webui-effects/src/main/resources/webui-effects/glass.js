import { LiquidGlassEngine } from './quick-liquid.mjs'

export function mountGlass(element, dark) {
  const solidSurfaces = matchMedia('(forced-colors: active), (prefers-contrast: more), (prefers-reduced-transparency: reduce)')
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
    const borderRadius = parseFloat(getComputedStyle(element).borderTopRightRadius) || 0
    engine = new LiquidGlassEngine(element, {
      appearance,
      borderRadius,
      material: 'thin',
      quality: 'medium',
      blur: 7,
      saturation: 1.2,
      refractionStrength: 18,
      bezelWidth: 16,
      dispersionMode: 'mono',
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
