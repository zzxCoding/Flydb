<script setup lang="ts">
import { ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ConfigSnapshot } from '../types'
import ConfigCatalog from './ConfigCatalog.vue'
import ConfigFieldHelp from './ConfigFieldHelp.vue'
import JdbcEditor from './JdbcEditor.vue'
import Icon from './Icon.vue'
const props = defineProps<{ modelValue: Record<string, string>; knownKeys: string[]; snapshot?: ConfigSnapshot; defaults?: Record<string, string>; copying?: boolean; inherit?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, string>] }>()
const { t } = useI18n()
const id = useId()
const replacingUrl = ref(false)
const basic = [{ key: 'flydb.user', label: 'databaseUser' }, { key: 'flydb.password', label: 'passwordRef' }, { key: 'flydb.password.file', label: 'passwordFile' }, { key: 'flydb.locations', label: 'scriptLocation' }]
const drivers = [{ key: 'flydb.database-type', label: 'databaseType' }, { key: 'flydb.driver', label: 'driver' }, { key: 'flydb.driver-coordinate', label: 'driverCoordinate' }]
function set(key: string, value: string) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
function remove(key: string) { const next = { ...props.modelValue }; delete next[key]; emit('update:modelValue', next) }
function hidden(key: string) { return /password|secret|token|credential/i.test(key) && !key.endsWith('.file') && !props.modelValue[key]?.startsWith('${env:') }
</script>
<template>
  <ConfigCatalog class="configuration-fields" :model-value="modelValue" :known-keys="knownKeys" :defaults="defaults"
    :snapshot="snapshot" :copying="copying" :inherit="inherit" @set="set" @remove="remove">
    <div class="form-grid">
      <div v-if="(snapshot?.secretKeys.includes('flydb.url') || snapshot?.effective['flydb.url']) && !modelValue['flydb.url'] && !replacingUrl" class="field wide"><span>{{ t('jdbcUrl') }}</span><code>{{ snapshot?.effective['flydb.url'] }}</code><small v-if="snapshot?.secretKeys.includes('flydb.url')">{{ t('hiddenUrlHint') }}</small><button class="button" type="button" @click="replacingUrl = true">{{ t('replaceUrl') }}</button></div>
      <JdbcEditor v-else :model-value="modelValue['flydb.url'] || ''" @update:model-value="set('flydb.url', $event)" @protocol="set('flydb.database-type', $event)" />
      <div v-for="field in basic" :key="field.key" class="field" :class="{ wide: field.key === 'flydb.locations' }">
        <label :for="id + field.key">{{ t(field.label) }}</label>
        <input :id="id + field.key" :aria-describedby="id + field.key + '-help'" :type="hidden(field.key) ? 'password' : 'text'" :value="modelValue[field.key]"
          :placeholder="snapshot?.secretKeys.includes(field.key) ? t('masked') : undefined" autocomplete="off" :spellcheck="false"
          @input="set(field.key, ($event.target as HTMLInputElement).value)" />
        <ConfigFieldHelp :id="id + field.key + '-help'" :config-key="field.key" />
      </div>
    </div>
    <details class="settings-section"><summary>{{ t('driverSettings') }}<Icon name="chevron" /></summary><div class="form-grid">
      <div v-for="field in drivers" :key="field.key" class="field">
        <label :for="id + field.key">{{ t(field.label) }}</label>
        <input :id="id + field.key" :aria-describedby="id + field.key + '-help'" :value="modelValue[field.key]"
          :list="field.key === 'flydb.database-type' ? id + '-dialects' : undefined" :placeholder="field.key === 'flydb.database-type' ? t('autoDetect') : ''"
          :spellcheck="false" @input="set(field.key, ($event.target as HTMLInputElement).value)" />
        <ConfigFieldHelp :id="id + field.key + '-help'" :config-key="field.key" />
      </div>
      <datalist :id="id + '-dialects'"><option v-for="db in ['mysql', 'postgresql', 'oracle', 'dm', 'kingbasees', 'opengauss', 'oceanbase', 'tidb']" :key="db" :value="db" /></datalist>
    </div></details>
  </ConfigCatalog>
</template>
