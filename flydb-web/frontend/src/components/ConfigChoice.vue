<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { configOptions } from '../config-labels'
const props = defineProps<{ configKey: string; value?: string; options: ReturnType<typeof configOptions>; helpId: string }>()
const emit = defineEmits<{ set: [value: string]; remove: [] }>()
const { locale, t } = useI18n()
const editing = ref(false), input = ref<HTMLInputElement>()
// 进入手动输入时保留原值，只有输入或选择具体值才修改草稿。
const custom = computed(() => editing.value || (props.value !== undefined && !props.options.some(([v]) => v === props.value)))
function choose(event: Event) {
  const choice = (event.target as HTMLSelectElement).value
  editing.value = choice === '@custom'
  if (editing.value) nextTick(() => input.value?.focus())
  else if (choice === '@inherit') emit('remove')
  else emit('set', choice)
}
</script>
<template>
  <div class="config-choice">
    <select :aria-label="configKey" :aria-describedby="helpId" :value="custom ? '@custom' : value ?? '@inherit'" @change="choose">
      <option value="@inherit">{{ t('catalogNoOverride') }}</option>
      <option v-for="[v, zh, en] in options" :key="v" :value="v">{{ v }} · {{ locale === 'en' ? en : zh }}</option>
      <option value="@custom">{{ t('customConfigValue') }}</option>
    </select>
    <input v-if="custom" ref="input" type="text" :aria-label="t('customConfigValue') + ' ' + configKey" :aria-describedby="helpId"
      :value="value ?? ''" placeholder="${env:VAR}" autocomplete="off" :spellcheck="false" @input="emit('set', ($event.target as HTMLInputElement).value)" />
  </div>
</template>
