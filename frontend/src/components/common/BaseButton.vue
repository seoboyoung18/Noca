<script setup>
defineProps({
  disabled: {
    type: Boolean,
    default: false,
  },
})
</script>

<template>
  <button type="button" class="base-button" :disabled="disabled">
    <slot />
  </button>
</template>

<style scoped>
.base-button {
  width: 100%;
  height: var(--size-button-height);
  border: none;
  border-radius: var(--radius-button);
  background: var(--color-brand-500);
  color: #ffffff;
  font-family: var(--font-family-base);
  font-size: var(--font-size-md);
  font-weight: 600;
  cursor: pointer;
}

.base-button:hover:not(:disabled),
.base-button:active:not(:disabled) {
  background: var(--color-brand-600);
}

.base-button:disabled {
  background: var(--color-disabled-bg);
  color: var(--color-disabled-text);
  cursor: not-allowed;
}
</style>
