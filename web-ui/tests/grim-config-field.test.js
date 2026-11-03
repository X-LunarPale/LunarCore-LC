import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { grimFieldError, parsePunishmentCommand } from '../src/utils/grim-config.js'
import { t } from '../src/i18n.js'

const source = await readFile(new URL('../src/components/moderation/GrimConfigField.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'grim-config-field-test', genDefaultAs: '__component' })
const template = compileTemplate({
  source: descriptor.template.content,
  filename: 'GrimConfigField.vue',
  id: 'grim-config-field-test',
  compilerOptions: { mode: 'function', bindingMetadata: script.bindings }
})
assert.deepEqual(template.errors, [])
const imports = { ...vue, grimFieldError, parsePunishmentCommand, t, FieldInput: { render: () => null } }
const names = Object.keys(script.imports)
for (const name of names) assert.ok(Object.hasOwn(imports, name), `Missing import: ${name}`)
const component = new Function(...names, `${script.content.replace(/^import .*$/gm, '')}; return __component`)(...names.map(name => imports[name]))
component.render = new Function('Vue', template.code)(vue)

const renderer = vue.createRenderer({
  createElement: type => ({ type, children: [], props: {} }),
  createText: text => ({ text }),
  createComment: text => ({ comment: text }),
  insert(node, parent, anchor) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node)
    else parent.children.splice(index, 0, node)
  },
  remove(node) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = null
  },
  setText(node, text) { node.text = text },
  setElementText(node, text) { node.text = text; node.children = [] },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
  patchProp(node, key, previous, value) { node.props[key] = value }
})
const control = type => ({
  inheritAttrs: false,
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue'],
  setup(props, { attrs, emit, slots }) {
    return () => vue.h(type, {
      ...attrs, modelValue: props.modelValue, disabled: props.disabled,
      'onUpdate:modelValue': value => emit('update:modelValue', value)
    }, slots.default?.())
  }
})
async function settle() {
  await vue.nextTick()
  await Promise.resolve()
  await vue.nextTick()
}
function descendants(root, predicate) {
  return (root.children || []).flatMap(child => [
    ...(predicate(child) ? [child] : []), ...descendants(child, predicate)
  ])
}
async function environment(context, initial = ['20:40 [alert]']) {
  const values = vue.ref(initial)
  const field = vue.ref({ key: 'Punishments.Combat.commands', label: '处罚命令', type: 'punishment-commands' })
  const disabled = vue.ref(false)
  const emitted = []
  const root = { children: [] }
  const app = renderer.createApp({
    setup: () => () => vue.h(component, {
      field: field.value, modelValue: values.value, disabled: disabled.value,
      'onUpdate:modelValue': value => { emitted.push(value); values.value = value }
    })
  })
  app.component('Icon', { render: () => null })
  app.component('el-input-number', control('test-number'))
  app.component('el-input', control('test-input'))
  app.component('el-button', control('test-button'))
  app.component('el-tooltip', { inheritAttrs: false, setup: (_, { slots }) => () => slots.default?.() })
  app.component('el-form', { inheritAttrs: false, setup: (_, { slots }) => () => slots.default?.() })
  app.mount(root)
  context.after(() => app.unmount())
  await settle()
  const numbers = () => descendants(root, node => node.type === 'test-number')
  const inputs = () => descendants(root, node => node.type === 'test-input')
  const buttons = () => descendants(root, node => node.type === 'test-button')
  async function type(node, value) { node.props['onUpdate:modelValue'](value); await settle() }
  return { values, field, disabled, emitted, root, numbers, inputs, buttons, type }
}

test('clearing and refilling a violation number preserves structured controls and updates the controlled model', async context => {
  const env = await environment(context)
  assert.equal(env.numbers().length, 2)
  await env.type(env.numbers()[0], undefined)
  assert.deepEqual(env.values.value, [':40 [alert]'])
  assert.equal(env.numbers().length, 2)
  assert.equal(env.numbers()[0].props.modelValue, undefined)
  assert.equal(env.inputs()[0].props['aria-label'], '执行命令')
  await env.type(env.numbers()[0], 35)
  assert.deepEqual(env.values.value, ['35:40 [alert]'])
  assert.equal(env.numbers().length, 2)
  await env.type(env.numbers()[1], undefined)
  assert.deepEqual(env.values.value, ['35: [alert]'])
  assert.equal(env.numbers().length, 2)
  await env.type(env.numbers()[1], 0)
  assert.deepEqual(env.values.value, ['35:0 [alert]'])
})

test('clearing and refilling a command preserves numeric controls throughout editing', async context => {
  const env = await environment(context)
  await env.type(env.inputs()[0], '')
  assert.deepEqual(env.values.value, ['20:40 '])
  assert.equal(env.numbers().length, 2)
  assert.equal(env.inputs()[0].props['aria-label'], '执行命令')
  await env.type(env.inputs()[0], 'kick %player% Invalid movement')
  assert.deepEqual(env.values.value, ['20:40 kick %player% Invalid movement'])
  assert.equal(env.numbers().length, 2)
})

test('external snapshots replace incomplete local rule state and preserve unknown raw rules', async context => {
  const env = await environment(context)
  await env.type(env.numbers()[0], undefined)
  env.values.value = ['5:7 [log]']
  await settle()
  assert.equal(env.numbers()[0].props.modelValue, 5)
  assert.equal(env.numbers()[1].props.modelValue, 7)
  assert.equal(env.inputs()[0].props.modelValue, '[log]')
  env.values.value = ['unrecognized legacy rule']
  await settle()
  assert.equal(env.numbers().length, 0)
  assert.equal(env.inputs()[0].props['aria-label'], '原始处罚规则')
  assert.equal(env.inputs()[0].props.modelValue, 'unrecognized legacy rule')
  await env.type(env.inputs()[0], '9:0 [webhook]')
  assert.equal(env.numbers().length, 2)
  assert.equal(env.numbers()[0].props.modelValue, 9)
  assert.equal(env.numbers()[1].props.modelValue, 0)
})

test('disabled and read-only rules cannot change values, add rows, or remove rows', async context => {
  const env = await environment(context)
  env.disabled.value = true
  await settle()
  assert.ok(env.numbers().every(node => node.props.disabled))
  assert.ok(env.inputs().every(node => node.props.disabled))
  assert.ok(env.buttons().every(node => node.props.disabled))
  await env.type(env.numbers()[0], 2)
  await env.type(env.inputs()[0], 'stop')
  for (const button of env.buttons()) button.props.onClick()
  await settle()
  assert.deepEqual(env.values.value, ['20:40 [alert]'])
  assert.equal(env.emitted.length, 0)
  env.disabled.value = false
  env.field.value = { ...env.field.value, readOnly: true }
  await settle()
  assert.ok(env.numbers().every(node => node.props.disabled))
  await env.type(env.numbers()[0], 2)
  await env.type(env.inputs()[0], 'stop')
  for (const button of env.buttons()) button.props.onClick()
  await settle()
  assert.deepEqual(env.values.value, ['20:40 [alert]'])
  assert.equal(env.emitted.length, 0)
})
