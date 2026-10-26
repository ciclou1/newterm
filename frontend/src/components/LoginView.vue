<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ApiError, api, describeError } from '../api'
import type { LoginResponse } from '../api'

const props = defineProps<{ theme: string; notice?: string }>()
const emit = defineEmits<{
  (event: 'logged-in', value: LoginResponse): void
  (event: 'toggle-theme'): void
}>()

const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const usernameInput = ref<HTMLInputElement | null>(null)
const isDev = import.meta.env.DEV

function loginErrorMessage(caught: unknown): string {
  if (caught instanceof ApiError) {
    if (caught.kind === 'rate_limit' && caught.retryAfterSeconds) {
      return `登录尝试过于频繁，请 ${caught.retryAfterSeconds} 秒后再试。`
    }
    // The login endpoint returns a safe, user-facing message for bad
    // credentials; everything else is normalised to avoid leaking internals.
    if (caught.status === 401 || caught.status === 403) return caught.message
    return describeError(caught)
  }
  return describeError(caught)
}

async function submit() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  busy.value = true
  try {
    const result = await api.login(username.value.trim(), password.value)
    emit('logged-in', result)
  } catch (caught) {
    error.value = loginErrorMessage(caught)
    await nextTick()
    if (!username.value.trim()) usernameInput.value?.focus()
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  usernameInput.value?.focus()
})
</script>

<template>
  <div class="login-shell">
    <header class="login-topbar">
      <div class="brand">
        <span class="brand-mark">BI</span>
        <span>
          <span class="brand-name">ChatBI</span>
          <span class="brand-sub">企业数据智能问答</span>
        </span>
      </div>
      <button
        class="icon-btn"
        type="button"
        :aria-label="props.theme === 'dark' ? '切换浅色模式' : '切换深色模式'"
        @click="emit('toggle-theme')"
      >
        <svg v-if="props.theme === 'light'" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M13.5 9.6A6 6 0 0 1 6.4 2.5 5.5 5.5 0 1 0 13.5 9.6Z"
            stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
        </svg>
        <svg v-else width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="3" stroke="currentColor" stroke-width="1.4" />
          <path d="M8 1.4v1.8M8 12.8v1.8M1.4 8h1.8M12.8 8h1.8M3.3 3.3l1.3 1.3M11.4 11.4l1.3 1.3M12.7 3.3l-1.3 1.3M4.6 11.4l-1.3 1.3"
            stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        </svg>
      </button>
    </header>

    <main class="login-main">
      <section class="login-card" aria-labelledby="login-title">
        <div class="eyebrow">Sign in</div>
        <h1 id="login-title">登录 ChatBI</h1>
        <p class="muted">使用企业账号登录。身份与角色由后端 JWT 校验，行级数据权限随账号生效。</p>

        <div v-if="props.notice" class="notice-box" role="status">{{ props.notice }}</div>

        <form class="login-form" novalidate @submit.prevent="submit">
          <div class="field">
            <label for="login-username">用户名</label>
            <input
              id="login-username"
              ref="usernameInput"
              v-model="username"
              name="username"
              autocomplete="username"
              maxlength="64"
              placeholder="请输入用户名"
              :aria-invalid="Boolean(error) && !username.trim()"
            />
          </div>
          <div class="field">
            <label for="login-password">密码</label>
            <input
              id="login-password"
              v-model="password"
              name="password"
              type="password"
              autocomplete="current-password"
              maxlength="128"
              placeholder="请输入密码"
            />
          </div>
          <div v-if="error" class="error-box" role="alert" aria-live="assertive">{{ error }}</div>
          <button class="btn login-submit" type="submit" :disabled="busy" :aria-busy="busy">
            <span v-if="busy" class="spinner"></span>
            {{ busy ? '登录中…' : '登录' }}
          </button>
        </form>

        <p v-if="isDev" class="login-hint">
          本地演示账号：<span class="mono">lisi</span>（管理员）、
          <span class="mono">zhangsan</span> / <span class="mono">wangwu</span>（受限用户）；密码见 README。
        </p>
      </section>
    </main>
  </div>
</template>
