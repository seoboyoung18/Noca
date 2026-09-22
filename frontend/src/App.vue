<template>
  <!-- PC 에서는 폰 프레임(360x800)만 가운데에, 모바일에서는 프레임 없이 전체 화면 (styles.css 의 .shell/.stage/.phone) -->
  <div class="shell">
    <main class="stage">
      <div class="phone">
        <router-view />
      </div>
    </main>
  </div>
</template>
