import test from 'node:test'
import assert from 'node:assert/strict'
import { chatPreview } from '../src/components/chat/chat-preview.js'
import { parseMinecraftText } from '../src/components/text-preview/minecraft-text.js'

const plain = text => parseMinecraftText(text).segments.map(segment => segment.text).join('')
test('chat preview follows world title player order with custom colors', () => {
  const rendered = chatPreview('<aqua>[{world}]</aqua> {title} <displayname> » <message>', {
    world: 'survival', worldName: '生存世界', color: '#12ab34', title: '&6[旅行者]', player: 'Steve', message: '你好'
  })
  assert.equal(plain(rendered), '[生存世界] [旅行者] Steve » 你好')
  assert.ok(parseMinecraftText(rendered).segments.some(segment => segment.text === '生存世界' && segment.style.color === '#12ab34'))
})
test('chat preview removes nested interactive tag attributes and preserves visible name', () => {
  const format = "<hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '><white><displayname></white></click></hover> <message>"
  const output = chatPreview(format, { player: 'Steve', message: '<red>literal' })
  assert.equal(plain(output), 'Steve <red>literal')
  assert.deepEqual(parseMinecraftText(output).warnings, [])
})
test('TAB PAPI title token and empty title have matching previews', () => {
  assert.equal(plain(chatPreview('%playerTitle_use% {player}', { title: '&b[先锋]', player: 'Steve' })), '[先锋] Steve')
  assert.equal(plain(chatPreview('{title} {player}', { showTitle: false, player: 'Steve' })), 'Steve')
  assert.equal(plain(chatPreview('{title} {player}', { title: '', player: 'Steve' })), 'Steve')
})
test('legacy title color remains scoped to title in chat and TAB previews', () => {
  for (const format of ['{title} {player}', '<aqua>[{world}]</aqua> {title} <displayname> » <message>']) {
    const output = parseMinecraftText(chatPreview(format, { title: '&d[探索者]', player: 'Steve', message: 'hello' }))
    assert.equal(output.segments.find(segment => segment.text.includes('[探索者]')).style.color, '#ff55ff')
    assert.notEqual(output.segments.find(segment => segment.text.includes('Steve')).style.color, '#ff55ff')
    assert.notEqual(output.segments.find(segment => segment.text.includes('hello'))?.style.color, '#ff55ff')
  }
})
test('world and player values remain literal instead of becoming preview markup', () => {
  assert.equal(plain(chatPreview('{world} {player}', { worldName: '<red>世界', player: '<click:test>', showTitle: false })), '<red>世界 <click:test>')
})
