<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ConfigSnapshot } from '../types'
import { configLabel, configOptions, configSections, configSource } from '../config-labels'
import ConfigChoice from './ConfigChoice.vue'
import ConfigFieldHelp from './ConfigFieldHelp.vue'
import Icon from './Icon.vue'

const props = defineProps<{
  modelValue: Record<string, string>; knownKeys: string[]; defaults?: Record<string, string>
  snapshot?: ConfigSnapshot; copying?: boolean; inherit?: boolean
}>()
const emit = defineEmits<{ set: [key: string, value: string]; remove: [key: string] }>()
const { t, locale } = useI18n()
const id = useId()
const view = ref<'specified' | 'all' | 'common' | 'review'>(props.snapshot ? 'specified' : 'common')
const search = ref(''), newKey = ref('')
const query = computed(() => search.value.trim().toLowerCase())
const keys = computed(() => [...new Set([...props.knownKeys, ...Object.keys(props.snapshot?.sources ?? {}),
  ...Object.keys(props.snapshot?.effective ?? {}), ...Object.keys(props.modelValue), ...(props.snapshot?.secretKeys ?? [])])])
function specified(key: string) {
  const source = props.snapshot?.sources[key]
  return Object.hasOwn(props.modelValue, key) || !!props.snapshot?.secretKeys.includes(key) || (!!source && source !== 'DEFAULT')
}
const specifiedCount = computed(() => keys.value.filter(specified).length)
// 仅根据已加载的配置快照提示，草稿和复制后的目标环境在保存后重新解析。
function reviewReason(key: string) {
  const saved = props.snapshot
  if (!saved || saved.error) return ''
  const value = saved.effective[key]?.toLowerCase()
  if (key === 'flydb.url' && !value) return t('catalogMissingUrl')
  if (key === 'flydb.clean-disabled' && value === 'false') return t('catalogCleanAllowed')
  if (key === 'flydb.validate-on-migrate' && value === 'false') return t('catalogValidateOff')
  if (key === 'flydb.baseline-on-migrate' && value === 'true') return t('catalogAutoBaseline')
  if (key === 'flydb.out-of-order' && value === 'true') return t('catalogOutOfOrder')
  if (saved.sources[key] === 'ENVIRONMENT' || saved.declaredAt?.[key]?.startsWith('FLYDB_')) return t('catalogEnvPriority')
  return ''
}
const reviewCount = computed(() => keys.value.filter(key => reviewReason(key)).length)
const groups = computed(() => {
  const matches = keys.value.map(key => ({ key, ...configLabel(key) })).filter(row => query.value
    ? [row.key, row.zh, row.en, row.hintZh, row.hintEn, row.example, ...configOptions(row.key, props.defaults).flat(), t('configGroups.' + row.section)].some(s => s.toLowerCase().includes(query.value))
    : view.value === 'review' ? !!reviewReason(row.key) : view.value !== 'specified' || specified(row.key))
  return configSections.map(section => ({ section, rows: matches.filter(row => row.section === section) }))
    .filter(group => group.rows.length)
})
function select(next: typeof view.value) { view.value = next; search.value = '' }
function displayedValue(key: string) {
  const snapshot = props.snapshot
  if (snapshot?.error) return t('configUnresolved')
  if (key === 'flydb.config-import' && snapshot?.values[key]) return snapshot.values[key]
  const value = snapshot ? snapshot.effective[key] : props.defaults?.[key]
  if (value !== undefined) return value
  if (snapshot?.sources[key] || snapshot?.secretKeys.includes(key)) return t('masked')
  if (['flydb.database-type', 'flydb.driver', 'flydb.driver-coordinate', 'flydb.version-selection'].includes(key)) return t('autoDetect')
  return t('catalogUnspecified')
}
function sensitive(key: string) {
  const value = props.modelValue[key] ?? ''
  return props.snapshot?.secretKeys.includes(key) || (/password|secret|token|credential/i.test(key) && !key.endsWith('.file') && !/^\$\{env:[^}]+\}$/.test(value))
    || (key === 'flydb.url' && /password|secret|token|credential|api[-_]?key|\/\/[^/\s]+:[^/\s]+@|thin:[^/@\s]+\/[^@\s]+@/i.test(props.modelValue[key] ?? ''))
}
function addCustom() {
  const key = newKey.value.trim()
  if (!key || keys.value.includes(key)) return
  emit('set', key, '')
  search.value = key; newKey.value = ''
}
</script>

