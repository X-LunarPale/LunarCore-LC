import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'

for (const relative of ['./MinecraftTextPreview.vue', '../panel/FieldInput.vue', '../maintenance/MaintenanceWorkspace.vue']) {
  const url = new URL(relative, import.meta.url)
  const source = readFileSync(url, 'utf8')
  const { descriptor, errors } = parse(source, { filename: url.pathname })
  assert.deepEqual(errors, [], relative)
  const script = compileScript(descriptor, { id: 'preview-check' })
  const template = compileTemplate({ source: descriptor.template.content, filename: url.pathname, id: 'preview-check', compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [], relative)
}
console.log('Preview components: Vue script and template compilation checks passed (no application build).')
