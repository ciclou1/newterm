<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type {
  RowPermission, SemEnum, SemMetric, SemSynonym, SemanticOverview, TableDetail,
} from '../types'
import { ApiError, api } from '../api'

const props = defineProps<{ isAdmin: boolean }>()

const overview = ref<SemanticOverview | null>(null)
const details = ref<TableDetail[]>([])
const metrics = ref<SemMetric[]>([])
const permissions = ref<RowPermission[]>([])
const loading = ref(true)
const error = ref('')
const expanded = ref<string | null>(null)
const metricQuery = ref('')
const refreshing = ref(false)
const refreshNote = ref('')

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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [overviewData, detailData, metricData, permissionData] = await Promise.all([
      api.get<SemanticOverview>('/api/semantic/overview'),
      api.get<TableDetail[]>('/api/semantic/tables'),
      api.get<SemMetric[]>('/api/semantic/metrics'),
      props.isAdmin
        ? api.get<RowPermission[]>('/api/semantic/permissions')
        : Promise.resolve([] as RowPermission[]),
    ])
    overview.value = overviewData
    details.value = detailData
    metrics.value = metricData
    permissions.value = permissionData
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : '语义层加载失败'
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
    refreshNote.value = caught instanceof ApiError ? caught.message : '刷新失败'
  } finally {
    refreshing.value = false
  }
}

function toggle(table: string) {
  expanded.value = expanded.value === table ? null : table
}

function synonymsOf(table: TableDetail): SemSynonym[] {
  return table.synonyms
}

function enumsOf(table: TableDetail): SemEnum[] {
  return table.enums
}

onMounted(load)
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
          class="btn"
          type="button"
          :disabled="!props.isAdmin || refreshing"
          :title="props.isAdmin ? '重建向量与 BM25 索引' : '需要管理员权限'"
          @click="refreshIndex"
        >
          <span v-if="refreshing" class="spinner"></span>
          {{ refreshing ? '刷新中' : '刷新索引' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-else-if="loading" class="empty-state"><span class="spinner"></span></div>

    <template v-else-if="overview">
      <div class="stat-grid">
        <div v-for="stat in stats" :key="stat.label" class="stat-card">
          <div class="label">{{ stat.label }}</div>
          <div class="value">{{ stat.value }}</div>
        </div>
      </div>

      <section class="section">
        <div class="section-head">
          <div>
            <h2>语义表</h2>
            <p>点击任意表展开字段、同义词与枚举口径。</p>
          </div>
          <span class="badge neutral">schema v{{ overview.version }}</span>
        </div>
        <div class="table-card">
          <table class="sem-table">
            <thead>
              <tr>
                <th>业务表</th>
                <th>物理视图</th>
                <th>领域</th>
                <th>字段</th>
                <th>指标</th>
                <th>说明</th>
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
                  <td class="muted" style="max-width: 360px">{{ table.description }}</td>
                </tr>
                <tr v-if="expanded === table.tableName" class="detail-row">
                  <td colspan="6">
                    <div v-if="detailMap.get(table.tableName)">
                      <div class="column-grid">
                        <div
                          v-for="column in detailMap.get(table.tableName)!.columns"
                          :key="column.id"
                          class="column-item"
                        >
                          <div class="top">
                            <span class="biz">{{ column.businessName }}</span>
                            <span class="mini-badges">
                              <span v-if="column.isDimension" class="badge info">维度</span>
                              <span v-if="column.isMeasure" class="badge neutral">度量</span>
                              <span v-if="column.sensitive" class="badge bad">敏感</span>
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

                      <div v-if="synonymsOf(detailMap.get(table.tableName)!).length" style="margin-top: 12px">
                        <span class="muted" style="font-size: 11px">业务同义词：</span>
                        <span
                          v-for="synonym in synonymsOf(detailMap.get(table.tableName)!)"
                          :key="synonym.id"
                          class="badge neutral"
                          style="margin: 2px 4px 2px 0"
                        >
                          {{ synonym.synonym }}
                        </span>
                      </div>

                      <div v-if="enumsOf(detailMap.get(table.tableName)!).length" style="margin-top: 6px">
                        <span class="muted" style="font-size: 11px">枚举口径：</span>
                        <span
                          v-for="item in enumsOf(detailMap.get(table.tableName)!).slice(0, 24)"
                          :key="item.id"
                          class="badge neutral"
                          style="margin: 2px 4px 2px 0"
                          :title="`${item.columnName} = ${item.enumValue}`"
                        >
                          {{ item.enumLabel }}
                        </span>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </section>

      <section class="section">
        <div class="section-head">
          <div>
            <h2>指标定义</h2>
            <p>指标表达式由语义层维护，模型只负责选择指标，不自由定义口径。</p>
          </div>
          <input v-model="metricQuery" class="input" type="search" placeholder="搜索指标或同义词" style="width: 220px" />
        </div>
        <div class="table-card">
          <table class="sem-table metrics-table">
            <thead>
              <tr>
                <th>指标</th>
                <th>编码</th>
                <th>表达式</th>
                <th>基础表</th>
                <th>单位 / 格式</th>
                <th>同义词</th>
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
              </tr>
              <tr v-if="!filteredMetrics.length">
                <td colspan="6" class="empty-state">没有匹配的指标</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="props.isAdmin" class="section">
        <div class="section-head">
          <div>
            <h2>行级权限</h2>
            <p>权限谓词由服务端注入 SQL AST，模型输出无法绕过。</p>
          </div>
        </div>
        <div class="table-card">
          <table class="sem-table permissions-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>目标表</th>
                <th>字段</th>
                <th>允许值</th>
                <th>说明</th>
                <th>状态</th>
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
              </tr>
              <tr v-if="!permissions.length">
                <td colspan="6" class="empty-state">暂无行级权限配置</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="section">
        <div class="section-head">
          <div>
            <h2>SQL 安全链路</h2>
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
  </div>
</template>
