package com.platform.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.rpc.RpcException;
import java.util.*;
import static com.platform.meta.Definitions.fields;

/** A closed vocabulary of declared ref hops; no table, column or SQL expression comes from a caller. */
public final class RelationPaths {
    public record Hop(String model, String field, boolean incoming, JsonNode filters) { }
    private RelationPaths() { }
    public static List<List<Hop>> validate(Map<String,JsonNode> models,String source,String target,JsonNode paths) {
        if(!models.containsKey(source)||!models.containsKey(target))throw invalid("关联模型不存在");
        if(paths==null||!paths.isArray()||paths.isEmpty()||paths.size()>4)throw invalid("paths必须包含1~4条路径");
        List<List<Hop>> result=new ArrayList<>();
        for(JsonNode path:paths){
            if(!path.isArray()||path.isEmpty()||path.size()>4)throw invalid("每条关联路径需要1~4个跳转");
            String current=source;List<Hop> hops=new ArrayList<>();
            for(JsonNode hop:path){
                if(!hop.isObject())throw invalid("关联跳转必须为对象");
                hop.fieldNames().forEachRemaining(key->{if(!Set.of("model","field","direction","filters").contains(key))throw invalid("未知关联跳转参数："+key);});
                String next=hop.path("model").asText(),field=hop.path("field").asText(),direction=hop.path("direction").asText();
                if(!hop.path("model").isTextual()||!hop.path("field").isTextual()||!models.containsKey(next)||!Set.of("in","out").contains(direction))throw invalid("关联跳转模型、字段或方向非法");
                boolean incoming=direction.equals("in");
                JsonNode ref=fields(models.get(incoming?next:current)).get(field);
                if(ref==null||!ref.path("type").asText().equals("ref")||!ref.path("ref").asText().equals(incoming?current:next))throw invalid("关联跳转必须沿已声明的ref字段");
                if(hop.has("filters")) {
                    if(!hop.get("filters").isArray()||hop.get("filters").size()>30)throw invalid("关联过滤须为最多30项数组");
                    for(JsonNode f:hop.get("filters")){
                        JsonNode definition=fields(models.get(next)).get(f.path("field").asText());
                        if(definition==null||!definition.path("searchable").asBoolean()||!Set.of("eq","ne","like","gt","ge","lt","le","in","between").contains(f.path("op").asText())||!f.has("value"))throw invalid("关联过滤字段或操作符无效");
                    }
                }
                hops.add(new Hop(next,field,incoming,hop.get("filters")));current=next;
            }
            if(!current.equals(target))throw invalid("关联路径终点与model不一致");
            result.add(List.copyOf(hops));
        }
        return List.copyOf(result);
    }
    private static RpcException invalid(String message){return new RpcException(-32602,message);}
}
