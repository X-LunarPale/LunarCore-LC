import { readFile } from 'node:fs/promises'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
const names = ['components/panel/ActionRunner.vue','components/panel/FormSection.vue','components/panel/TableSection.vue','components/panel/StatsSection.vue','views/PanelView.vue','views/Config.vue']
for (const name of names) {
 const filename = new URL(`../src/${name}`, import.meta.url)
 const { descriptor, errors } = parse(await readFile(filename, 'utf8'), { filename: name })
 if (errors.length) throw new Error(String(errors))
 const script = compileScript(descriptor, { id: name })
 const result = compileTemplate({ source: descriptor.template.content, filename: name, id: name, compilerOptions: { bindingMetadata: script.bindings } })
 if (result.errors.length) throw new Error(`${name}: ${result.errors}`)
 console.log(`PASS Vue compile ${name}`)
}
