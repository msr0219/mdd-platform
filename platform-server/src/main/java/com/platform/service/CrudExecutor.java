package com.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.meta.Conditions;
import com.platform.meta.RelationPaths;
import com.platform.auth.AuthContext;
import com.platform.auth.RowScope;
import com.platform.meta.MetaSnapshot;
import com.platform.rpc.RpcException;
import com.platform.service.extension.ModelCrudContext;
import com.platform.service.extension.ModelCrudHandler;
import com.platform.service.extension.ModelCrudRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static com.platform.meta.Definitions.*;

@Component
public class CrudExecutor {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final RowScope rowScope;
    private final ModelCrudRegistry handlers;
    private final ThreadLocal<Deque<String>> handlerCalls = ThreadLocal.withInitial(ArrayDeque::new);
    private final AtomicLong lastId=new AtomicLong();
    public CrudExecutor(JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager tm,RowScope rowScope,ModelCrudRegistry handlers) {this.jdbc=jdbc;this.mapper=mapper;tx=new TransactionTemplate(tm);this.rowScope=rowScope;this.handlers=handlers;}
    public Object execute(MetaSnapshot snapshot,String code,String action,JsonNode params) {
        JsonNode def=model(snapshot,code);
        if(params==null||params.isNull())params=mapper.createObjectNode();
        if(!params.isObject())throw new RpcException(-32602,"params必须为对象");
        final JsonNode p=params;
        try {
            if(Set.of("create","update","delete","batchCreate","batchDelete","adjust","transition").contains(action))
                return tx.execute(status->invokeHandler(snapshot,code,def,action,p));
            return invokeHandler(snapshot,code,def,action,p);
        }catch(DataIntegrityViolationException ex) {
            throw new RpcException(-32003,"唯一约束或数据完整性冲突",Map.of("fields",fields(def).values().stream().filter(f->f.path("unique").asBoolean()).map(f->Map.of("code",f.path("code").asText(),"message","值已存在或违反数据约束")).toList()));
        }
    }
    private Object invokeHandler(MetaSnapshot snapshot,String code,JsonNode def,String action,JsonNode params) {
        ModelCrudHandler handler=handlers.find(code);
        if(handler==null)return run(snapshot,code,def,action,params);
        // Model-level immutability remains a boundary even when the operation is fully replaced.
        if(Set.of("update","delete","batchDelete","adjust","transition").contains(action))mutable(def);
        Deque<String> calls=handlerCalls.get();String method=code+"."+action;
        if(calls.contains(method)||calls.size()>=32)
            throw new RpcException(-32603,"自定义 CRUD 递归调用或嵌套超过32层："+method+"；调用当前动作的通用实现请使用 context.proceed(params)");
        calls.push(method);
        try {
            JsonNode input=params.deepCopy();
            ModelCrudContext context=new ModelCrudContext(code,action,def,input,
                    supplied->run(snapshot,code,def,action,objectParams(supplied)),
                    (modelCode,nestedAction,supplied)->execute(snapshot,modelCode,nestedAction,supplied));
            return handler.handle(context,input);
        } finally {
            calls.pop();if(calls.isEmpty())handlerCalls.remove();
        }
    }
    private JsonNode objectParams(JsonNode params) {
        if(params==null||params.isNull())return mapper.createObjectNode();
        if(!params.isObject())throw new RpcException(-32602,"params必须为对象");
        return params;
    }
    private Object run(MetaSnapshot snapshot,String code,JsonNode def,String action,JsonNode p) {
        return switch(action) {
            case "create"->create(snapshot,def,p);
            case "read"->read(snapshot,def,id(def,p));
            case "list"->list(snapshot,def,p);
            case "update"->update(snapshot,def,p);
            case "delete"->delete(snapshot,def,p);
            case "adjust"->adjust(snapshot,def,p);
            case "transition"->transition(snapshot,def,p);
            case "aggregate"->aggregate(snapshot,def,p);
            case "related"->related(snapshot,def,p);
            case "batchCreate"->batchCreate(snapshot,def,p);
            case "batchDelete"->batchDelete(snapshot,def,p);
            default->throw new RpcException(-32601,"模型动作不存在："+action);
        };
    }
    private Map<String,Object> create(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        Map<String,JsonNode> fs=fields(def);checkKeys(p,fs.keySet());
        Map<String,Object> data=new LinkedHashMap<>();List<Map<String,String>> errors=new ArrayList<>();
        for(var e:fs.entrySet()) {
            String key=e.getKey();JsonNode f=e.getValue(),value=p.get(key);
            if(value!=null&&!f.path("editable").asBoolean(true)) {errors.add(fieldError(key,"字段不可编辑"));continue;}
            if(value==null)value=f.get("defaultValue");
            if(def.path("stateMachine").path("field").asText().equals(key)) {
                JsonNode initial=def.path("stateMachine").get("initial");
                if(p.has(key)&&!p.get(key).equals(initial))errors.add(fieldError(key,"新记录必须使用初始状态"));
                value=initial;
            }
            Object converted=convert(snapshot,def,f,value,errors);
            if(value!=null||f.path("required").asBoolean())data.put(key,converted);
        }
        reject(errors);validateUnique(def,data,null);
        String idType=def.path("model").path("idType").asText("auto");
        Object recordId=null;
        if(idType.equals("uuid"))recordId=UUID.randomUUID().toString();
        else if(idType.equals("snowflake"))recordId=lastId.updateAndGet(previous->Math.max(previous+1,System.currentTimeMillis()*4096));
        if(recordId!=null)data.put("id",recordId);
        data.put("created_by",AuthContext.actor());data.put("updated_by",AuthContext.actor());
        if(data.isEmpty()) {
            // Explicit default version permits creation of models containing only optional/default database values.
            data.put("version",0L);
        }
        String sql="INSERT INTO "+q(table(def))+" ("+String.join(",",data.keySet().stream().map(com.platform.meta.Definitions::q).toList())+") VALUES ("+String.join(",",Collections.nCopies(data.size(),"?"))+")";
        if(idType.equals("auto")) {
            KeyHolder holder=new GeneratedKeyHolder();
            jdbc.update(c->{PreparedStatement s=c.prepareStatement(sql,new String[]{"id"});int i=1;for(Object value:data.values())s.setObject(i++,value);return s;},holder);
            recordId=Objects.requireNonNull(holder.getKey()).longValue();
        }else jdbc.update(sql,data.values().toArray());
        return read(snapshot,def,recordId);
    }
    private Map<String,Object> update(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        mutable(def);Object recordId=id(def,p);long version=version(p);Map<String,JsonNode> fs=fields(def);
        Set<String> keys=new HashSet<>(fs.keySet());keys.addAll(Set.of("id","version"));checkKeys(p,keys);
        Map<String,Object> current=read(snapshot,def,recordId);checkVersion(current,version);
        Map<String,Object> data=new LinkedHashMap<>();List<Map<String,String>> errors=new ArrayList<>();
        p.fieldNames().forEachRemaining(key->{
            if(!fs.containsKey(key))return;
            JsonNode f=fs.get(key);
            if(!f.path("editable").asBoolean(true))errors.add(fieldError(key,"字段不可编辑"));
            else if(def.path("stateMachine").path("field").asText().equals(key))errors.add(fieldError(key,"状态只能通过transition动作修改"));
            else data.put(key,convert(snapshot,def,f,p.get(key),errors));
        });
        reject(errors);if(data.isEmpty())throw new RpcException(-32602,"没有可更新的字段");
        validateUnique(def,data,recordId);
        List<Object> args=new ArrayList<>(data.values());args.add(AuthContext.actor());args.add(recordId);args.add(version);
        String scope=rowScope.sql(snapshot,def,table(def),args);
        int n=jdbc.update("UPDATE "+q(table(def))+" SET "+String.join(",",data.keySet().stream().map(k->q(k)+"=?").toList())+",`version`=`version`+1,`updated_at`=CURRENT_TIMESTAMP,`updated_by`=? WHERE `id`=? AND `version`=?"+alive(def)+scope,args.toArray());
        if(n!=1)conflict();return read(snapshot,def,recordId);
    }
    private Object delete(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        mutable(def);checkKeys(p,Set.of("id","version"));Object recordId=id(def,p);long v=version(p);checkVersion(read(snapshot,def,recordId),v);
        String sql=logicalDelete(def)?"UPDATE "+q(table(def))+" SET `deleted`=TRUE,`version`=`version`+1,`updated_at`=CURRENT_TIMESTAMP,`updated_by`=? WHERE `id`=? AND `version`=?"+alive(def):"DELETE FROM "+q(table(def))+" WHERE `id`=? AND `version`=?";
        List<Object> args=new ArrayList<>();if(logicalDelete(def))args.add(AuthContext.actor());args.add(recordId);args.add(v);
        sql+=rowScope.sql(snapshot,def,table(def),args);
        if(jdbc.update(sql,args.toArray())!=1)conflict();return Map.of("id",recordId);
    }
    private Map<String,Object> adjust(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        mutable(def);checkKeys(p,Set.of("id","field","delta","version"));Object recordId=id(def,p);long v=version(p);
        String field=p.path("field").asText();JsonNode f=fields(def).get(field);
        if(f==null||!Set.of("integer","decimal").contains(f.path("type").asText())||!f.path("editable").asBoolean(true))throw new RpcException(-32602,"adjust仅接受可编辑数值字段");
        if(!p.path("delta").isNumber()||p.get("delta").decimalValue().signum()==0)throw new RpcException(-32602,"delta必须为非零数值");
        Map<String,Object> row=read(snapshot,def,recordId);checkVersion(row,v);
        if(!(row.get(field) instanceof Number))throw new RpcException(-32602,"当前字段没有数值");
        BigDecimal value=new BigDecimal(row.get(field).toString()).add(p.get("delta").decimalValue());
        if(value.signum()<0)throw new RpcException(-32011,"可用数量不足，调整后不能为负数");
        List<Map<String,String>> errors=new ArrayList<>();Object converted=convert(snapshot,def,f,mapper.valueToTree(value),errors);reject(errors);
        List<Object> args=new ArrayList<>(Arrays.asList(converted,AuthContext.actor(),recordId,v));
        String scope=rowScope.sql(snapshot,def,table(def),args);
        int n=jdbc.update("UPDATE "+q(table(def))+" SET "+q(field)+"=?,`version`=`version`+1,`updated_at`=CURRENT_TIMESTAMP,`updated_by`=? WHERE `id`=? AND `version`=?"+alive(def)+scope,args.toArray());
        if(n!=1)conflict();return read(snapshot,def,recordId);
    }
    private Map<String,Object> transition(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        checkKeys(p,Set.of("id","to","version","reason"));
        if(def.path("model").path("appendOnly").asBoolean())throw new RpcException(-32602,"只追加模型不允许修改状态；请将状态单据与事件流水分开建模");
        JsonNode state=def.path("stateMachine");if(state.isMissingNode())throw new RpcException(-32602,"模型未声明状态机");
        Object recordId=id(def,p);long v=version(p);Map<String,Object> row=read(snapshot,def,recordId);checkVersion(row,v);
        String field=state.path("field").asText(),from=Objects.toString(row.get(field),""),to=p.path("to").asText();
        JsonNode matched=null;List<String> allowed=new ArrayList<>();
        for(JsonNode rule:state.path("transitions"))if(rule.path("from").asText().equals(from)){allowed.add(rule.path("to").asText());if(rule.path("to").asText().equals(to))matched=rule;}
        if(matched==null)throw new RpcException(-32013,"非法状态转移",Map.of("from",from,"to",to,"allowed",allowed));
        if(!Conditions.test(matched.get("guard"),row))throw new RpcException(-32011,"状态转移守卫未通过");
        List<Object> args=new ArrayList<>(Arrays.asList(to,AuthContext.actor(),recordId,v));
        String scope=rowScope.sql(snapshot,def,table(def),args);
        int n=jdbc.update("UPDATE "+q(table(def))+" SET "+q(field)+"=?,`version`=`version`+1,`updated_at`=CURRENT_TIMESTAMP,`updated_by`=? WHERE `id`=? AND `version`=?"+alive(def)+scope,args.toArray());
        if(n!=1)conflict();
        String reason=p.path("reason").asText("");if(reason.length()>1000)throw new RpcException(-32602,"原因长度不能超过1000");
        jdbc.update("INSERT INTO t_state_event(model_code,record_id,from_state,to_state,reason,actor) VALUES (?,?,?,?,?,?)",def.path("model").path("code").asText(),recordId.toString(),from,to,reason,AuthContext.actor());
        return read(snapshot,def,recordId);
    }
    private Map<String,Object> read(MetaSnapshot snapshot,JsonNode def,Object id) {
        List<Object> args=new ArrayList<>();args.add(id);
        String scope=rowScope.sql(snapshot,def,table(def),args);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM "+q(table(def))+" WHERE `id`=?"+alive(def)+scope,args.toArray());
        if(rows.isEmpty())throw new RpcException(-32002,"记录不存在",Map.of("id",id));
        return normalize(def,rows.get(0));
    }
    private Object list(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        checkKeys(p,Set.of("page","pageSize","sortField","order","filters"));
        int page=integer(p,"page",1,1,Integer.MAX_VALUE),size=integer(p,"pageSize",20,1,200);
        Filter filter=scoped(snapshot,def,filter(def,p.get("filters")));Map<String,JsonNode> fs=fields(def);
        String sort=p.path("sortField").asText("id"),order=p.path("order").asText("desc").toLowerCase(Locale.ROOT);
        if(!sort.equals("id")&&(!fs.containsKey(sort)||!fs.get(sort).path("listable").asBoolean()))throw new RpcException(-32602,"排序字段必须声明listable");
        if(!Set.of("asc","desc").contains(order))throw new RpcException(-32602,"order必须为asc或desc");
        String where=" WHERE 1=1"+alive(def)+filter.sql;
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+q(table(def))+where,Long.class,filter.args.toArray());
        List<Object> args=new ArrayList<>(filter.args);args.add(size);args.add(((long)page-1)*size);
        var rows=jdbc.queryForList("SELECT * FROM "+q(table(def))+where+" ORDER BY "+q(sort)+" "+order+" LIMIT ? OFFSET ?",args.toArray());
        return Map.of("total",total,"records",rows.stream().map(r->normalize(def,r)).toList(),"page",page,"pageSize",size);
    }
    private Object aggregate(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        checkKeys(p,Set.of("groupBy","metrics","filters","firstBy"));Map<String,JsonNode> fs=fields(def);
        List<String> group=new ArrayList<>();
        if(p.has("groupBy")&&!p.get("groupBy").isArray())throw new RpcException(-32602,"groupBy必须为数组");
        for(JsonNode key:p.path("groupBy")){String f=key.asText();if(!fs.containsKey(f))throw new RpcException(-32602,"未知分组字段");group.add(f);}
        if(group.size()>4)throw new RpcException(-32602,"最多支持4个分组字段");
        List<String> select=new ArrayList<>(group.stream().map(com.platform.meta.Definitions::q).toList());
        JsonNode metrics=p.path("metrics");if(!metrics.isArray()||metrics.isEmpty()||metrics.size()>10)throw new RpcException(-32602,"metrics需要1~10项");
        Map<String,String> resultNames=new HashMap<>();
        group.forEach(name->resultNames.put(name.toLowerCase(Locale.ROOT),name));
        Set<String> aliases=new HashSet<>(resultNames.keySet());
        for(JsonNode metric:metrics) {
            String op=metric.path("op").asText(),field=metric.path("field").asText(),as=metric.path("as").asText(op+"_"+(field.isEmpty()?"all":field));
            if(!as.matches("[a-zA-Z][a-zA-Z0-9_]*")||!aliases.add(as.toLowerCase(Locale.ROOT)))throw new RpcException(-32602,"聚合别名非法或重复");
            resultNames.put(as.toLowerCase(Locale.ROOT),as);
            if(!Set.of("count","sum","avg").contains(op))throw new RpcException(-32602,"不支持的聚合函数");
            if(!field.isEmpty()&&!fs.containsKey(field))throw new RpcException(-32602,"聚合字段不存在");
            if(!op.equals("count")&&(field.isEmpty()||!Set.of("integer","decimal").contains(fs.get(field).path("type").asText())))throw new RpcException(-32602,"sum/avg要求数值字段");
            String expression=op.toUpperCase(Locale.ROOT)+"("+(field.isEmpty()?"*":q(field))+")";
            select.add((op.equals("sum")?"COALESCE("+expression+",0)":expression)+" AS "+q(as));
        }
        Filter filter=filter(def,p.get("filters"));String groupSql=group.isEmpty()?"":" GROUP BY "+String.join(",",group.stream().map(com.platform.meta.Definitions::q).toList());
        String source=q(table(def)),where=" WHERE 1=1";
        List<Object> args=new ArrayList<>();
        if(p.has("firstBy")) {
            String first=p.path("firstBy").asText();
            if(!p.get("firstBy").isTextual()||!fs.containsKey(first)||fs.containsKey("__first_rank")
                    || !Set.of("ref","string","integer","enum").contains(fs.get(first).path("type").asText()))
                throw new RpcException(-32602,"firstBy必须为已声明的标识字段");
            String scope=rowScope.sql(snapshot,def,table(def),args);
            source="(SELECT "+q(table(def))+".*,ROW_NUMBER() OVER (PARTITION BY "+q(first)+" ORDER BY `id` ASC) AS `__first_rank` FROM "+q(table(def))+" WHERE "+q(first)+" IS NOT NULL"+alive(def)+scope+") first_records";
            where+=" AND `__first_rank`=1";
        } else {
            where+=alive(def)+rowScope.sql(snapshot,def,table(def),args);
        }
        where+=filter.sql;args.addAll(filter.args);
        String orderSql=group.isEmpty()?"":" ORDER BY "+String.join(",",group.stream().map(com.platform.meta.Definitions::q).toList());
        // Aggregate results are bounded to prevent unbounded chart/group scans in the client.
        List<Map<String,Object>> result=jdbc.queryForList("SELECT "+String.join(",",select)+" FROM "+source+where+groupSql+orderSql+" LIMIT 1001",args.toArray());
        List<Map<String,Object>> normalized=result.stream().limit(1000).map(raw->{
            Map<String,Object> row=new LinkedHashMap<>();
            raw.forEach((key,value)->row.put(resultNames.getOrDefault(key.toLowerCase(Locale.ROOT),key),value));
            return row;
        }).toList();
        return Map.of("records",normalized,"truncated",result.size()>1000);
    }
    private Object related(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        checkKeys(p,Set.of("id","model","field","page","pageSize","paths","filters"));Object recordId=id(def,p);read(snapshot,def,recordId);
        String code=def.path("model").path("code").asText(),target=p.path("model").asText();JsonNode targetDef=model(snapshot,target);
        JsonNode paths=p.get("paths");
        if(paths==null){
            List<String> refs=fields(targetDef).entrySet().stream().filter(e->e.getValue().path("type").asText().equals("ref")&&e.getValue().path("ref").asText().equals(code)).map(Map.Entry::getKey).toList();
            String field=p.path("field").asText(refs.size()==1?refs.get(0):"");
            if(!refs.contains(field))throw new RpcException(-32602,"请指定目标模型中引用当前模型的ref字段");
            paths=mapper.valueToTree(List.of(List.of(Map.of("model",target,"field",field,"direction","in"))));
        }
        var validated=RelationPaths.validate(snapshot.models(),code,target,paths);
        List<String> queries=new ArrayList<>();List<Object> args=new ArrayList<>();int pathIndex=0;
        for(var path:validated){
            String alias="r"+pathIndex+"_0";JsonNode current=def;
            StringBuilder from=new StringBuilder(" FROM "+q(table(def))+" "+alias);
            StringBuilder where=new StringBuilder(" WHERE "+alias+".`id`=?"+aliveAt(def,alias));args.add(recordId);where.append(rowScope.sql(snapshot,def,alias,args));int hopIndex=0;
            for(var hop:path){
                JsonNode next=model(snapshot,hop.model());String nextAlias="r"+pathIndex+"_"+(++hopIndex);
                from.append(" JOIN ").append(q(table(next))).append(' ').append(nextAlias).append(" ON ");
                from.append(hop.incoming()?nextAlias+"."+q(hop.field())+"="+alias+".`id`":alias+"."+q(hop.field())+"="+nextAlias+".`id`");
                Filter filter=filter(next,hop.filters());where.append(aliveAt(next,nextAlias)).append(at(filter.sql,nextAlias));args.addAll(filter.args);where.append(rowScope.sql(snapshot,next,nextAlias,args));
                current=next;alias=nextAlias;
            }
            queries.add("SELECT "+alias+".`id`"+from+where);pathIndex++;
        }
        int page=integer(p,"page",1,1,Integer.MAX_VALUE),size=integer(p,"pageSize",20,1,200);
        Filter finalFilter=filter(targetDef,p.get("filters"));args.addAll(finalFilter.args);
        String selection=" FROM "+q(table(targetDef))+" dest WHERE dest.`id` IN ("+String.join(" UNION ",queries)+")"+aliveAt(targetDef,"dest")+at(finalFilter.sql,"dest");
        long count=jdbc.queryForObject("SELECT COUNT(*)"+selection,Long.class,args.toArray());
        List<Object> listArgs=new ArrayList<>(args);listArgs.add(size);listArgs.add(((long)page-1)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT dest.*"+selection+" ORDER BY dest.`id` DESC LIMIT ? OFFSET ?",listArgs.toArray());
        return Map.of("total",count,"records",rows.stream().map(r->normalize(targetDef,r)).toList());
    }
    private String at(String sql,String alias){return sql.replaceAll("`([a-zA-Z][a-zA-Z0-9_]*)`",alias+".`$1`");}
    private String aliveAt(JsonNode def,String alias){return logicalDelete(def)?" AND "+alias+".`deleted`=FALSE":"";}
    private Object batchCreate(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        checkKeys(p,Set.of("records"));JsonNode records=p.path("records");
        if(!records.isArray()||records.isEmpty()||records.size()>200)throw new RpcException(-32602,"records必须为1~200条记录的数组");
        List<Object> result=new ArrayList<>();for(JsonNode r:records){if(!r.isObject())throw new RpcException(-32602,"批量记录必须为对象");result.add(execute(snapshot,def.path("model").path("code").asText(),"create",r));}
        return Map.of("records",result);
    }
    private Object batchDelete(MetaSnapshot snapshot,JsonNode def,JsonNode p) {
        checkKeys(p,Set.of("records"));JsonNode records=p.path("records");
        if(!records.isArray()||records.isEmpty()||records.size()>200)throw new RpcException(-32602,"records必须为含id/version的1~200项数组");
        List<Object> result=new ArrayList<>();for(JsonNode r:records){if(!r.isObject())throw new RpcException(-32602,"批量记录必须为对象");result.add(execute(snapshot,def.path("model").path("code").asText(),"delete",r));}return Map.of("records",result);
    }
    private record Filter(String sql,List<Object> args) { }
    private Filter scoped(MetaSnapshot snapshot,JsonNode def,Filter filter) {
        List<Object> args=new ArrayList<>(filter.args);
        return new Filter(filter.sql+rowScope.sql(snapshot,def,table(def),args),args);
    }
    private Filter filter(JsonNode def,JsonNode filters) {
        if(filters==null||filters.isNull())return new Filter("",List.of());
        if(!filters.isArray()||filters.size()>30)throw new RpcException(-32602,"filters必须为最多30项的数组");
        StringBuilder sql=new StringBuilder();List<Object> args=new ArrayList<>();Map<String,JsonNode> fs=fields(def);
        for(JsonNode filter:filters) {
            String field=filter.path("field").asText(),op=filter.path("op").asText();JsonNode value=filter.get("value"),f=fs.get(field);
            if(f==null||!f.path("searchable").asBoolean())throw new RpcException(-32602,"过滤字段必须声明searchable："+field);
            if(value==null)throw new RpcException(-32602,"过滤条件缺少value");
            if(!Set.of("eq","ne","like","gt","ge","lt","le","in","between").contains(op))throw new RpcException(-32602,"非法过滤操作符："+op);
            if(value.isNull()&&(op.equals("eq")||op.equals("ne"))){sql.append(" AND ").append(q(field)).append(op.equals("eq")?" IS NULL":" IS NOT NULL");continue;}
            sql.append(" AND ").append(q(field));
            if(op.equals("in")||op.equals("between")) {
                if(!value.isArray()||value.isEmpty()||value.size()>200||(op.equals("between")&&value.size()!=2))throw new RpcException(-32602,"in需要1~200项数组，between需要两项数组");
                if(op.equals("in"))sql.append(" IN (").append(String.join(",",Collections.nCopies(value.size(),"?"))).append(")");else sql.append(" BETWEEN ? AND ?");
                for(JsonNode item:value)args.add(filterValue(f,item));
            } else {
                String operator=switch(op){case "eq"->"=";case "ne"->"<>";case "like"->"LIKE";case "gt"->">";case "ge"->">=";case "lt"->"<";case "le"->"<=";default->throw new RpcException(-32602,"非法操作符");};
                sql.append(" ").append(operator).append(" ?");
                if(op.equals("like")) {if(!Set.of("string","text","enum").contains(f.path("type").asText())||!value.isTextual())throw new RpcException(-32602,"like仅支持文本字段");args.add("%"+value.asText()+"%");}
                else args.add(filterValue(f,value));
            }
        }
        return new Filter(sql.toString(),args);
    }
    private Object filterValue(JsonNode field,JsonNode value) {
        if(value==null||value.isNull()||value.isContainerNode())throw new RpcException(-32602,"过滤值类型无效");
        try {return switch(field.path("type").asText()) {
            case "integer"-> {if(!value.isIntegralNumber()||!value.canConvertToLong())throw new IllegalArgumentException();yield value.longValue();}
            case "decimal"->{if(!value.isNumber())throw new IllegalArgumentException();yield value.decimalValue();}
            case "boolean"->{if(!value.isBoolean())throw new IllegalArgumentException();yield value.booleanValue();}
            case "date"->java.sql.Date.valueOf(LocalDate.parse(value.asText()));
            case "datetime"->Timestamp.valueOf(LocalDateTime.parse(value.asText()));
            case "ref"->value.isNumber()?value.longValue():value.asText();
            default->{if(!value.isTextual())throw new IllegalArgumentException();yield value.asText();}
        };}catch(Exception ex){throw new RpcException(-32602,"过滤值与字段类型不匹配："+field.path("code").asText());}
    }
    private Object convert(MetaSnapshot snapshot,JsonNode def,JsonNode f,JsonNode value,List<Map<String,String>> errors) {
        String key=f.path("code").asText(),name=f.path("name").asText(key),type=f.path("type").asText();
        if(value==null||value.isNull()||(value.isTextual()&&value.asText().isBlank())) {
            if(f.path("required").asBoolean())errors.add(fieldError(key,name+"不能为空"));return null;
        }
        Object result;
        try {
            result=switch(type) {
                case "string","text","enum"->{if(!value.isTextual())throw new IllegalArgumentException("必须为文本");yield value.textValue();}
                case "integer"->{if(!value.isNumber()||value.decimalValue().stripTrailingZeros().scale()>0)throw new IllegalArgumentException("必须为整数");yield value.decimalValue().longValueExact();}
                case "decimal"->{if(!value.isNumber())throw new IllegalArgumentException("必须为数值");BigDecimal n=value.decimalValue();if(n.scale()>f.path("scale").asInt(2)||n.precision()-n.scale()>f.path("precision").asInt(18)-f.path("scale").asInt(2))throw new IllegalArgumentException("数值精度超出限制");yield n;}
                case "boolean"->{if(!value.isBoolean())throw new IllegalArgumentException("必须为布尔值");yield value.booleanValue();}
                case "date"->java.sql.Date.valueOf(LocalDate.parse(value.asText()));
                case "datetime"->Timestamp.valueOf(LocalDateTime.parse(value.asText()));
                case "ref"->{JsonNode target=model(snapshot,f.path("ref").asText());Object ref=recordId(target,value);read(snapshot,target,ref);yield ref;}
                default->throw new IllegalArgumentException("不支持的字段类型");
            };
            if(result instanceof String s) {
                int length=s.codePointCount(0,s.length());
                int max=f.path("maxLength").asInt(type.equals("string")?f.path("length").asInt(255):type.equals("enum")?100:Integer.MAX_VALUE);
                int min=f.path("minLength").asInt(f.path("min").asInt(0));
                if(length>max||length<min)throw new IllegalArgumentException("长度应在"+min+"~"+max+"之间");
                if(f.has("pattern")&&!java.util.regex.Pattern.compile(f.get("pattern").asText()).matcher(s).matches())throw new IllegalArgumentException("格式不符合要求");
                if(type.equals("enum")) {boolean valid=false;for(JsonNode o:f.path("options"))if(o.path("value").asText().equals(s))valid=true;if(!valid)throw new IllegalArgumentException("枚举值不在允许范围内");}
            }
            if(result instanceof Number n) {
                BigDecimal v=new BigDecimal(n.toString());
                if(f.has("min")&&v.compareTo(f.get("min").decimalValue())<0)throw new IllegalArgumentException("不能小于"+f.get("min"));
                if(f.has("max")&&v.compareTo(f.get("max").decimalValue())>0)throw new IllegalArgumentException("不能大于"+f.get("max"));
            }
        }catch(Exception ex){errors.add(fieldError(key,name+"："+(ex instanceof RpcException?ex.getMessage():Objects.toString(ex.getMessage(),"类型不正确"))));return null;}
        return result;
    }
    private void validateUnique(JsonNode def,Map<String,Object> values,Object id) {
        List<Map<String,String>> errors=new ArrayList<>();
        for(var e:fields(def).entrySet())if(e.getValue().path("unique").asBoolean()&&values.get(e.getKey())!=null) {
            List<Object> args=new ArrayList<>();args.add(values.get(e.getKey()));if(id!=null)args.add(id);
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+q(table(def))+" WHERE "+q(e.getKey())+"=?"+(id==null?"":" AND `id`<>?"),Long.class,args.toArray());
            if(count>0)errors.add(fieldError(e.getKey(),e.getValue().path("name").asText()+"已存在"));
        }
        if(!errors.isEmpty())throw new RpcException(-32003,"唯一约束冲突",Map.of("fields",errors));
    }
    private Map<String,Object> normalize(JsonNode def,Map<String,Object> raw) {
        Map<String,String> names=new HashMap<>();for(String key:fields(def).keySet())names.put(key.toLowerCase(Locale.ROOT),key);
        Map<String,Object> row=new LinkedHashMap<>();
        raw.forEach((key,value)->{
            String name=names.getOrDefault(key.toLowerCase(Locale.ROOT),key.toLowerCase(Locale.ROOT));
            if(!names.containsKey(key.toLowerCase(Locale.ROOT))&&!Set.of("id","version","created_at","updated_at","created_by","updated_by","deleted").contains(name))return;
            if(value instanceof Timestamp ts)value=ts.toLocalDateTime().toString();
            else if(value instanceof java.sql.Date date)value=date.toLocalDate().toString();
            else if(value instanceof Clob clob)try{value=clob.getSubString(1,(int)clob.length());}catch(SQLException ex){throw new IllegalStateException(ex);}
            row.put(name,value);
        });return row;
    }
    private Object id(JsonNode def,JsonNode p) {return recordId(def,p.get("id"));}
    private Object recordId(JsonNode def,JsonNode id) {
        if(id==null||id.isNull())throw new RpcException(-32602,"id必填");
        if(def.path("model").path("idType").asText("auto").equals("uuid")) {if(!id.isTextual()||id.asText().isBlank()||id.asText().length()>64)throw new RpcException(-32602,"id必须为非空字符串");return id.asText();}
        if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<1)throw new RpcException(-32602,"id必须为正整数");return id.longValue();
    }
    private long version(JsonNode p) {if(!p.path("version").isIntegralNumber()||!p.get("version").canConvertToLong()||p.get("version").longValue()<0)throw new RpcException(-32602,"写操作必须携带记录的version");return p.get("version").longValue();}
    private void checkVersion(Map<String,Object> row,long version) {if(((Number)row.get("version")).longValue()!=version)conflict();}
    private void conflict() {throw new RpcException(-32006,"记录已被其他操作修改，请刷新后重试");}
    private void mutable(JsonNode def) {if(def.path("model").path("appendOnly").asBoolean())throw new RpcException(-32602,"只追加模型禁止修改或删除");}
    private String alive(JsonNode def) {return logicalDelete(def)?" AND `deleted`=FALSE":"";}
    private int integer(JsonNode p,String key,int fallback,int min,int max) {JsonNode n=p.get(key);if(n==null)return fallback;if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min||n.intValue()>max)throw new RpcException(-32602,key+"超出允许范围");return n.intValue();}
    private void checkKeys(JsonNode p,Set<String> keys) {p.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw new RpcException(-32602,"未声明或不允许的参数："+k);});}
    private Map<String,String> fieldError(String key,String message) {return Map.of("code",key,"message",message);}
    private void reject(List<Map<String,String>> errors) {if(!errors.isEmpty())throw new RpcException(-32001,"数据校验失败",Map.of("fields",errors));}
}
