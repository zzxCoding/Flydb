<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { configLabel } from '../config-labels'
const props = defineProps<{ configKey: string; showExample?: boolean }>()
const { locale, t } = useI18n()
const field = computed(() => configLabel(props.configKey))
</script>
<template>
  <small v-if="field.hintZh" class="config-field-help">
    {{ locale === 'en' ? field.hintEn : field.hintZh }}
    <span v-if="showExample !== false && field.example" class="field-example">{{ t('fieldExample') }} <code>{{ field.example }}</code></span>
  </small>
</template>
