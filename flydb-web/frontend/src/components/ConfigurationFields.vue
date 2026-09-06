<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import JdbcEditor from './JdbcEditor.vue'
import Icon from './Icon.vue'
const props = defineProps<{ modelValue: Record<string, string>; knownKeys: string[]; secretKeys?: string[]; maskedUrl?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, string>] }>()
const { t } = useI18n()
const newKey = ref(''), replacingUrl = ref(false)
const basic = [{ key: 'flydb.user', label: 'databaseUser' }, { key: 'flydb.password', label: 'passwordRef' }, { key: 'flydb.password.file', label: 'passwordFile' }, { key: 'flydb.locations', label: 'scriptLocation' }]
const drivers = [{ key: 'flydb.database-type', label: 'databaseType' }, { key: 'flydb.driver', label: 'driver' }, { key: 'flydb.driver-coordinate', label: 'driverCoordinate' }]
const standard = ['flydb.url', ...basic.map(f => f.key), ...drivers.map(f => f.key)]
function set(key: string, value: string) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
function remove(key: string) { const next = { ...props.modelValue }; delete next[key]; emit('update:modelValue', next) }
function hidden(key: string) { return /password|secret|token|credential/i.test(key) && !key.endsWith('.file') && !props.modelValue[key]?.startsWith('${env:') }
</script>
<template>
  <div class="configuration-fields">
    <div class="form-grid">
      <div v-if="secretKeys?.includes('flydb.url') && !modelValue['flydb.url'] && !replacingUrl" class="field wide"><span>{{ t('jdbcUrl') }}</span><code>{{ maskedUrl }}</code><small>{{ t('hiddenUrlHint') }}</small><button class="button" type="button" @click="replacingUrl = true">{{ t('replaceUrl') }}</button></div>
      <JdbcEditor v-else :model-value="modelValue['flydb.url'] || ''" @update:model-value="set('flydb.url', $event)" @protocol="set('flydb.database-type', $event)" />
      <label v-for="field in basic" :key="field.key" class="field" :class="{ wide: field.key === 'flydb.locations' }"><span>{{ t(field.label) }}</span><input :type="hidden(field.key) ? 'password' : 'text'" :value="modelValue[field.key]" :placeholder="secretKeys?.includes(field.key) ? t('masked') : field.key === 'flydb.locations' ? 'filesystem:db/migration' : ''" autocomplete="off" :spellcheck="false" @input="set(field.key, ($event.target as HTMLInputElement).value)" /><small v-if="field.key === 'flydb.password'">{{ t('passwordHint') }}</small><small v-if="field.key === 'flydb.locations'">{{ t('locationsHint') }}</small></label>
    </div>
    <details class="settings-section"><summary>{{ t('driverSettings') }}<Icon name="chevron" /></summary><div class="form-grid">
      <label v-for="field in drivers" :key="field.key" class="field"><span>{{ t(field.label) }}</span><input :value="modelValue[field.key]" :list="field.key === 'flydb.database-type' ? 'database-dialects' : undefined" :placeholder="field.key === 'flydb.database-type' ? t('autoDetect') : ''" :spellcheck="false" @input="set(field.key, ($event.target as HTMLInputElement).value)" /></label>
      <datalist id="database-dialects"><option v-for="db in ['mysql', 'postgresql', 'oracle', 'dm', 'kingbasees', 'opengauss', 'oceanbase', 'tidb']" :key="db" :value="db" /></datalist>
    </div></details>
    <details class="settings-section"><summary>{{ t('advancedSettings') }}<Icon name="chevron" /></summary><div class="key-values">
      <div v-for="key in Object.keys(modelValue).filter(key => !standard.includes(key))" :key="key" class="key-row"><label><code>{{ key }}</code><input :type="hidden(key) ? 'password' : 'text'" :value="modelValue[key]" :aria-label="key" :spellcheck="false" @input="set(key, ($event.target as HTMLInputElement).value)" /></label><button type="button" class="icon-button" :aria-label="`${t('removeField')} ${key}`" @click="remove(key)"><Icon name="close" /></button></div>
      <div class="path-bar"><input v-model="newKey" list="configuration-keys" :aria-label="t('key')" placeholder="flydb." /><datalist id="configuration-keys"><option v-for="key in knownKeys.filter(k => !(k in modelValue))" :key="key" :value="key" /></datalist><button class="button" type="button" :disabled="!newKey.trim() || newKey.trim() in modelValue" @click="set(newKey.trim(), ''); newKey = ''"><Icon name="plus" />{{ t('addField') }}</button></div>
    </div></details>
  </div>
</template>
