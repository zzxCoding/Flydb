import { computed, ref } from 'vue'
export interface ConfigDocument { content: string; values: Record<string, string>; revision: string }
export type ConvertDocument = (input: { content: string; values?: Record<string, string | null>; validate: boolean }) => Promise<ConfigDocument>
export function changes(base: Record<string, string>, draft: Record<string, string>) {
  const result: Record<string, string | null> = {}
  for (const key of new Set([...Object.keys(base), ...Object.keys(draft)]))
    if (base[key] !== draft[key]) result[key] = draft[key] ?? null
  return result
}

/** The source stays authoritative after it is opened; form edits patch it through Java Properties. */
export function useConfigDraft(initial: Record<string, string>, load: () => Promise<ConfigDocument>, convert: ConvertDocument) {
  const fields = ref({ ...initial }), baseFields = ref({ ...initial }), synced = ref({ ...initial })
  const content = ref(''), baseline = ref(''), loaded = ref(false), mode = ref<'form' | 'file'>('form')
  const fieldChanges = computed(() => changes(loaded.value ? synced.value : baseFields.value, fields.value))
  const dirty = computed(() => (loaded.value && content.value !== baseline.value) || Object.keys(fieldChanges.value).length > 0)
  async function document(validate = true) {
    let source = content.value
    let edits = mode.value === 'form' ? fieldChanges.value : undefined
    if (!loaded.value) {
      const original = await load()
      source = original.content
      const result = await convert({ content: source, values: edits, validate })
      baseline.value = source; loaded.value = true
      content.value = result.content; fields.value = { ...result.values }; synced.value = { ...result.values }
      return result
    }
    const result = await convert({ content: source, values: edits, validate })
    content.value = result.content; fields.value = { ...result.values }; synced.value = { ...result.values }
    return result
  }
  async function switchMode(next: 'form' | 'file') {
    if (next === mode.value) return
    await document(false)
    mode.value = next
  }
  function reset(values: Record<string, string>) {
    fields.value = { ...values }; baseFields.value = { ...values }; synced.value = { ...values }
    content.value = ''; baseline.value = ''; loaded.value = false; mode.value = 'form'
  }
  function accept(value: ConfigDocument) {
    content.value = value.content; baseline.value = value.content
    fields.value = { ...value.values }; synced.value = { ...value.values }; baseFields.value = { ...value.values }; loaded.value = true
  }
  function rebase(value: ConfigDocument) { baseline.value = value.content }
  return { fields, content, baseline, loaded, mode, dirty, fieldChanges, document, switchMode, reset, accept, rebase }
}
