<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { basicSetup } from 'codemirror'
import { EditorState, Compartment } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'
import { StreamLanguage, syntaxHighlighting, HighlightStyle } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import { properties } from '@codemirror/legacy-modes/mode/properties'
import { autocompletion } from '@codemirror/autocomplete'
import { configLabel, configOptions } from '../config-labels'
import ConfigFieldHelp from './ConfigFieldHelp.vue'
const props = defineProps<{ modelValue: string; knownKeys: string[]; defaults?: Record<string, string>; disabled?: boolean; readonly?: boolean; label?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; save: [] }>()
const { t, locale } = useI18n()
const root = ref<HTMLDivElement>(), lines = ref(1), line = ref(1), column = ref(1), wrapped = ref(false)
// 仅识别当前行开头的配置键，说明面板不读取或显示用户填写的值。
function fieldAt(text: string) { return /^\s*(flydb\.[\w.-]+)(?:\s|[=:]|$)/.exec(text)?.[1] ?? '' }
const currentKey = ref('')
const currentField = computed(() => configLabel(currentKey.value))
const choices = computed(() => configOptions(currentKey.value, props.defaults))
const wrapping = new Compartment(), access = new Compartment(), language = new Compartment()
let editor: EditorView | undefined
function editable() { return [EditorState.readOnly.of(!!props.readonly || !!props.disabled), EditorView.editable.of(!props.readonly && !props.disabled)] }
const phrases: Record<string, string> = { 'Find': '查找', 'Replace': '替换', 'next': '下一个', 'previous': '上一个', 'all': '全部', 'match case': '区分大小写', 'regexp': '正则表达式', 'by word': '全词匹配', 'replace': '替换', 'replace all': '全部替换', 'close': '关闭', 'Go to line': '跳转到行', 'go': '跳转', 'Control character': '控制字符' }
function localized() { return [EditorState.phrases.of(locale.value === 'zh-CN' ? phrases : {}), EditorView.contentAttributes.of({ 'aria-label': props.label || t('fileContent'), spellcheck: 'false' })] }
onMounted(() => {
  editor = new EditorView({ parent: root.value, state: EditorState.create({ doc: props.modelValue, extensions: [
    basicSetup, StreamLanguage.define(properties), wrapping.of([]), access.of(editable()),
    syntaxHighlighting(HighlightStyle.define([{ tag: tags.comment, color: 'var(--muted)' }, { tag: [tags.propertyName, tags.definition(tags.variableName)], color: 'var(--accent)' }, { tag: [tags.string, tags.variableName], color: 'var(--text)' }, { tag: [tags.atom, tags.number], color: 'var(--warning)' }])),
    language.of(localized()),
    EditorState.lineSeparator.of(props.modelValue.includes('\r\n') ? '\r\n' : props.modelValue.includes('\r') ? '\r' : '\n'),
    keymap.of([{ key: 'Mod-s', run: () => { if (!props.readonly && !props.disabled) emit('save'); return true } }]),
    autocompletion({ override: [context => {
      const word = context.matchBefore(/[\w.-]*/)
      const prefix = context.state.doc.lineAt(context.pos).text.slice(0, context.pos - context.state.doc.lineAt(context.pos).from)
      if (!word || (!context.explicit && !word.text) || !/^\s*[\w.-]*$/.test(prefix)) return null
      return { from: word.from, options: props.knownKeys.map(label => {
        const field = configLabel(label)
        return { label, type: 'property', detail: locale.value === 'en' ? field.en : field.zh }
      }) }
    }] }),
    EditorView.updateListener.of(update => {
      const pos = update.state.selection.main.head, current = update.state.doc.lineAt(pos)
      lines.value = update.state.doc.lines; line.value = current.number; column.value = pos - current.from + 1
      currentKey.value = fieldAt(current.text)
      if (update.docChanged) emit('update:modelValue', update.state.sliceDoc())
    }),
  ] }) })
  lines.value = editor.state.doc.lines
  currentKey.value = fieldAt(editor.state.doc.line(1).text)
})
watch(() => props.modelValue, value => {
  if (editor && value !== editor.state.sliceDoc()) editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } })
})
watch(() => [props.disabled, props.readonly], () => editor?.dispatch({ effects: access.reconfigure(editable()) }))
watch([locale, () => props.label], () => editor?.dispatch({ effects: language.reconfigure(localized()) }))
watch(wrapped, value => editor?.dispatch({ effects: wrapping.reconfigure(value ? EditorView.lineWrapping : []) }))
onBeforeUnmount(() => editor?.destroy())
</script>
<template>
  <section class="source-editor" :aria-label="label || t('fileContent')">
    <header class="source-toolbar"><span><span class="file-dot"></span>flydb.conf <small>UTF-8 · Properties</small></span><button type="button" class="text-button" :aria-pressed="wrapped" @click="wrapped = !wrapped">{{ t('wrapLines') }}</button></header>
    <div ref="root" class="code-editor"></div>
    <aside v-if="currentField.hintZh" class="source-field-help">
      <strong>{{ locale === 'en' ? currentField.en : currentField.zh }}</strong>
      <ConfigFieldHelp :config-key="currentKey" :show-example="!choices.length" />
      <small v-if="choices.length" class="field-example">{{ choices.map(([v, zh, en]) => v + ' · ' + (locale === 'en' ? en : zh)).join(' / ') }}</small>
    </aside>
    <footer class="source-status"><span>{{ t('editorPosition', { line, column }) }} <span class="muted">· {{ t('editorLines', { count: lines }) }}</span></span><span>{{ t('editorShortcut') }}</span></footer>
  </section>
</template>
