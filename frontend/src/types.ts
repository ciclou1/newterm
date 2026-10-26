export type Status = 'OK' | 'CLARIFY' | 'BLOCKED' | 'ERROR' | 'DEGRADED'

export interface Series {
  name: string
  data: (number | string | null)[]
}

export interface ChartSpec {
  type: 'kpi' | 'line' | 'bar' | 'pie'
  title: string
  xAxisName: string
  yAxisName: string
  categories: string[]
  series: Series[]
  horizontal: boolean
}

export interface TableColumn {
  key: string
  label: string
  type: string
  unit: string | null
  format: string | null
}

export interface TableView {
  columns: TableColumn[]
  rows: Record<string, unknown>[]
  truncated: boolean
}

export interface GuardCheck {
  name: string
  passed: boolean
  detail: string | null
}

export interface RowScope {
  table: string
  predicate: string
}

export interface ClarificationOption {
  label: string
  question: string
}

export interface AgentResult {
  status: Status
  question: string
  mode: string
  generatedSql: string | null
  finalSql: string | null
  explanation: string | null
  confidence: number
  clarification: { question: string; options: ClarificationOption[] } | null
  guard: {
    checks: GuardCheck[]
    tables: string[]
    rowScopes: RowScope[]
  }
  result: {
    columns: { name: string; type: string }[]
    rows: Record<string, unknown>[]
    truncated: boolean
    rowCount: number
  }
  usage: {
    attempts: number
    promptTokens: number
    completionTokens: number
    costUsd: number
    model: string | null
  }
  performance: {
    linkMs: number
    generateMs: number
    guardMs: number
    executeMs: number
    totalMs: number
    explainCost: number | null
    planNodes: string[]
  }
  schema: {
    tableCount: number
    metricCount: number
    columnCount: number
    exampleCount: number
    hits: string[]
  }
  failure: { stage: string; code: string; message: string } | null
  /** True when the answer came from a fallback path rather than the primary planner. */
  degraded: boolean
  degradedReason: string | null
  trace: {
    index: number
    mode: string
    stage: string
    sql: string | null
    code: string | null
    message: string | null
    passed: boolean
    elapsedMs: number
  }[]
}

export interface ChatResponse {
  requestId: string
  conversationId: string
  auditId: number
  answer: string
  chart: ChartSpec | null
  table: TableView
  followUps: string[]
  result: AgentResult
}

export interface AppUser {
  id: number
  username: string
  displayName: string
  role: string
  roles: string[]
  departmentId: number | null
  admin: boolean
  enabled: boolean
}

export interface SemanticOverview {
  version: number
  tableCount: number
  columnCount: number
  metricCount: number
  enumCount: number
  synonymCount: number
  exampleCount: number
  permissionCount: number
  tables: {
    tableName: string
    businessName: string
    domain: string
    description: string
    columnCount: number
    metricCount: number
    enabled: boolean
  }[]
}

export interface SemColumn {
  id: number
  tableId: number
  columnName: string
  businessName: string
  dataType: string
  description: string | null
  unit: string | null
  isDimension: boolean
  isMeasure: boolean
  sensitive: boolean
  ordinal: number
}

export interface SemSynonym {
  id: number
  targetType: string
  targetKey: string
  synonym: string
  weight?: number | null
}

export interface SemEnum {
  id: number
  tableName: string
  columnName: string
  enumValue: string
  enumLabel: string
  description?: string | null
}

export interface SemTable {
  id: number
  tableName: string
  businessName: string
  domain: string
  description: string | null
  enabled: boolean
  updatedAt: string
}

export interface TableDetail {
  table: SemTable
  columns: SemColumn[]
  synonyms: SemSynonym[]
  enums: SemEnum[]
}

export interface SemMetric {
  id: number
  metricCode: string
  metricName: string
  description: string | null
  expression: string
  baseTable: string
  timeColumn: string | null
  unit: string | null
  valueFormat: string | null
  synonymsText: string | null
  enabled: boolean
  updatedAt: string
}

export interface FewShotExample {
  id: number
  question: string
  sqlText: string
  tablesUsed: string | null
  tags: string | null
  enabled: boolean
}

export interface RowPermission {
  id: number
  userId: string
  tableName: string
  columnName: string
  allowedValues: string
  description: string | null
  enabled: boolean
}

export interface EvalQuestion {
  id: number
  category: string
  difficulty: string
  question: string
  expectedStatus: string
  goldenSql: string | null
  tables: string[]
}

export interface EvalDataset {
  version: string
  description: string
  questions: EvalQuestion[]
}

export interface EvalCaseResult {
  id: number
  category: string
  difficulty: string
  question: string
  expectedStatus: string
  status: string
  passed: boolean
  message: string
  goldenSql: string | null
  generatedSql: string | null
  expectedRows: number
  actualRows: number
  latencyMs: number
  mode: string | null
  code: string | null
}

export interface EvalJob {
  id: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  user: string
  createdAt: string
  finishedAt: string | null
  total: number
  completed: number
  passed: number
  accuracy: number
  categories: Record<string, { total: number; passed: number; accuracy: number }>
  error: string | null
  results: EvalCaseResult[]
}

export interface QueryAudit {
  id: number
  requestId: string
  conversationId: string | null
  userId: string
  question: string
  generatedSql: string | null
  finalSql: string | null
  generatedBy: string | null
  status: string
  stage: string | null
  errorMessage: string | null
  attempts: number | null
  rowCount: number | null
  elapsedMs: number | null
  promptTokens: number | null
  completionTokens: number | null
  costUsd: number | null
  schemaHits: string | null
  createdAt: string
}

export interface AuditStats {
  days: number
  scope: string
  stats: Record<string, number | string>
}
