<script setup lang="ts">
import { computed, watch, ref } from 'vue'
import { rpc } from '../api'
const props=defineProps<{model:string;definition:any;filters?:unknown[];refreshKey?:number}>()
const rows=ref<any[]>([]),error=ref(''),loading=ref(false)
const labels:Record<string,string>={IQC:'来料检验',RECHECK:'返工复检'}
const columns=computed(()=>[...props.definition.groupBy.map((key:string)=>({key,name:key==='supplier'?'供应商':key==='phase'?'检验阶段':key})),...props.definition.measures.map((m:any)=>({key:m.as,name:m.field==='testedQty'?'检验数':m.field==='passedQty'?'合格数':m.field==='workHours'?'工时':m.as}))])
function ratio(row:any,r:any){const denominator=Number(row[r.denominator]||0);return denominator?`${((Number(row[r.numerator]||0)-Number(r.subtract?row[r.subtract]||0:0))/denominator*100).toFixed(2)}%`:'—'}
async function load(){loading.value=true;error.value='';try{rows.value=(await rpc(`${props.model}.aggregate`,{groupBy:props.definition.groupBy,metrics:props.definition.measures,filters:props.filters || []})).records}catch(e){error.value=(e as Error).message}finally{loading.value=false}}
watch(()=>JSON.stringify([props.model,props.definition,props.filters,props.refreshKey]),load,{immediate:true})
</script>
<template><section class="panel" v-loading="loading"><div class="panel-heading"><div><h2>{{definition.title}}</h2><p>合格率=合格数÷检验数；不良率=(检验数−合格数)÷检验数。同一件多次复检分别计入各次检验。</p></div><el-button @click="load">刷新指标</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-table :data="rows" empty-text="暂无检验数据，请先登记检验结果"><el-table-column v-for="c in columns" :key="c.key" :label="c.name"><template #default="{row}">{{ labels[row[c.key]] || row[c.key] || (row[c.key]===0?0:'—') }}</template></el-table-column><el-table-column v-for="r in definition.ratios" :key="r.name" :label="r.name"><template #default="{row}">{{ratio(row,r)}}</template></el-table-column></el-table></section></template>
