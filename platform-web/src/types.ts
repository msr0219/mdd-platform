export interface Field {
  code: string; name: string; type: string; required?: boolean; editable?: boolean; hidden?: boolean;
  searchable?: boolean; listable?: boolean; length?: number; minLength?: number; maxLength?: number;
  min?: number; max?: number; pattern?: string; defaultValue?: unknown; ref?: string;
  options?: { value: string | number | boolean; label: string }[];
  refFilters?: { field: string; op: string; value: unknown }[];
}
export interface RelationHop { model: string; field: string; direction: 'in' | 'out'; filters?: {field:string;op:string;value:unknown}[] }
export interface Model {
  model: { code: string; name: string; description?: string; appendOnly?: boolean; [key: string]: unknown };
  fields: Field[]; stateMachine?: { field: string; initial: string; transitions: { from: string; to: string }[] };
  relations?: { code: string; name: string; target: string; paths: RelationHop[][] }[];
}
export interface Menu { code: string; name: string; view?: string; order?: number; permission?: string; children?: Menu[] }
export interface ViewField { field: string; widget?: string; name?: string; span?: number; placeholder?: string; when?: string }
export interface Action {
  code: string; name: string; action: string; rowSelected?: boolean; confirm?: string; when?: string;
  map?: Record<string, unknown>; params?: Record<string, unknown>; permission?: string;
  parameters?: boolean;
  success?: { message?: string; close?: boolean; refresh?: boolean; clear?: boolean };
}
export interface View {
  code: string; name: string; type: 'list' | 'form'; model: string; description?: string;
  search?: { fields: (ViewField & { op?: string })[] };
  table?: { columns: { field: string; title?: string; width?: number }[]; pagination?: { pageSize: number }; buttons?: Action[] };
  fields?: ViewField[]; buttons?: Action[];
  analytics?: any;
  chart?: ChartDefinition[];
}
export interface ChartDefinition {
  code: string; title: string; type: 'bar' | 'line'; description?: string; categoryPrefix?: string;
  groupBy: string[]; groupNames?: string[]; firstBy?: string;
  metrics: { op: 'count' | 'sum' | 'avg'; field?: string; as: string }[];
  ratios?: { as: string; numerator: string; denominator: string; subtract?: string }[];
  series: { field: string; name: string; percent?: boolean }[];
}
export interface Status { version: number; models: string[]; views: string[]; services: string[]; [key: string]: unknown }
export type Row = Record<string, any>
