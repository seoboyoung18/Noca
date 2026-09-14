<script setup>
defineProps({ show: Boolean })
</script>

<template>
  <div class="toast" :class="{ on: show }"><slot /></div>
</template>
