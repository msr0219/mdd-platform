package com.platform.auth;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.platform.meta.MetaSnapshot;
import com.platform.meta.MetaEngine;
import com.platform.rpc.RpcException;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RpcAuthorization {
    private final MetaEngine engine;
    private final ApplicationAccess applications;
    public RpcAuthorization(MetaEngine engine,ApplicationAccess applications){this.engine=engine;this.applications=applications;}
    private Set<String> permissions(){return AuthContext.current()==null?Set.of():AuthContext.current().permissions();}
    private boolean administrator(){return AuthContext.current()==null||AuthContext.current().roles().contains("admin");}
    public boolean modelRead(String code){return modelRead(engine.snapshot(),code);}
    private boolean modelRead(MetaSnapshot s,String code){return administrator()||applications.read(s,permissions(),code);}
    public boolean modelWrite(MetaSnapshot s,String code){return administrator()||applications.write(s,permissions(),code);}
    public boolean manageAttachments(MetaSnapshot s,String code){return administrator()||applications.attachments(s,permissions(),code);}
    private boolean viewRead(MetaSnapshot s,JsonNode v){return administrator()||applications.view(s,permissions(),v.path("code").asText());}
    public boolean allows(String permission){return AuthContext.current()==null||AuthContext.current().allows(permission);}
    public boolean allowed(MetaSnapshot s,String method,JsonNode p){
        if(method.equals("meta.reload"))return allows("meta:reload");
        if(method.equals("meta.getService"))return allowed(s,"service."+p.path("code").asText(),p);
        if(method.equals("meta.getModel"))return modelRead(s,p.path("code").asText());
        if(method.equals("meta.getEvents"))return modelRead(s,p.path("model").asText());
        if(method.equals("meta.getViews")) {
            List<String> codes=new ArrayList<>();if(p.has("code"))codes.add(p.get("code").asText());else p.path("codes").forEach(c->codes.add(c.asText()));
            return codes.stream().allMatch(c->{JsonNode v=s.views().get(c);return v==null||viewRead(s,v);});
        }
        if(method.startsWith("meta."))return true;
        String[] bits=method.split("\\.");if(bits.length!=2)return true;
        if(bits[0].equals("service")){JsonNode def=s.services().get(bits[1]);if(def!=null&&def.path("internalOnly").asBoolean())return false;return administrator()||applications.invoke(s,permissions(),bits[1]);}
        boolean read=Set.of("list","read","related","aggregate").contains(bits[1]);
        if(read&&bits[1].equals("related")&&!modelRead(s,p.path("model").asText()))return false;
        if(read&&bits[1].equals("related")&&p.has("paths"))for(JsonNode path:p.get("paths"))for(JsonNode hop:path)if(!modelRead(s,hop.path("model").asText()))return false;
        JsonNode model=s.models().get(bits[0]);
        if(!read&&model!=null&&model.path("model").path("serviceOnly").asBoolean()&&!administrator())return false;
        return read?modelRead(s,bits[0]):modelWrite(s,bits[0]);
    }
    public void require(MetaSnapshot s,String method,JsonNode p){if(!allowed(s,method,p==null?JsonNodeFactory.instance.objectNode():p))throw new RpcException(-32021,"没有执行此操作的权限");}
    public Object filter(MetaSnapshot s,String method,Object result){
        if(AuthContext.current()==null)return result;
        if(method.equals("meta.getMenus"))return Map.of("version",s.version(),"menus",menus(s,s.menus()));
        if(method.equals("meta.status")){Map<String,Object> status=new LinkedHashMap<>((Map<String,Object>)result);status.put("models",s.models().keySet().stream().filter(c->modelRead(s,c)).sorted().toList());status.put("views",s.views().values().stream().filter(v->viewRead(s,v)).map(v->v.path("code").asText()).sorted().toList());status.put("services",s.services().keySet().stream().filter(c->allowed(s,"service."+c,JsonNodeFactory.instance.objectNode())).sorted().toList());status.put("authentication","session-rbac");if(!allows("meta:inspect"))status.remove("metaDirectory");return status;}
        if(method.equals("meta.getViews")){Map<?,?> response=(Map<?,?>)result;List<JsonNode> views=new ArrayList<>();for(JsonNode original:(List<JsonNode>)response.get("views")){ObjectNode v=original.deepCopy();filterButtons(s,v,"buttons");if(v.path("table").isObject())filterButtons(s,(ObjectNode)v.get("table"),"buttons");views.add(v);}return Map.of("version",s.version(),"views",views);}
        return result;
    }
    private List<JsonNode> menus(MetaSnapshot s,List<JsonNode> nodes){List<JsonNode> result=new ArrayList<>();for(JsonNode n:nodes){if(!allows(n.path("permission").asText("")))continue;ObjectNode m=n.deepCopy();if(n.path("children").isArray()){List<JsonNode> children=new ArrayList<>();n.get("children").forEach(children::add);var visible=menus(s,children);if(visible.isEmpty())continue;m.set("children",JsonNodeFactory.instance.arrayNode().addAll(visible));}else{if(!administrator()&&!applications.menu(permissions(),n.path("code").asText()))continue;}result.add(m);}return result;}
    private void filterButtons(MetaSnapshot s,ObjectNode owner,String key){if(!owner.path(key).isArray())return;ArrayNode visible=JsonNodeFactory.instance.arrayNode();for(JsonNode b:owner.get(key)){if(!allows(b.path("permission").asText("")))continue;String action=b.path("action").asText();if(action.startsWith("service:")){String call=action.substring(8);if(!call.contains("."))call="service."+call;if(!allowed(s,call,JsonNodeFactory.instance.objectNode()))continue;}if(action.startsWith("openForm:")){JsonNode v=s.views().get(action.substring(9));if(v!=null&&(!viewRead(s,v)||!allowed(s,v.path("model").asText()+".create",JsonNodeFactory.instance.objectNode())))continue;}visible.add(b);}owner.set(key,visible);}
}
