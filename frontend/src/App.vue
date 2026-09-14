<script setup>
import DevSidebar from './components/DevSidebar.vue'
</script>

<template>
  <div class="shell">
    <DevSidebar />
    <main class="stage">
      <div class="phone">
        <router-view />
      </div>
    </main>
  </div>
</template>
