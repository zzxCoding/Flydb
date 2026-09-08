<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
const props = defineProps<{ modelValue: number; total: number; size: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()
const { t } = useI18n()
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
function jump(event: Event) {
  const input = event.target as HTMLInputElement, value = Number(input.value)
  const page = Number.isFinite(value) ? Math.max(0, Math.min(pages.value - 1, Math.floor(value) - 1)) : props.modelValue
  emit('update:modelValue', page); input.value = String(page + 1)
}
</script>
<template><div class="pagination"><span>{{ t('itemRange', { start: total ? modelValue * size + 1 : 0, end: Math.min(total, (modelValue + 1) * size), total }) }}</span><div v-if="pages > 1"><button class="text-button" :disabled="modelValue === 0" @click="emit('update:modelValue', modelValue - 1)">{{ t('previousPage') }}</button><input type="number" :aria-label="t('pageNumber')" min="1" :max="pages" :value="modelValue + 1" @change="jump"><span>/ {{ pages }}</span><button class="text-button" :disabled="modelValue + 1 >= pages" @click="emit('update:modelValue', modelValue + 1)">{{ t('nextPage') }}</button></div></div></template>
