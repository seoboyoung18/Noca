<script setup>
defineProps({
  title: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['back'])
</script>

<template>
  <header class="app-header">
    <button type="button" class="back-button" aria-label="뒤로가기" @click="emit('back')">
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
        <path
          d="M12.5 15L7.5 10L12.5 5"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </button>
    <h1 class="title">{{ title }}</h1>
    <div class="spacer" aria-hidden="true"></div>
  </header>
</template>

<style scoped>
.app-header {
  display: grid;
  grid-template-columns: var(--size-header-height) 1fr var(--size-header-height);
  align-items: center;
  height: var(--size-header-height);
  border-bottom: 1px solid var(--color-border);
}

.back-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--size-header-height);
  height: var(--size-header-height);
  border: none;
  background: none;
  color: var(--color-text);
  cursor: pointer;
}

.title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--color-text);
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spacer {
  width: var(--size-header-height);
  height: var(--size-header-height);
}
</style>
