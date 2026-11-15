package com.platform.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.meta.MetaSnapshot;
import org.springframework.stereotype.Component;
import java.util.*;

/** Application grants stay distinct from reference-data reads, so a dropdown never grants a menu. */
@Component
public class ApplicationAccess {
    public static final String CONFIGURED = "apps:configured";
    private static final Set<String> SYSTEM_PERMISSIONS=Set.of("data:scopeAll","meta:inspect","meta:reload","users:manage");
    public record Application(String code, String name, String view, String model, String modelName,
                              boolean canEdit, boolean canOperate, Set<String> formViews, Set<String> readModels,
                              Map<String,String> services, Set<String> queryServices, Set<String> operationReadModels) { }
    private record Catalog(MetaSnapshot snapshot, Map<String,Application> applications) { }
    private volatile Catalog cached;

    public Map<String,Application> applications(MetaSnapshot snapshot) {
        Catalog current=cached;
        if(current!=null && current.snapshot()==snapshot)return current.applications();
        Map<String,Application> result=new LinkedHashMap<>();
        for(JsonNode menu:snapshot.menus())collect(snapshot,menu,result);
        Map<String,Application> immutable=Collections.unmodifiableMap(result);
        cached=new Catalog(snapshot,immutable);
        return immutable;
    }
    private void collect(MetaSnapshot s,JsonNode menu,Map<String,Application> out) {
        if(menu.has("children")){menu.get("children").forEach(child->collect(s,child,out));return;}
        JsonNode view=s.views().get(menu.path("view").asText());
        if(view==null)return;
        String model=view.path("model").asText();JsonNode definition=s.models().get(model);
        if(definition==null)return;
        Set<String> forms=new TreeSet<>(),reads=new TreeSet<>(),operationReads=new TreeSet<>();
        reads.add(model);references(definition.path("fields"),reads);
        Map<String,String> services=new TreeMap<>();Set<String> queries=new TreeSet<>();
        List<JsonNode> buttons=new ArrayList<>();view.path("buttons").forEach(buttons::add);view.path("table").path("buttons").forEach(buttons::add);
        boolean editable=false;
        for(JsonNode button:buttons){
            String action=button.path("action").asText();
            if(action.startsWith("openForm:")){
                JsonNode form=s.views().get(action.substring(9));
                if(form!=null && form.path("model").asText().equals(model)){forms.add(action.substring(9));editable=true;}
            }
            if(!action.startsWith("service:"))continue;
            String call=action.substring(8);if(call.startsWith("service."))call=call.substring(8);
            if(call.startsWith(model+".")){editable=true;continue;}
            JsonNode service=s.services().get(call);
            if(service==null||service.path("internalOnly").asBoolean())continue;
            services.put(call,service.path("name").asText(call));references(service.path("params"),operationReads);
            if(service.path("permission").asText().equals("data:read")){queries.add(call);references(service.path("params"),reads);}
        }
        editable=editable&&!definition.path("model").path("serviceOnly").asBoolean();
        out.put(menu.path("code").asText(),new Application(menu.path("code").asText(),menu.path("name").asText(),
                view.path("code").asText(),model,definition.path("model").path("name").asText(model),editable,!services.isEmpty()||definition.path("model").path("attachments").asBoolean(),
                Set.copyOf(forms),Set.copyOf(reads),Collections.unmodifiableMap(services),Set.copyOf(queries),Set.copyOf(operationReads)));
    }
    private void references(JsonNode fields,Set<String> result){for(JsonNode field:fields)if(field.path("type").asText().equals("ref"))result.add(field.path("ref").asText());}
    public static String grant(String code,String action){return "app_"+code+":"+action;}
    private boolean has(Set<String> permissions,Application app,String action){return permissions.contains(grant(app.code(),action));}
    public boolean menu(Set<String> permissions,String code){return permissions.contains(grant(code,"view"));}
    public boolean view(MetaSnapshot s,Set<String> permissions,String code){return applications(s).values().stream().anyMatch(a->has(permissions,a,"view")&&(a.view().equals(code)||(has(permissions,a,"edit")&&a.formViews().contains(code))));}
    public boolean read(MetaSnapshot s,Set<String> permissions,String code){return applications(s).values().stream().anyMatch(a->has(permissions,a,"view")&&(a.readModels().contains(code)||(has(permissions,a,"operate")&&a.operationReadModels().contains(code))));}
    public boolean write(MetaSnapshot s,Set<String> permissions,String code){return applications(s).values().stream().anyMatch(a->a.canEdit()&&a.model().equals(code)&&has(permissions,a,"view")&&has(permissions,a,"edit"));}
    public boolean invoke(MetaSnapshot s,Set<String> permissions,String code){return applications(s).values().stream().anyMatch(a->has(permissions,a,"view")&&a.services().containsKey(code)&&(has(permissions,a,"operate")||a.queryServices().contains(code)));}
    public boolean attachments(MetaSnapshot s,Set<String> permissions,String model){return applications(s).values().stream().anyMatch(a->a.model().equals(model)&&has(permissions,a,"view")&&((a.canEdit()&&has(permissions,a,"edit"))||has(permissions,a,"operate")));}
    public Set<String> effectivePermissions(MetaSnapshot s,Set<String> permissions){
        Set<String> result=new TreeSet<>(permissions);
        for(String model:s.models().keySet()){
            if(read(s,permissions,model))result.add(model+":read");
            if(write(s,permissions,model))result.add(model+":write");
            if(attachments(s,permissions,model))result.add(model+":attachments");
        }
        return result;
    }
    /** Drop obsolete direct business grants at authentication as well as during persistent migration. */
    public Set<String> configuredPermissions(Collection<String> permissions){
        Set<String> result=new TreeSet<>();
        for(String p:permissions)if(SYSTEM_PERMISSIONS.contains(p)||p.startsWith("app_"))result.add(p);
        return result;
    }
    public List<String> migrate(MetaSnapshot s,Collection<String> old){
        Set<String> result=new TreeSet<>();
        boolean configured=old.contains(CONFIGURED)||old.stream().anyMatch(p->p.startsWith("app_"));
        for(String p:SYSTEM_PERMISSIONS)if(configured?old.contains(p):legacyAllows(old,p))result.add(p);
        if(configured){old.stream().filter(p->p.startsWith("app_")).forEach(result::add);return List.copyOf(result);}
        for(Application app:applications(s).values()){
            if(!legacyAllows(old,"data:read")&&!legacyAllows(old,app.model()+":read")&&!legacyAllows(old,app.model()+":view"))continue;
            result.add(grant(app.code(),"view"));
            boolean write=legacyAllows(old,"data:write")||legacyAllows(old,app.model()+":write");
            if(app.canEdit()&&write)result.add(grant(app.code(),"edit"));
            boolean allServices=!app.services().isEmpty()&&app.services().keySet().stream().allMatch(code->{
                JsonNode def=s.services().get(code);
                return legacyAllows(old,"service:invoke")||legacyAllows(old,code+":invoke")||(def.has("permission")&&legacyAllows(old,def.get("permission").asText()));
            });
            if(app.canOperate()&&(allServices||(app.services().isEmpty()&&write)))result.add(grant(app.code(),"operate"));
        }
        return List.copyOf(result);
    }
    private boolean legacyAllows(Collection<String> permissions,String value){return permissions.contains("*")||permissions.contains(value)||permissions.contains(value.substring(0,value.indexOf(':'))+":*");}
    public void validate(MetaSnapshot s,List<String> permissions){
        for(String permission:permissions){
            if(permission.startsWith("app_")){
                String[] parts=permission.split(":");Application app=applications(s).get(parts[0].substring(4));String action=parts[1];
                if(app==null||!Set.of("view","edit","operate").contains(action))throw AuthService.fail(400,"应用授权无效，请刷新应用列表后重试");
                if(!permissions.contains(grant(app.code(),"view")))throw AuthService.fail(400,"应用操作授权必须同时允许访问该应用");
                if((action.equals("edit")&&!app.canEdit())||(action.equals("operate")&&!app.canOperate()))throw AuthService.fail(400,"该应用不支持所选操作权限");
            }else if(!SYSTEM_PERMISSIONS.contains(permission)){
                throw AuthService.fail(400,"业务权限只能通过应用授权配置");
            }
        }
    }
}
