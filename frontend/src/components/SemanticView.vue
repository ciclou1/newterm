<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type {
  FewShotExample, RowPermission, SemColumn, SemEnum, SemMetric, SemSynonym,
  SemanticOverview, TableDetail,
} from '../types'
import type { AuthUser } from '../api'
import { ApiError, api, describeError } from '../api'

const props = defineProps<{ isAdmin: boolean }>()

type EditorKind = 'metric' | 'synonym' | 'enum' | 'example' | 'permission' | 'table' | 'column'

interface FieldDef {
  key: string
  label: string
  type: 'text' | 'textarea' | 'select' | 'checkbox' | 'number'
  required?: boolean
  maxLength?: number
  help?: string
  placeholder?: string
}

interface EditorState {
  kind: EditorKind
  mode: 'create' | 'edit'
  id?: number
  title: string
  values: Record<string, string | boolean>
  readOnlyNote?: string
}

const editingFields: Record<EditorKind, FieldDef[]> = {
  metric: [
    { key: 'metricCode', label: '指标编码', type: 'text', required: true, maxLength: 64, help: '字母开头，仅字母、数字和下划线' },
    { key: 'metricName', label: '指标名称', type: 'text', required: true, maxLength: 64 },
    { key: 'baseTable', label: '所属表', type: 'select', required: true },
    { key: 'expression', label: '指标表达式', type: 'textarea', required: true, maxLength: 1000, help: '例如 SUM(order_amount)' },
    { key: 'timeColumn', label: '时间字段', type: 'text', maxLength: 64 },
    { key: 'unit', label: '单位', type: 'text', maxLength: 16 },
    { key: 'valueFormat', label: '数值格式', type: 'select' },
    { key: 'description', label: '说明', type: 'textarea', maxLength: 500 },
    { key: 'synonymsText', label: '同义词', type: 'text', maxLength: 500, help: '逗号分隔' },
    { key: 'enabled', label: '启用', type: 'checkbox' },
  ],
  synonym: [
    { key: 'targetType', label: '目标类型', type: 'select', required: true },
    { key: 'targetKey', label: '目标名称', type: 'text', required: true, maxLength: 128 },
    { key: 'synonym', label: '同义词', type: 'text', required: true, maxLength: 64 },
  ],
  enum: [
    { key: 'tableName', label: '所属表', type: 'select', required: true },
    { key: 'columnName', label: '所属字段', type: 'select', required: true },
    { key: 'enumValue', label: '存储值', type: 'text', required: true, maxLength: 128 },
    { key: 'enumLabel', label: '业务含义', type: 'text', required: true, maxLength: 128 },
    { key: 'description', label: '说明', type: 'textarea', maxLength: 500 },
  ],
  example: [
    { key: 'question', label: '示例问题', type: 'textarea', required: true, maxLength: 500 },
    { key: 'sqlText', label: '示例 SQL', type: 'textarea', required: true, maxLength: 4000, help: '单条只读 SELECT，服务端会再次校验' },
    { key: 'tablesUsed', label: '涉及表', type: 'text', maxLength: 500, help: '逗号分隔' },
    { key: 'tags', label: '标签', type: 'text', maxLength: 200 },
    { key: 'enabled', label: '启用', type: 'checkbox' },
  ],
  permission: [
    { key: 'userId', label: '用户', type: 'select', required: true },
    { key: 'tableName', label: '目标表', type: 'select', required: true },
    { key: 'columnName', label: '字段', type: 'select', required: true, help: '必须为语义层已注册的小写字段名' },
    { key: 'allowedValues', label: '允许值', type: 'text', required: true, maxLength: 1000, help: '例如：华东,华南' },
    { key: 'description', label: '说明', type: 'textarea', maxLength: 200 },
    { key: 'enabled', label: '启用', type: 'checkbox' },
  ],
  table: [
    { key: 'businessName', label: '业务名称', type: 'text', required: true, maxLength: 128 },
    { key: 'domain', label: '业务领域', type: 'text', required: true, maxLength: 64 },
    { key: 'description', label: '说明', type: 'textarea', maxLength: 500 },
    { key: 'enabled', label: '启用', type: 'checkbox' },
  ],
  column: [
    { key: 'businessName', label: '业务名称', type: 'text', required: true, maxLength: 128 },
    { key: 'unit', label: '单位', type: 'text', maxLength: 16 },
    { key: 'description', label: '说明', type: 'textarea', maxLength: 500 },
    { key: 'isDimension', label: '维度字段', type: 'checkbox' },
    { key: 'isMeasure', label: '度量字段', type: 'checkbox' },
    { key: 'sensitive', label: '敏感字段', type: 'checkbox' },
  ],
}

