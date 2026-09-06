<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseJdbc, formatJdbc, type JdbcParts } from '../jdbc'
const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; protocol: [value: string] }>()
const { t } = useI18n()
const defaults = { prefix: 'jdbc:mysql://', host: 'localhost', port: '3306', database: 'app', suffix: '' }
const raw = ref(!!props.modelValue && !parseJdbc(props.modelValue))
const parts = ref<JdbcParts>(parseJdbc(props.modelValue) ?? defaults)
const recognized = computed(() => !!parseJdbc(props.modelValue))
const isOracle = computed(() => parts.value.prefix.startsWith('jdbc:oracle:'))
const engineName = computed(() => isOracle.value ? 'oracle' : parts.value.prefix.slice(5, -3))
const databaseLabel = computed(() => isOracle.value ? (parts.value.separator === ':' ? 'oracleSid' : 'oracleService') : 'database')
watch(() => props.modelValue, value => { const parsed = parseJdbc(value); if (parsed) parts.value = parsed })
onMounted(() => { if (!props.modelValue) emit('update:modelValue', formatJdbc(parts.value)) })
function update(field: keyof JdbcParts, value: string) {
  parts.value = { ...parts.value, [field]: value }
  emit('update:modelValue', formatJdbc(parts.value))
}
function engine(value: string) {
  const ports: Record<string, string> = { mysql: '3306', postgresql: '5432', oracle: '1521', dm: '5236', kingbase8: '54321', opengauss: '5432' }
  parts.value = { ...parts.value, prefix: value === 'oracle' ? 'jdbc:oracle:thin:@//' : `jdbc:${value}://`, port: ports[value] ?? '', separator: '/', suffix: '' }
  emit('update:modelValue', formatJdbc(parts.value))
  emit('protocol', value === 'kingbase8' ? 'kingbasees' : value)
}
function oracleMode(value: string) {
  parts.value = { ...parts.value, prefix: value === 'sid' ? 'jdbc:oracle:thin:@' : 'jdbc:oracle:thin:@//', separator: value === 'sid' ? ':' : '/' }
  emit('update:modelValue', formatJdbc(parts.value))
}
</script>
<template><div class="jdbc-editor wide">
  <label v-if="!raw" class="field"><span>{{ t('connectionType') }}</span><select :value="engineName" @change="engine(($event.target as HTMLSelectElement).value)"><option value="mysql">MySQL / TiDB / OceanBase MySQL</option><option value="postgresql">PostgreSQL</option><option value="oracle">Oracle</option><option value="dm">Dameng DM</option><option value="kingbase8">KingbaseES</option><option value="opengauss">openGauss</option></select></label>
  <label v-if="!raw && isOracle" class="field"><span>{{ t('oracleMode') }}</span><select :value="parts.separator === ':' ? 'sid' : 'service'" @change="oracleMode(($event.target as HTMLSelectElement).value)"><option value="service">{{ t('oracleService') }}</option><option value="sid">{{ t('oracleSid') }}</option></select></label>
  <div v-if="!raw" class="jdbc-fields"><label class="field"><span>{{ t('host') }}</span><input :value="parts.host" required @input="update('host', ($event.target as HTMLInputElement).value)" /></label><label class="field"><span>{{ t('port') }}</span><input :value="parts.port" inputmode="numeric" pattern="[0-9]*" @input="update('port', ($event.target as HTMLInputElement).value)" /></label><label class="field"><span>{{ t(databaseLabel) }}</span><input :value="parts.database" required @input="update('database', ($event.target as HTMLInputElement).value)" /></label></div>
  <label v-if="raw" class="field"><span>{{ t('jdbcUrl') }}</span><input :value="modelValue" required :spellcheck="false" placeholder="jdbc:oracle:thin:@//localhost:1521/service" @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)" /></label>
  <code v-else class="jdbc-address">{{ modelValue }}</code>
  <button v-if="!raw || recognized" class="text-button" type="button" @click="raw = !raw">{{ t(raw ? 'easyConnection' : 'rawUrl') }}</button>
</div></template>
