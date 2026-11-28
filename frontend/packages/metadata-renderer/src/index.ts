/** 元数据页面与字段契约；本包只定义受控数据结构，不执行动态脚本或生成任意 HTML。 */
export type MetadataFieldType =
  | 'string' | 'text' | 'uuid' | 'date' | 'datetime'
  | 'decimal' | 'integer' | 'boolean' | 'reference' | 'select' | 'money' | 'json' | 'table'

export interface MetadataFieldDefinition {
  name: string
  label?: string
  type: MetadataFieldType
  required?: boolean
  readOnly?: boolean
  unique?: boolean
  defaultValue?: string | number | boolean | null
  target?: string
  options?: string[]
  readRoles?: string[]
  childFields?: MetadataFieldDefinition[]
}

export interface MetadataTableMapping {
  schema: string
  name: string
}

export interface MetadataPageDefinition {
  doctype: string
  scope: 'PLATFORM' | 'TENANT'
  table: MetadataTableMapping
  fields: MetadataFieldDefinition[]
  form?: { fields: string[] }
  list?: { columns: string[]; filters?: Array<string | { field: string }>; sort?: Array<string | { field: string }>; search?: string[] }
  permissions?: Record<string, string[]>
}