const overview = ref<SemanticOverview | null>(null)
const details = ref<TableDetail[]>([])
const metrics = ref<SemMetric[]>([])
const synonyms = ref<SemSynonym[]>([])
const enums = ref<SemEnum[]>([])
const examples = ref<FewShotExample[]>([])
const permissions = ref<RowPermission[]>([])
const users = ref<AuthUser[]>([])
const loading = ref(true)
const error = ref('')
const actionError = ref('')
const actionNote = ref('')
const saving = ref(false)
const expanded = ref<string | null>(null)
const metricQuery = ref('')
const refreshing = ref(false)
const refreshNote = ref('')
const editor = ref<EditorState | null>(null)
const fieldErrors = ref<Record<string, string>>({})
const formError = ref('')
const firstField = ref<HTMLInputElement | null>(null)

const filteredMetrics = computed(() => {
  const query = metricQuery.value.trim().toLowerCase()
  if (!query) return metrics.value
  return metrics.value.filter((metric) =>
    `${metric.metricName} ${metric.metricCode} ${metric.description ?? ''} ${metric.synonymsText ?? ''}`
      .toLowerCase().includes(query))
})

const detailMap = computed(() => new Map(details.value.map((item) => [item.table.tableName, item])))

const stats = computed(() => {
  const data = overview.value
  if (!data) return []
  return [
    { label: '语义表', value: data.tableCount },
    { label: '字段', value: data.columnCount },
    { label: '指标', value: data.metricCount },
    { label: '同义词', value: data.synonymCount },
    { label: '枚举值', value: data.enumCount },
    { label: 'Few-shot', value: data.exampleCount },
    { label: '行级权限', value: data.permissionCount },
  ]
})

const tableOptions = computed(() => details.value.map((item) => ({
  value: item.table.tableName,
  label: `${item.table.businessName}（${item.table.tableName}）`,
})))

const editorFields = computed(() => (editor.value ? editingFields[editor.value.kind] : []))

function resetMessages() {
  actionError.value = ''
  actionNote.value = ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const requests: [
      Promise<SemanticOverview>,
      Promise<TableDetail[]>,
      Promise<SemMetric[]>,
      Promise<SemSynonym[]>,
      Promise<SemEnum[]>,
      Promise<FewShotExample[]>,
    ] = [
      api.get<SemanticOverview>('/api/semantic/overview'),
      api.get<TableDetail[]>('/api/semantic/tables'),
      api.get<SemMetric[]>('/api/semantic/metrics'),
      api.get<SemSynonym[]>('/api/semantic/synonyms'),
      api.get<SemEnum[]>('/api/semantic/enums'),
      api.get<FewShotExample[]>('/api/semantic/examples'),
    ]
    const [overviewData, detailData, metricData, synonymData, enumData, exampleData] =
      await Promise.all(requests)
    overview.value = overviewData
    details.value = detailData
    metrics.value = metricData
    synonyms.value = synonymData
    enums.value = enumData
    examples.value = exampleData

    if (props.isAdmin) {
      const [permissionData, userData] = await Promise.all([
        api.get<RowPermission[]>('/api/semantic/permissions'),
        api.get<AuthUser[]>('/api/users'),
      ])
      permissions.value = permissionData
      users.value = userData
    } else {
      permissions.value = []
      users.value = []
    }
  } catch (caught) {
    error.value = describeError(caught)
  } finally {
    loading.value = false
  }
}

async function refreshIndex() {
  refreshing.value = true
  refreshNote.value = ''
  try {
    const result = await api.post<Record<string, unknown>>('/api/semantic/refresh', {})
    refreshNote.value = `索引已刷新：${result.documents ?? '—'} 个文档，${result.upserted ?? 0} 个更新`
    await load()
  } catch (caught) {
    refreshNote.value = describeError(caught)
  } finally {
    refreshing.value = false
  }
}

function toggle(table: string) {
  expanded.value = expanded.value === table ? null : table
}

function str(key: string): string {
  return String(editor.value?.values[key] ?? '')
}

function bool(key: string): boolean {
  return Boolean(editor.value?.values[key])
}

