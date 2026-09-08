<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { EditorState, Compartment } from '@codemirror/state'
import { useI18n } from 'vue-i18n'
import { EditorView } from '@codemirror/view'
import { basicSetup } from 'codemirror'
const props = defineProps<{ text: string; label: string }>()
const root = ref<HTMLDivElement>()
const { locale } = useI18n()
const language = new Compartment()
const phrases = { Find: '查找', next: '下一个', previous: '上一个', all: '全部', 'match case': '区分大小写', regexp: '正则表达式', 'by word': '全词匹配', close: '关闭', 'Go to line': '跳转到行', go: '跳转' }
function localized() { return [EditorState.phrases.of(locale.value === 'zh-CN' ? phrases : {}), EditorView.contentAttributes.of({ 'aria-label': props.label, tabindex: '0' })] }
let editor: EditorView | undefined
onMounted(() => {
  editor = new EditorView({ parent: root.value, state: EditorState.create({ doc: props.text, extensions: [
    basicSetup, EditorState.readOnly.of(true), EditorView.editable.of(false),
    language.of(localized()),
    EditorView.theme({ '&': { height: '100%', fontSize: '12px' }, '.cm-scroller': { overflow: 'auto', fontFamily: 'monospace' },
      '.cm-content': { color: 'var(--text)' }, '.cm-gutters': { backgroundColor: 'var(--surface)', color: 'var(--muted)', borderColor: 'var(--line)' },
      '.cm-activeLine': { backgroundColor: 'transparent' }, '.cm-panels': { backgroundColor: 'var(--surface)', color: 'var(--text)' },
      '.cm-textfield': { backgroundColor: 'var(--canvas)', color: 'var(--text)', borderColor: 'var(--line)' },
      '.cm-button': { background: 'var(--surface)', color: 'var(--text)', borderColor: 'var(--line)' } }),
  ] }) })
})
watch(() => props.text, text => {
  if (editor) { editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: text }, selection: { anchor: 0 }, scrollIntoView: true }); editor.scrollDOM.scrollTop = 0 }
})
watch([locale, () => props.label], () => editor?.dispatch({ effects: language.reconfigure(localized()) }))
onBeforeUnmount(() => editor?.destroy())
</script>
<template><div ref="root" class="sql-editor"></div></template>
