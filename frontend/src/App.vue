<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ChatView from './components/ChatView.vue'
import SemanticView from './components/SemanticView.vue'
import EvalView from './components/EvalView.vue'
import AuditView from './components/AuditView.vue'
import type { AppUser } from './types'
import { api } from './api'

type ViewKey = 'chat' | 'semantic' | 'eval' | 'audit'

const views: { key: ViewKey; label: string }[] = [
  { key: 'chat', label: '问数' },
  { key: 'semantic', label: '语义层' },
  { key: 'eval', label: '评测' },
  { key: 'audit', label: '审计' },
]

function initialView(): ViewKey {
  const hash = window.location.hash.replace('#', '') as ViewKey
  return views.some((item) => item.key === hash) ? hash : 'chat'
}

const view = ref<ViewKey>(initialView())
const users = ref<AppUser[]>([])
const selected = ref(localStorage.getItem('chatbi.user') ?? 'zhangsan')
const theme = ref(localStorage.getItem('chatbi.theme')
  ?? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'))

const currentUser = computed(() => {
  const user = users.value.find((item) => item.username === selected.value)
  return user?.username ?? selected.value
})

const currentUserInfo = computed(() => users.value.find((item) => item.username === currentUser.value))
const isAdmin = computed(() => Boolean(currentUserInfo.value?.admin))

function navigate(next: ViewKey) {
  view.value = next
  history.replaceState(null, '', `#${next}`)
}

function onHashChange() {
  view.value = initialView()
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
}

watch(theme, (value) => {
  document.documentElement.dataset.theme = value
  localStorage.setItem('chatbi.theme', value)
}, { immediate: true })

watch(selected, (value) => {
  localStorage.setItem('chatbi.user', value)
})

onMounted(async () => {
  window.addEventListener('hashchange', onHashChange)
  try {
    users.value = await api.get<AppUser[]>('/api/users')
    if (!users.value.some((user) => user.username === selected.value)) {
      selected.value = users.value[0]?.username ?? 'zhangsan'
    }
  } catch {
    users.value = []
  }
})

onBeforeUnmount(() => window.removeEventListener('hashchange', onHashChange))
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <button class="brand" type="button" style="border: 0; background: transparent; padding: 0; text-align: left"
        @click="navigate('chat')">
        <span class="brand-mark">BI</span>
        <span>
          <span class="brand-name">ChatBI</span>
          <span class="brand-sub">企业数据智能问答</span>
        </span>
      </button>

      <nav class="nav" aria-label="主导航">
        <button
          v-for="item in views"
          :key="item.key"
          type="button"
          :class="{ active: view === item.key }"
          @click="navigate(item.key)"
        >
          {{ item.label }}
        </button>
      </nav>

      <div class="top-actions">
        <label class="user-select" title="演示身份决定行级权限">
          <span>身份</span>
          <select v-model="selected" aria-label="切换用户">
            <option v-for="user in users" :key="user.username" :value="user.username">
              {{ user.displayName }}
            </option>
            <option v-if="!users.length" :value="selected">{{ selected }}</option>
          </select>
        </label>
        <button class="icon-btn" type="button" :aria-label="theme === 'dark' ? '切换浅色模式' : '切换深色模式'"
          @click="toggleTheme">
          <svg v-if="theme === 'light'" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M13.5 9.6A6 6 0 0 1 6.4 2.5 5.5 5.5 0 1 0 13.5 9.6Z"
              stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <circle cx="8" cy="8" r="3" stroke="currentColor" stroke-width="1.4" />
            <path d="M8 1.4v1.8M8 12.8v1.8M1.4 8h1.8M12.8 8h1.8M3.3 3.3l1.3 1.3M11.4 11.4l1.3 1.3M12.7 3.3l-1.3 1.3M4.6 11.4l-1.3 1.3"
              stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          </svg>
        </button>
      </div>
    </header>

    <main class="view" :class="{ 'chat-mode': view === 'chat' }">
      <ChatView
        v-show="view === 'chat'"
        :key="currentUser"
        :user="currentUser"
        :theme="theme"
      />
      <SemanticView v-if="view === 'semantic'" :key="currentUser" :user="currentUser" :is-admin="isAdmin" />
      <EvalView v-if="view === 'eval'" :key="currentUser" :user="currentUser" :is-admin="isAdmin" />
      <AuditView v-if="view === 'audit'" :key="currentUser" :user="currentUser" :is-admin="isAdmin" />
    </main>
  </div>
</template>