function setValue(key: string, value: string | boolean) {
  if (!editor.value) return
  const previous = editor.value.values[key]
  editor.value.values[key] = value
  if (key === 'tableName' && previous !== value
    && (editor.value.kind === 'enum' || editor.value.kind === 'permission')) {
    // Column options depend on the chosen table; never submit a stale pair.
    editor.value.values.columnName = ''
    fieldErrors.value = { ...fieldErrors.value, columnName: '' }
  }
  if (fieldErrors.value[key]) {
    fieldErrors.value = { ...fieldErrors.value, [key]: '' }
  }
}

function optionsFor(field: FieldDef): { value: string; label: string }[] {
  if (!editor.value) return []
  switch (field.key) {
    case 'baseTable':
    case 'tableName':
      return tableOptions.value
    case 'columnName':
      return columnsFor(str('tableName')).map((column) => ({
        value: column.columnName,
        label: `${column.businessName}（${column.columnName}）`,
      }))
    case 'targetType':
      return [
        { value: 'TABLE', label: '表' },
        { value: 'METRIC', label: '指标' },
        { value: 'COLUMN', label: '字段' },
      ]
    case 'valueFormat':
      return [
        { value: 'number', label: '数值' },
        { value: 'currency', label: '金额' },
        { value: 'percent', label: '百分比' },
      ]
    case 'userId':
      return users.value.map((user) => ({
        value: user.username,
        label: `${user.displayName || user.username}（${user.username}）`,
      }))
    default:
      return []
  }
}

function columnsFor(tableName: string): SemColumn[] {
  return detailMap.value.get(tableName)?.columns ?? []
}

function tableDetail(tableName: string): TableDetail | undefined {
  return detailMap.value.get(tableName)
}

function editTable(tableName: string) {
  const detail = tableDetail(tableName)
  if (detail) openEdit('table', { ...detail.table })
}

function editColumn(column: SemColumn) {
  openEdit('column', { ...column })
}

/** The backend keys metrics by code, so the code is immutable once created. */
function isReadonly(field: FieldDef): boolean {
  return editor.value?.kind === 'metric'
    && editor.value.mode === 'edit'
    && field.key === 'metricCode'
}

function openCreate(kind: EditorKind) {
  resetMessages()
  fieldErrors.value = {}
  formError.value = ''
  const values: Record<string, string | boolean> = {}
  editingFields[kind].forEach((field) => {
    values[field.key] = field.type === 'checkbox' ? true : ''
  })
  if (kind === 'synonym') values.targetType = 'TABLE'
  if (kind === 'metric') values.valueFormat = 'number'
  editor.value = {
    kind,
    mode: 'create',
    title: {
      metric: '新增指标', synonym: '新增同义词', enum: '新增枚举值', example: '新增 Few-shot 示例',
      permission: '新增行级权限', table: '编辑语义表', column: '编辑字段',
    }[kind],
    values,
  }
  focusEditor()
}

function openEdit(kind: EditorKind, entity: Record<string, unknown>, title?: string) {
  resetMessages()
  fieldErrors.value = {}
  formError.value = ''
  const values: Record<string, string | boolean> = {}
  editingFields[kind].forEach((field) => {
    const raw = entity[field.key]
    values[field.key] = field.type === 'checkbox' ? Boolean(raw) : raw === null || raw === undefined ? '' : String(raw)
  })
  editor.value = {
    kind,
    mode: 'edit',
    id: Number(entity.id),
    title: title ?? {
      metric: '编辑指标', synonym: '编辑同义词', enum: '编辑枚举值', example: '编辑 Few-shot 示例',
      permission: '编辑行级权限', table: '编辑语义表', column: '编辑字段',
    }[kind],
    values,
  }
  focusEditor()
}

function focusEditor() {
  nextTick(() => firstField.value?.focus())
}

function closeEditor() {
  if (saving.value) return
  editor.value = null
  fieldErrors.value = {}
  formError.value = ''
}