<template>
  <section class="config-catalog" :aria-label="t('configCatalog')">
    <div class="catalog-tools">
      <div class="segmented catalog-views" role="group" :aria-label="t('configCatalog')">
        <button v-for="tab in (['specified', 'all', 'common'] as const)" :key="tab" type="button"
          :aria-pressed="!query && view === tab" @click="select(tab)">{{ t('configViews.' + tab) }}<span v-if="tab !== 'common'">{{ tab === 'all' ? keys.length : specifiedCount }}</span></button>
        <button v-if="snapshot && !snapshot.error" type="button" :aria-pressed="!query && view === 'review'" @click="select('review')">{{ t('catalogReview') }}<span>{{ reviewCount }}</span></button>
      </div>
      <label class="catalog-search"><Icon name="search" :size="16" /><input v-model="search" type="search" :aria-label="t('configSearch')" :placeholder="t('configSearch')" @keydown.enter.prevent /></label>
    </div>
    <slot v-if="view === 'common' && !query" />
    <template v-else>
      <p v-if="view === 'review' && !query" class="muted small catalog-scope">{{ t('catalogReviewScope') }}</p>
      <p v-else class="muted small catalog-scope">{{ t('catalogOverrideHelp') }}</p>
      <div class="catalog-list">
        <div class="catalog-columns catalog-head">
          <span>{{ t('catalogSetting') }}</span><span>{{ t(snapshot ? copying ? 'catalogSourceValue' : 'catalogEffective' : 'catalogDefault') }}</span><span>{{ t('catalogFileValue') }}</span>
        </div>
        <section v-for="group in groups" :key="group.section" class="catalog-group">
          <h4>{{ t('configGroups.' + group.section) }}</h4>
          <div v-for="row in group.rows" :key="row.key" class="catalog-columns catalog-row">
            <div><strong>{{ locale === 'en' ? row.en : row.zh }}</strong><code>{{ row.key }}</code>
              <ConfigFieldHelp :id="id + row.key" :config-key="row.key" :show-example="!configOptions(row.key, defaults).length" />
            </div>
            <div class="catalog-value"><span>{{ displayedValue(row.key) }}</span><small v-if="configSource(snapshot, row.key, t)">{{ configSource(snapshot, row.key, t) }}</small><small v-if="reviewReason(row.key)" class="catalog-reason">{{ reviewReason(row.key) }}</small></div>
            <div class="catalog-input">
              <small v-if="inherit && row.key === 'flydb.config-import'">{{ t('catalogAutoImport') }}</small>
              <ConfigChoice v-else-if="configOptions(row.key, defaults).length" :config-key="row.key" :options="configOptions(row.key, defaults)"
                :value="modelValue[row.key]" :help-id="id + row.key" @set="emit('set', row.key, $event)" @remove="emit('remove', row.key)" />
              <input v-else :aria-label="row.key" :aria-describedby="row.hintZh ? id + row.key : undefined" :type="sensitive(row.key) ? 'password' : 'text'" :value="modelValue[row.key] ?? ''"
                :placeholder="snapshot?.secretKeys.includes(row.key) ? t('masked') : t('catalogNoOverride')"
                autocomplete="off" :spellcheck="false" @input="emit('set', row.key, ($event.target as HTMLInputElement).value)" />
              <button v-if="!configOptions(row.key, defaults).length && Object.hasOwn(modelValue, row.key) && !(inherit && snapshot && Object.hasOwn(snapshot.values, row.key))" type="button" class="icon-button"
                :aria-label="t('removeField') + ' ' + row.key" @click="emit('remove', row.key)"><Icon name="close" :size="14" /></button>
            </div>
          </div>
        </section>
        <p v-if="!groups.length" class="muted small">{{ t(query ? 'catalogNoResults' : snapshot?.error ? 'configUnresolved' : view === 'review' ? 'catalogNoReview' : 'catalogNoneSpecified') }}</p>
      </div>
      <details class="catalog-custom"><summary>{{ t('catalogCustom') }}</summary><div class="path-bar">
        <input v-model="newKey" :aria-label="t('key')" placeholder="flydb.placeholders.schema" @keydown.enter.prevent="addCustom" />
        <button type="button" class="button" :disabled="!newKey.trim() || keys.includes(newKey.trim())" @click="addCustom">{{ t('addField') }}</button>
      </div></details>
    </template>
  </section>
</template>
