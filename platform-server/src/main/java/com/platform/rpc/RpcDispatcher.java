package com.platform.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.meta.MetaEngine;
import com.platform.meta.MetaSnapshot;
import com.platform.service.CrudExecutor;
import com.platform.service.ServiceOrchestrator;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RpcDispatcher {
    private final MetaEngine engine;
    private final CrudExecutor crud;
    private final ServiceOrchestrator services;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public RpcDispatcher(MetaEngine engine,CrudExecutor crud,@Lazy ServiceOrchestrator services,JdbcTemplate jdbc,ObjectMapper mapper) {
        this.engine=engine;this.crud=crud;this.services=services;this.jdbc=jdbc;this.mapper=mapper;
    }
    public Object dispatch(MetaSnapshot snapshot,String method,JsonNode params) {
        if(params==null||params.isNull())params=mapper.createObjectNode();
        if(!params.isObject())throw new RpcException(-32602,"params必须为命名参数对象");
        if(method.startsWith("meta."))return metadata(snapshot,method,params);
        String[] parts=method.split("\\.",-1);
        if(parts.length!=2)throw new RpcException(-32601,"方法不存在："+method);
        if(parts[0].equals("service"))return services.invoke(snapshot,parts[1],params);
        if(!snapshot.models().containsKey(parts[0])||!MetaEngine.ACTIONS.contains(parts[1]))throw new RpcException(-32601,"方法不存在："+method);
        return crud.execute(snapshot,parts[0],parts[1],params);
    }
    private Object metadata(MetaSnapshot s,String method,JsonNode p) {
        return switch(method) {
            case "meta.getMenus"->Map.of("version",s.version(),"menus",s.menus());
            case "meta.getModel"->{JsonNode model=s.models().get(p.path("code").asText());if(model==null)throw new RpcException(-32004,"模型不存在");yield model;}
            case "meta.getService"->{JsonNode service=s.services().get(p.path("code").asText());if(service==null)throw new RpcException(-32004,"服务不存在");yield service;}
            case "meta.getViews"->{
                List<JsonNode> result=new ArrayList<>();
                if(p.has("code"))result.add(view(s,p.get("code").asText()));
                else if(p.path("codes").isArray())for(JsonNode code:p.get("codes"))result.add(view(s,code.asText()));
                else throw new RpcException(-32602,"请提供code或codes数组");
                yield Map.of("version",s.version(),"views",result);
            }
            case "meta.status"->engine.status();
            case "meta.reload"->engine.reload();
            case "meta.getEvents"->{
                String model=p.path("model").asText(),id=p.path("id").asText();
                if(!s.models().containsKey(model)||id.isBlank())throw new RpcException(-32602,"请提供有效model与id");
                var lookup=mapper.createObjectNode();
                if(s.models().get(model).path("model").path("idType").asText("auto").equals("uuid"))lookup.put("id",id);
                else {try{lookup.put("id",Long.parseLong(id));}catch(NumberFormatException ex){throw new RpcException(-32602,"记录编号格式错误");}}
                crud.execute(s,model,"read",lookup);
                yield Map.of("records",jdbc.queryForList("SELECT id,model_code,record_id,from_state,to_state,reason,actor,created_at FROM t_state_event WHERE model_code=? AND record_id=? ORDER BY id DESC LIMIT 200",model,id));
            }
            default->throw new RpcException(-32601,"元数据方法不存在："+method);
        };
    }
    private JsonNode view(MetaSnapshot s,String code) {JsonNode view=s.views().get(code);if(view==null)throw new RpcException(-32004,"视图不存在："+code);return view;}
}
