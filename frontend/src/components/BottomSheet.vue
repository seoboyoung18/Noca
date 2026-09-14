<script setup>
defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <div v-if="modelValue" class="sheet">
    <div class="dim" @click="emit('update:modelValue', false)"></div>
    <div class="panel">
      <i class="grab"></i>
      <slot />
    </div>
  </div>
</template>
