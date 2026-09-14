<script setup>
import CheckBox from './CheckBox.vue'
defineProps({ item: Object, editing: Boolean })
const emit = defineEmits(['toggle', 'edit'])
</script>

<template>
  <div class="ci" :class="{ on: item.done, editing }" @click="!editing && emit('toggle')">
    <CheckBox :on="item.done" />
    <span class="tx">
      <span class="t">{{ item.text }}</span>
      <span v-if="item.why" class="why">{{ item.why }}</span>
      <span v-if="item.memo" class="memo">✎ {{ item.memo }}</span>
    </span>
    <button v-if="editing" class="edit" aria-label="항목 편집" @click.stop="emit('edit')">
      <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M12.5 3.5l4 4L7 17H3v-4z" stroke="#B0B8C1" stroke-width="1.6" stroke-linejoin="round"/><path d="M10.5 5.5l4 4" stroke="#B0B8C1" stroke-width="1.6"/></svg>
    </button>
  </div>
</template>

<style scoped>
.ci { padding: 14px 0; border-bottom: 1px solid var(--line); display: flex; align-items: flex-start; gap: 12px; cursor: pointer; }
.ci.editing { cursor: default; }
.ci:last-child { border-bottom: 0; }
.tx { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; }
.t { font-size: 14px; line-height: 1.5; color: var(--text); }
.ci.on .t { color: var(--text-3); text-decoration: line-through; text-decoration-color: var(--line-2); }
.why { margin-top: 3px; font-size: 12px; color: var(--text-3); }
.why::before { content: "↳ "; color: var(--line-2); }
.memo { margin-top: 3px; font-size: 12px; color: var(--primary); }
.edit { flex: 0 0 28px; width: 28px; height: 28px; margin: -3px -6px 0 0; display: flex; align-items: center; justify-content: center; border-radius: 14px; }
.edit:hover { background: var(--bg); }
</style>