function validate(): boolean {
  const current = editor.value
  if (!current) return false
  const issues: Record<string, string> = {}
  for (const field of editorFields.value) {
    const value = String(current.values[field.key] ?? '').trim()
    if (field.required && !value) {
      issues[field.key] = `${field.label}不能为空`
    } else if (field.maxLength && value.length > field.maxLength) {
      issues[field.key] = `${field.label}不能超过 ${field.maxLength} 个字符`
    }
  }
  if (current.kind === 'metric') {
    const code = String(current.values.metricCode ?? '').trim()
    if (code && !/^[A-Za-z][A-Za-z0-9_]*$/.test(code)) {
      issues.metricCode = '指标编码需以字母开头，仅含字母、数字和下划线'
    }
    const expression = String(current.values.expression ?? '')
    if (expression.includes(';')) issues.expression = '指标表达式不允许包含分号或多条语句'
  }
  if (current.kind === 'example') {
    const sql = String(current.values.sqlText ?? '').trim()
    if (sql && !/^select\b/i.test(sql)) issues.sqlText = '示例 SQL 必须是 SELECT 查询'
  }
  if (current.kind === 'synonym') {
    const weight = String(current.values.weight ?? '').trim()
    if (weight && !Number.isFinite(Number(weight))) issues.weight = '权重必须是数字'
  }
  if (current.kind === 'permission') {
    if (!String(current.values.tableName ?? '').trim()) {
      issues.columnName = issues.columnName || '请先选择目标表'
    }
  }
  fieldErrors.value = issues
  return Object.keys(issues).length === 0
}

function payload(): Record<string, unknown> {
  const current = editor.value
  if (!current) return {}
  const data: Record<string, unknown> = { ...current.values }
  if (current.mode === 'edit') data.id = current.id
  // MetricRequest and SynonymRequest are upsert records without an id.
  if (current.kind === 'metric' || current.kind === 'synonym') delete data.id
  if (current.kind === 'table' || current.kind === 'column') delete data.id
  return data
}

async function save() {
  const current = editor.value
  if (!current || saving.value) return
  formError.value = ''
  if (!validate()) return
  saving.value = true
  try {
    const body = payload()
    if (current.kind === 'metric') {
      await api.post<SemMetric>('/api/semantic/metrics', body)
    } else if (current.kind === 'synonym') {
      await api.post<SemSynonym>('/api/semantic/synonyms', body)
    } else if (current.kind === 'enum') {
      await api.post<SemEnum>('/api/semantic/enums', body)
    } else if (current.kind === 'example') {
      await api.post<FewShotExample>('/api/semantic/examples', body)
    } else if (current.kind === 'permission') {
      await api.post<RowPermission>('/api/semantic/permissions', body)
    } else if (current.kind === 'table') {
      await api.put<unknown>(`/api/semantic/tables/${current.id}`, body)
    } else if (current.kind === 'column') {
      await api.put<unknown>(`/api/semantic/columns/${current.id}`, body)
    }
    editor.value = null
    actionNote.value = current.mode === 'create' ? '已新增' : '已保存'
    await load()
  } catch (caught) {
    // Keep the dialog open with the user's input; the list was not mutated, so
    // closing the dialog after a failure would silently discard their edit.
    if (caught instanceof ApiError && caught.kind === 'validation') {
      formError.value = caught.message
    } else {
      formError.value = describeError(caught)
    }
  } finally {
    saving.value = false
  }
}

async function remove(kind: EditorKind, id: number, label: string) {
  if (!props.isAdmin) return
  if (!window.confirm(`确认删除「${label}」？该操作不可撤销。`)) return
  resetMessages()
  try {
    await api.delete<null>(`/api/semantic/${kindPath(kind)}/${id}`)
    actionNote.value = `已删除「${label}」`
    await load()
  } catch (caught) {
    // No optimistic removal: on failure the reloaded list is still the source
    // of truth and the user sees a recoverable error.
    actionError.value = describeError(caught)
    await load()
  }
}

function kindPath(kind: EditorKind): string {
  switch (kind) {
    case 'metric': return 'metrics'
    case 'synonym': return 'synonyms'
    case 'enum': return 'enums'
    case 'example': return 'examples'
    case 'permission': return 'permissions'
    default: return kind
  }
}

function onEditorKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && editor.value) {
    event.preventDefault()
    closeEditor()
  }
}

onMounted(() => {
  document.addEventListener('keydown', onEditorKeydown)
  void load()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onEditorKeydown)
})
</script>

