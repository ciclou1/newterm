<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, api, describeError } from '../api'

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'changed'): void
}>()

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const busy = ref(false)
const error = ref('')
const fieldErrors = ref<Record<string, string>>({})
const oldInput = ref<HTMLInputElement | null>(null)
let previouslyFocused: HTMLElement | null = null

const MIN_LENGTH = 12

function validate(): boolean {
  const issues: Record<string, string> = {}
  if (!oldPassword.value) issues.oldPassword = '请输入当前密码'
  if (!newPassword.value) issues.newPassword = '请输入新密码'
  else if (newPassword.value.length < MIN_LENGTH) {
    issues.newPassword = `新密码至少需要 ${MIN_LENGTH} 个字符`
  } else if (newPassword.value === oldPassword.value) {
    issues.newPassword = '新密码不能与当前密码相同'
  }
  if (!confirmPassword.value) issues.confirmPassword = '请再次输入新密码'
  else if (confirmPassword.value !== newPassword.value) issues.confirmPassword = '两次输入的新密码不一致'
  fieldErrors.value = issues
  return Object.keys(issues).length === 0
}

async function submit() {
  if (busy.value) return
  error.value = ''
  if (!validate()) return
  busy.value = true
  try {
    await api.changePassword(oldPassword.value, newPassword.value)
    emit('changed')
  } catch (caught) {
    if (caught instanceof ApiError) {
      if (caught.code === 'OLD_PASSWORD_INCORRECT') {
        fieldErrors.value = { ...fieldErrors.value, oldPassword: caught.message }
      } else if (caught.code === 'WEAK_PASSWORD' || caught.code === 'PASSWORD_UNCHANGED') {
        fieldErrors.value = { ...fieldErrors.value, newPassword: caught.message }
      } else {
        error.value = describeError(caught)
      }
    } else {
      error.value = describeError(caught)
    }
  } finally {
    busy.value = false
  }
}

function close() {
  if (!busy.value) emit('close')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  }
}

onMounted(async () => {
  previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  oldInput.value?.focus()
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  previouslyFocused?.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click.self="close">
      <section
        class="modal-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="password-dialog-title"
      >
        <header class="modal-head">
          <h2 id="password-dialog-title">修改登录口令</h2>
          <button class="icon-btn" type="button" aria-label="关闭修改口令对话框" @click="close">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
              <path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
            </svg>
          </button>
        </header>
        <p class="muted" style="margin: 0 0 14px; font-size: 12px">
          新口令至少 {{ MIN_LENGTH }} 个字符，且不能使用文档中的演示密码。
        </p>

        <form class="login-form" novalidate @submit.prevent="submit">
          <div class="field">
            <label for="old-password">当前密码</label>
            <input
              id="old-password"
              ref="oldInput"
              v-model="oldPassword"
              name="oldPassword"
              type="password"
              autocomplete="current-password"
              maxlength="128"
              :aria-invalid="Boolean(fieldErrors.oldPassword)"
              :aria-describedby="fieldErrors.oldPassword ? 'old-password-error' : undefined"
            />
            <span v-if="fieldErrors.oldPassword" id="old-password-error" class="field-error" role="alert">
              {{ fieldErrors.oldPassword }}
            </span>
          </div>
          <div class="field">
            <label for="new-password">新密码</label>
            <input
              id="new-password"
              v-model="newPassword"
              name="newPassword"
              type="password"
              autocomplete="new-password"
              maxlength="128"
              :aria-invalid="Boolean(fieldErrors.newPassword)"
              :aria-describedby="fieldErrors.newPassword ? 'new-password-error' : undefined"
            />
            <span v-if="fieldErrors.newPassword" id="new-password-error" class="field-error" role="alert">
              {{ fieldErrors.newPassword }}
            </span>
          </div>
          <div class="field">
            <label for="confirm-password">确认新密码</label>
            <input
              id="confirm-password"
              v-model="confirmPassword"
              name="confirmPassword"
              type="password"
              autocomplete="new-password"
              maxlength="128"
              :aria-invalid="Boolean(fieldErrors.confirmPassword)"
              :aria-describedby="fieldErrors.confirmPassword ? 'confirm-password-error' : undefined"
            />
            <span v-if="fieldErrors.confirmPassword" id="confirm-password-error" class="field-error" role="alert">
              {{ fieldErrors.confirmPassword }}
            </span>
          </div>

          <div v-if="error" class="error-box" role="alert">{{ error }}</div>

          <div class="modal-actions">
            <button class="btn" type="button" :disabled="busy" @click="close">取消</button>
            <button class="btn primary" type="submit" :disabled="busy" :aria-busy="busy">
              <span v-if="busy" class="spinner" style="border-top-color: var(--surface)"></span>
              {{ busy ? '保存中…' : '保存新口令' }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </Teleport>
</template>