<template>
  <div class="view-pad">
    <div class="page-head">
      <div>
        <div class="eyebrow">Semantic Layer</div>
        <h1>语义层</h1>
        <p>表、字段、指标、同义词、枚举值与行级权限——ChatBI 的核心资产。</p>
      </div>
      <div style="display: flex; gap: 8px; align-items: center">
        <span v-if="refreshNote" class="muted" style="font-size: 11px">{{ refreshNote }}</span>
        <button
          v-if="props.isAdmin"
          class="btn"
          type="button"
          :disabled="refreshing"
          title="重建向量与 BM25 索引"
          @click="refreshIndex"
        >
          <span v-if="refreshing" class="spinner"></span>
          {{ refreshing ? '刷新中' : '刷新索引' }}
        </button>
      </div>
    </div>

    <div v-if="actionNote" class="notice-box" role="status">{{ actionNote }}</div>
    <div v-if="actionError" class="error-box" role="alert" style="margin-bottom: 14px">{{ actionError }}</div>

    <div v-if="error" class="error-box" role="alert">
      {{ error }}
      <div class="error-actions">
        <button class="btn sm" type="button" @click="load">重新加载</button>
      </div>
    </div>
    <div v-else-if="loading" class="empty-state" role="status">
      <span class="spinner"></span>
      <span class="sr-only">语义层加载中</span>
    </div>

    <template v-else-if="overview">
      <div class="stat-grid">
        <div v-for="stat in stats" :key="stat.label" class="stat-card">
          <div class="label">{{ stat.label }}</div>
          <div class="value">{{ stat.value }}</div>
        </div>
      </div>

      <p v-if="!props.isAdmin" class="muted" style="font-size: 12px; margin-top: -8px">
        当前账号为只读访问，维护操作需要管理员权限。
      </p>

      <section class="section" aria-labelledby="semantic-tables-title">
        <div class="section-head">
          <div>
            <h2 id="semantic-tables-title">语义表</h2>
            <p>点击任意表展开字段、同义词与枚举口径。</p>
          </div>
          <span class="badge neutral">schema v{{ overview.version }}</span>
        </div>
        <div class="table-card">
          <table class="sem-table">
            <thead>
              <tr>
                <th scope="col">业务表</th>
                <th scope="col">物理视图</th>
                <th scope="col">领域</th>
                <th scope="col">字段</th>
                <th scope="col">指标</th>
                <th scope="col">状态</th>
                <th scope="col">说明</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="table in overview.tables" :key="table.tableName">
                <tr class="clickable" @click="toggle(table.tableName)">
                  <td class="name">{{ table.businessName }}</td>
                  <td class="sub">{{ table.tableName }}</td>
                  <td><span class="badge neutral">{{ table.domain }}</span></td>
                  <td>{{ table.columnCount }}</td>
                  <td>{{ table.metricCount }}</td>
                  <td>
                    <span class="badge" :class="table.enabled ? 'ok' : 'neutral'">
                      {{ table.enabled ? '启用' : '停用' }}
                    </span>
                  </td>
                  <td class="muted" style="max-width: 360px">{{ table.description }}</td>
                </tr>
                <tr v-if="expanded === table.tableName" class="detail-row">
                  <td colspan="7">
                    <div v-if="detailMap.get(table.tableName)">
                      <div class="detail-actions" v-if="props.isAdmin">
                        <button class="btn sm" type="button" @click.stop="editTable(table.tableName)">
                          编辑表信息
                        </button>
                      </div>
                      <div class="column-grid">
                        <div
                          v-for="column in tableDetail(table.tableName)?.columns ?? []"
                          :key="column.id"
                          class="column-item"
                        >
                          <div class="top">
                            <span class="biz">{{ column.businessName }}</span>
                            <span class="mini-badges">
                              <span v-if="column.isDimension" class="badge info">维度</span>
                              <span v-if="column.isMeasure" class="badge neutral">度量</span>
                              <span v-if="column.sensitive" class="badge bad">敏感</span>
                              <button
                                v-if="props.isAdmin"
                                class="btn ghost sm"
                                type="button"
                                :aria-label="`编辑字段 ${column.businessName}`"
                                @click.stop="editColumn(column)"
                              >
                                编辑
                              </button>
                            </span>
                          </div>
                          <div class="phys">
                            {{ column.columnName }} · {{ column.dataType }}
                            <template v-if="column.unit"> · {{ column.unit }}</template>
                          </div>
                          <div v-if="column.description" class="muted" style="font-size: 11px; margin-top: 2px">
                            {{ column.description }}
                          </div>
                        </div>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </section>

      <section class="section" aria-labelledby="semantic-metrics-title">
        <div class="section-head">
          <div>
            <h2 id="semantic-metrics-title">指标定义</h2>
            <p>指标表达式由语义层维护，模型只负责选择指标，不自由定义口径。</p>
          </div>
          <div class="filter-row" style="margin: 0">
            <input
              v-model="metricQuery"
              class="input"
              type="search"
              aria-label="搜索指标或同义词"
              placeholder="搜索指标或同义词"
              style="width: 220px"
            />
            <button v-if="props.isAdmin" class="btn primary sm" type="button" @click="openCreate('metric')">
              新增指标
            </button>
          </div>
        </div>
        <div class="table-card">
          <table class="sem-table metrics-table">
            <thead>
              <tr>
                <th scope="col">指标</th>
                <th scope="col">编码</th>
                <th scope="col">表达式</th>
                <th scope="col">基础表</th>
                <th scope="col">单位 / 格式</th>
                <th scope="col">同义词</th>
                <th scope="col">状态</th>
                <th v-if="props.isAdmin" scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="metric in filteredMetrics" :key="metric.id">
                <td class="name">{{ metric.metricName }}</td>
                <td class="sub">{{ metric.metricCode }}</td>
                <td class="mono" style="max-width: 420px; white-space: normal; font-size: 10.5px">
                  {{ metric.expression }}
                </td>
                <td class="sub">{{ metric.baseTable }}</td>
                <td>{{ metric.unit ?? '—' }} / {{ metric.valueFormat ?? 'number' }}</td>
                <td class="muted" style="font-size: 11px; max-width: 160px">{{ metric.synonymsText ?? '—' }}</td>
                <td>
                  <span class="badge" :class="metric.enabled ? 'ok' : 'neutral'">
                    {{ metric.enabled ? '启用' : '停用' }}
                  </span>
                </td>
                <td v-if="props.isAdmin">
                  <div class="row-actions">
                    <button class="btn ghost sm" type="button" @click="openEdit('metric', { ...metric })">编辑</button>
                    <button class="btn ghost sm danger" type="button"
                      @click="remove('metric', metric.id, metric.metricName)">删除</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!filteredMetrics.length">
                <td :colspan="props.isAdmin ? 8 : 7" class="empty-state">没有匹配的指标</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="section" aria-labelledby="semantic-synonyms-title">
        <div class="section-head">
          <div>
            <h2 id="semantic-synonyms-title">同义词</h2>
            <p>业务叫法与表、指标、字段的映射。</p>
          </div>
          <button v-if="props.isAdmin" class="btn primary sm" type="button" @click="openCreate('synonym')">
            新增同义词
          </button>
        </div>
        <div class="table-card">
          <table class="sem-table">
            <thead>
              <tr>
                <th scope="col">目标类型</th>
                <th scope="col">目标名称</th>
                <th scope="col">同义词</th>
                <th v-if="props.isAdmin" scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="synonym in synonyms" :key="synonym.id">
                <td><span class="badge neutral">{{ synonym.targetType }}</span></td>
                <td class="mono">{{ synonym.targetKey }}</td>
                <td>{{ synonym.synonym }}</td>
                <td v-if="props.isAdmin">
                  <div class="row-actions">
                    <button class="btn ghost sm danger" type="button"
                      @click="remove('synonym', synonym.id, synonym.synonym)">删除</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!synonyms.length">
                <td :colspan="props.isAdmin ? 4 : 3" class="empty-state">暂无同义词</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="section" aria-labelledby="semantic-enums-title">
        <div class="section-head">
          <div>
            <h2 id="semantic-enums-title">枚举值</h2>
            <p>字段存储值与业务含义的映射，影响口径理解。</p>
          </div>
          <button v-if="props.isAdmin" class="btn primary sm" type="button" @click="openCreate('enum')">
            新增枚举值
          </button>
        </div>
        <div class="table-card">
          <table class="sem-table">
            <thead>
              <tr>
                <th scope="col">表</th>
                <th scope="col">字段</th>
                <th scope="col">存储值</th>
                <th scope="col">业务含义</th>
                <th v-if="props.isAdmin" scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in enums" :key="item.id">
                <td class="sub">{{ item.tableName }}</td>
                <td class="sub">{{ item.columnName }}</td>
                <td class="mono">{{ item.enumValue }}</td>
                <td>{{ item.enumLabel }}</td>
                <td v-if="props.isAdmin">
                  <div class="row-actions">
                    <button class="btn ghost sm" type="button" @click="openEdit('enum', { ...item })">编辑</button>
                    <button class="btn ghost sm danger" type="button"
                      @click="remove('enum', item.id, item.enumLabel)">删除</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!enums.length">
                <td :colspan="props.isAdmin ? 5 : 4" class="empty-state">暂无枚举值</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="section" aria-labelledby="semantic-examples-title">
        <div class="section-head">
          <div>
            <h2 id="semantic-examples-title">Few-shot 示例</h2>
            <p>经安全校验的示例 SQL，用于提升生成准确率。</p>
          </div>
          <button v-if="props.isAdmin" class="btn primary sm" type="button" @click="openCreate('example')">
            新增示例
          </button>
        </div>
        <div class="table-card">
          <table class="sem-table">
            <thead>
              <tr>
                <th scope="col">问题</th>
                <th scope="col">SQL</th>
                <th scope="col">标签</th>
                <th scope="col">状态</th>
                <th v-if="props.isAdmin" scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in examples" :key="item.id">
                <td style="max-width: 260px; white-space: normal">{{ item.question }}</td>
                <td class="mono" style="max-width: 340px; white-space: normal; font-size: 10.5px">
                  {{ item.sqlText }}
                </td>
                <td class="muted">{{ item.tags ?? '—' }}</td>
                <td>
                  <span class="badge" :class="item.enabled ? 'ok' : 'neutral'">
                    {{ item.enabled ? '启用' : '停用' }}
                  </span>
                </td>
                <td v-if="props.isAdmin">
                  <div class="row-actions">
                    <button class="btn ghost sm" type="button" @click="openEdit('example', { ...item })">编辑</button>
                    <button class="btn ghost sm danger" type="button"
                      @click="remove('example', item.id, item.question)">删除</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!examples.length">
                <td :colspan="props.isAdmin ? 5 : 4" class="empty-state">暂无 Few-shot 示例</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="props.isAdmin" class="section" aria-labelledby="semantic-permissions-title">
        <div class="section-head">
          <div>
            <h2 id="semantic-permissions-title">行级权限</h2>
            <p>权限谓词由服务端注入 SQL AST，模型输出无法绕过。</p>
          </div>
          <button class="btn primary sm" type="button" @click="openCreate('permission')">新增权限</button>
        </div>
        <div class="table-card">
          <table class="sem-table permissions-table">
            <thead>
              <tr>
                <th scope="col">用户</th>
                <th scope="col">目标表</th>
                <th scope="col">字段</th>
                <th scope="col">允许值</th>
                <th scope="col">说明</th>
                <th scope="col">状态</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="permission in permissions" :key="permission.id">
                <td class="name">{{ permission.userId }}</td>
                <td class="sub">{{ permission.tableName }}</td>
                <td class="sub">{{ permission.columnName }}</td>
                <td class="mono">{{ permission.allowedValues }}</td>
                <td class="muted" style="font-size: 12px">{{ permission.description }}</td>
                <td>
                  <span class="badge" :class="permission.enabled ? 'ok' : 'neutral'">
                    {{ permission.enabled ? '启用' : '停用' }}
                  </span>
                </td>
                <td>
                  <div class="row-actions">
                    <button class="btn ghost sm" type="button"
                      @click="openEdit('permission', { ...permission })">编辑</button>
                    <button class="btn ghost sm danger" type="button"
                      @click="remove('permission', permission.id, `${permission.userId} · ${permission.tableName}`)">
                      删除
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="!permissions.length">
                <td colspan="7" class="empty-state">暂无行级权限配置</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="section" aria-labelledby="semantic-security-title">
        <div class="section-head">
          <div>
            <h2 id="semantic-security-title">SQL 安全链路</h2>
            <p>每一次问数都经过同一条可审计的只读通道。</p>
          </div>
        </div>
        <div class="stat-grid" style="grid-template-columns: repeat(auto-fit, minmax(210px, 1fr))">
          <div class="stat-card">
            <div class="label">语句校验</div>
            <div style="font-size: 13px; margin-top: 3px">单层 SELECT · 拦截 DDL/DML/多语句</div>
          </div>
          <div class="stat-card">
            <div class="label">对象白名单</div>
            <div style="font-size: 13px; margin-top: 3px">仅语义视图 · 拒绝系统 schema</div>
          </div>
          <div class="stat-card">
            <div class="label">只读执行</div>
            <div style="font-size: 13px; margin-top: 3px">chatbi_ro 账号 · 查询超时与行数上限</div>
          </div>
          <div class="stat-card">
            <div class="label">代价预估</div>
            <div style="font-size: 13px; margin-top: 3px">EXPLAIN 代价阈值 · 审计日志全量留痕</div>
          </div>
        </div>
      </section>
    </template>

    <Teleport to="body">
      <div v-if="editor" class="modal-backdrop" @click.self="closeEditor">
        <section
          class="modal-card"
          role="dialog"
          aria-modal="true"
          aria-labelledby="semantic-editor-title"
        >
          <header class="modal-head">
            <div>
              <h2 id="semantic-editor-title">{{ editor.title }}</h2>
              <p v-if="editor.mode === 'edit'" class="muted" style="margin: 2px 0 0; font-size: 11px">
                物理名称不可修改；保存后立即刷新语义目录与索引。
              </p>
            </div>
            <button class="icon-btn" type="button" aria-label="关闭编辑对话框" @click="closeEditor">
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                <path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
              </svg>
            </button>
          </header>

          <form class="login-form" novalidate @submit.prevent="save">
            <div v-for="(field, index) in editorFields" :key="field.key" class="field">
              <label :for="`semantic-${field.key}`">
                {{ field.label }}<span v-if="field.required" aria-hidden="true"> *</span>
              </label>

              <select
                v-if="field.type === 'select'"
                :id="`semantic-${field.key}`"
                :ref="(element) => { if (index === 0) firstField = element as HTMLInputElement | null }"
                class="select"
                :name="field.key"
                :value="str(field.key)"
                :aria-invalid="Boolean(fieldErrors[field.key])"
                :aria-describedby="fieldErrors[field.key] ? `semantic-${field.key}-error` : undefined"
                @change="setValue(field.key, ($event.target as HTMLSelectElement).value)"
              >
                <option value="">请选择</option>
                <option v-for="option in optionsFor(field)" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>

              <textarea
                v-else-if="field.type === 'textarea'"
                :id="`semantic-${field.key}`"
                :ref="(element) => { if (index === 0) firstField = element as HTMLInputElement | null }"
                class="input"
                :name="field.key"
                rows="3"
                :maxlength="field.maxLength"
                :value="str(field.key)"
                :aria-invalid="Boolean(fieldErrors[field.key])"
                :aria-describedby="fieldErrors[field.key] ? `semantic-${field.key}-error` : undefined"
                @input="setValue(field.key, ($event.target as HTMLTextAreaElement).value)"
              ></textarea>

              <label v-else-if="field.type === 'checkbox'" class="checkbox-field">
                <input
                  :id="`semantic-${field.key}`"
                  :ref="(element) => { if (index === 0) firstField = element as HTMLInputElement | null }"
                  type="checkbox"
                  :name="field.key"
                  :checked="bool(field.key)"
                  @change="setValue(field.key, ($event.target as HTMLInputElement).checked)"
                />
                <span>启用</span>
              </label>

              <input
                v-else
                :id="`semantic-${field.key}`"
                :ref="(element) => { if (index === 0) firstField = element as HTMLInputElement | null }"
                class="input"
                :name="field.key"
                :type="field.type === 'number' ? 'number' : 'text'"
                :maxlength="field.maxLength"
                :placeholder="field.placeholder"
                :readonly="isReadonly(field)"
                :value="str(field.key)"
                :aria-invalid="Boolean(fieldErrors[field.key])"
                :aria-describedby="fieldErrors[field.key]
                  ? `semantic-${field.key}-error`
                  : field.help ? `semantic-${field.key}-help` : undefined"
                @input="setValue(field.key, ($event.target as HTMLInputElement).value)"
              />

              <span v-if="field.help" :id="`semantic-${field.key}-help`" class="field-help">{{ field.help }}</span>
              <span v-if="fieldErrors[field.key]" :id="`semantic-${field.key}-error`" class="field-error" role="alert">
                {{ fieldErrors[field.key] }}
              </span>
            </div>

            <div v-if="formError" class="error-box" role="alert">{{ formError }}</div>
            <p v-if="editor.mode === 'edit' && editor.kind === 'column'" class="muted" style="font-size: 11px; margin: 0">
              字段物理名与类型不可修改，仅维护业务名称、说明与标记。
            </p>

            <div class="modal-actions">
              <button class="btn" type="button" :disabled="saving" @click="closeEditor">取消</button>
              <button class="btn primary" type="submit" :disabled="saving" :aria-busy="saving">
                <span v-if="saving" class="spinner" style="border-top-color: var(--surface)"></span>
                {{ saving ? '保存中…' : '保存' }}
              </button>
            </div>
          </form>
        </section>
      </div>
    </Teleport>
  </div>
</template>
