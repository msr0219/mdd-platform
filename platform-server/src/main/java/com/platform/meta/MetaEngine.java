package com.platform.meta;

import com.platform.auth.RowScope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.dao.DdlSynchronizer;
import com.platform.rpc.RpcException;
import com.platform.service.extension.ModelCrudRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static com.platform.meta.Definitions.*;

@Component
public class MetaEngine {
    public static final Set<String> ACTIONS = Set.of("create","read","update","delete","list","batchCreate","batchDelete","adjust","transition","aggregate","related");
    private static final Set<String> TYPES = Set.of("string","text","integer","decimal","boolean","date","datetime","enum","ref");
    private static final Set<String> RESERVED = Set.of("id","version","created_by","created_at","updated_by","updated_at","deleted");
    private final ObjectMapper mapper;
    private final DdlSynchronizer ddl;
    private final Path directory;
    private final ModelCrudRegistry crudHandlers;
    private volatile MetaSnapshot current;
    public MetaEngine(ObjectMapper mapper, DdlSynchronizer ddl, @Value("${platform.meta-dir:../meta}") String directory, ModelCrudRegistry crudHandlers) {
        this.mapper=mapper; this.ddl=ddl; this.directory=Path.of(directory).toAbsolutePath().normalize();this.crudHandlers=crudHandlers;
    }
    @PostConstruct public void initialize() { reload(); }
    public MetaSnapshot snapshot() { return current; }
    public Map<String,Object> status() {
        MetaSnapshot s=current;
        return Map.of("version",s.version(),"models",s.models().keySet().stream().sorted().toList(),
                "views",s.views().keySet().stream().sorted().toList(),"services",s.services().keySet().stream().sorted().toList(),
                "menuCount",countMenus(s.menus()),"metaDirectory",directory.toString(),"authentication","local-demo-only");
    }
    private int countMenus(List<JsonNode> menus) { int n=menus.size(); for(JsonNode m:menus) { List<JsonNode> children=new ArrayList<>();m.path("children").forEach(children::add);n+=countMenus(children); }return n; }
    public synchronized Map<String,Object> reload() {
        List<Map<String,String>> errors=new ArrayList<>();
        Map<String,JsonNode> models=new LinkedHashMap<>(),views=new LinkedHashMap<>(),services=new LinkedHashMap<>();
        List<JsonNode> menus=new ArrayList<>(); Map<String,String> locations=new HashMap<>();
        load("models",models,menus,locations,errors); load("views",views,menus,locations,errors);
        load("services",services,menus,locations,errors); load("menus",null,menus,locations,errors);
        if(models.isEmpty()) error(errors,"models","$","至少需要一个模型");
        validateModels(models,locations,errors); validateViews(models,views,services,locations,errors);
        validateMenus(menus,views,new HashSet<>(),errors); validateServices(models,services,locations,errors);
        for(String code:crudHandlers.modelCodes())if(!models.containsKey(code))
            error(errors,"java-handlers","$."+code,"Java CRUD 处理器绑定的模型不存在："+code);
        if(!errors.isEmpty()) throw new RpcException(-32005,"元模型校验失败，原版本继续运行",Map.of("errors",errors));
        // DDL is deliberately additive. Metadata only becomes visible after all checks and synchronization succeed.
        try { ddl.synchronize(models); }
        catch(Exception ex) { throw new RpcException(-32005,"数据库结构同步失败，原版本继续运行",Map.of("errors",List.of(Map.of("file","models","path","$","message",Objects.toString(ex.getMessage(),"DDL error"))))); }
        menus.sort(Comparator.comparingInt(m->m.path("order").asInt(0)));
        current=new MetaSnapshot(current==null?1:current.version()+1,models,views,services,menus);
        return status();
    }
    private void load(String kind,Map<String,JsonNode> out,List<JsonNode> menus,Map<String,String> locations,List<Map<String,String>> errors) {
        Path dir=directory.resolve(kind);
        if(!Files.isDirectory(dir)) return;
        try(var paths=Files.walk(dir)) {
            for(Path path:paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".json")).sorted().toList()) {
                String file=directory.relativize(path).toString();
                try {
                    JsonNode root=mapper.readTree(Files.readString(path));
                    if(root==null||!root.isObject()) {error(errors,file,"$","根节点必须为对象");continue;}
                    if(kind.equals("models")) { put(out,root.path("model").path("code").asText(),root,kind,file,locations,errors); }
                    else {
                        JsonNode items=root.get(kind);
                        if(items==null||!items.isArray()) {error(errors,file,"$."+kind,"必须是数组");continue;}
                        int i=0;
                        for(JsonNode item:items) {
                            if(!item.isObject()) {error(errors,file,"$."+kind+"["+i+"]","必须为对象");i++;continue;}
                            if(kind.equals("menus")) menus.add(item); else put(out,item.path("code").asText(),item,kind,file,locations,errors);
                            i++;
                        }
                    }
                } catch(Exception ex) { error(errors,file,"$","JSON读取失败："+ex.getMessage()); }
            }
        }catch(IOException ex){error(errors,dir.toString(),"$",ex.getMessage());}
    }
    private void put(Map<String,JsonNode> map,String code,JsonNode node,String kind,String file,Map<String,String> locations,List<Map<String,String>> errors) {
        JsonNode rawCode=kind.equals("models")?node.path("model").path("code"):node.path("code");
        if(!rawCode.isTextual()||!identifier(code)) {error(errors,file,"$.code","编码必须是字符串，字母开头且仅含字母、数字、下划线");return;}
        if(map.putIfAbsent(code,node)!=null) error(errors,file,"$.code","重复编码："+code);
        locations.put(kind+":"+code,file);
    }
    private void validateModels(Map<String,JsonNode> models,Map<String,String> locations,List<Map<String,String>> errors) {
        Set<String> tables=new HashSet<>();
        for(var e:models.entrySet()) {
            String file=locations.get("models:"+e.getKey());JsonNode def=e.getValue(),model=def.path("model");
            if(Set.of("meta","service").contains(e.getKey())) error(errors,file,"$.model.code","meta/service为保留方法前缀");
            if(!model.path("name").isTextual()||model.path("name").asText().isBlank()) error(errors,file,"$.model.name","模型名称必须为非空字符串");
            for(String flag:List.of("appendOnly","logicDelete","serviceOnly","attachments")) if(model.has(flag)&&!model.get(flag).isBoolean()) error(errors,file,"$.model."+flag,"必须为布尔值");
            if(model.has("rowScope")) try {RowScope.validate(models,e.getKey());}
            catch(IllegalArgumentException ex){error(errors,file,"$.model.rowScope",ex.getMessage());}
            String table=table(def);
            if(!table.matches("[a-z][a-z0-9_]*")||table.startsWith("t_sys_")||table.startsWith("t_service_")||table.startsWith("t_attachment_")||Set.of("t_state_event","t_demo_seed").contains(table)) error(errors,file,"$.model.table","非法或保留表名");
            if(!tables.add(table.toLowerCase(Locale.ROOT))) error(errors,file,"$.model.table","物理表名重复");
            if(!Set.of("auto","uuid","snowflake").contains(model.path("idType").asText("auto"))) error(errors,file,"$.model.idType","仅支持auto/uuid/snowflake");
            JsonNode fs=def.path("fields");
            if(!fs.isArray()||fs.isEmpty()) {error(errors,file,"$.fields","必须为非空数组");continue;}
            Set<String> codes=new HashSet<>();
            for(JsonNode f:fs) {
                String code=f.path("code").asText(),p="$.fields."+code,type=f.path("type").asText();
                if(!f.isObject()||!f.path("code").isTextual()||!identifier(code)||RESERVED.contains(code.toLowerCase(Locale.ROOT))) error(errors,file,p,"非法或保留属性编码");
                if(!codes.add(code.toLowerCase(Locale.ROOT))) error(errors,file,p,"属性编码重复（数据库忽略大小写）");
                if(!f.path("name").isTextual()||f.path("name").asText().isBlank()) error(errors,file,p+".name","属性名称必须为非空字符串");
                if(!TYPES.contains(type)) error(errors,file,p+".type","不支持的属性类型");
                for(String flag:List.of("required","unique","searchable","listable","editable","hidden","index")) if(f.has(flag)&&!f.get(flag).isBoolean()) error(errors,file,p+"."+flag,"必须为布尔值");
                for(String number:List.of("min","max","minLength","maxLength","length","precision","scale")) if(f.has(number)&&!f.get(number).isNumber()) error(errors,file,p+"."+number,"必须为数值");
                if(f.has("min")&&f.has("max")&&f.get("min").isNumber()&&f.get("max").isNumber()&&f.get("min").decimalValue().compareTo(f.get("max").decimalValue())>0) error(errors,file,p,"min不能大于max");
                if(f.has("pattern")) try {Pattern.compile(f.get("pattern").asText());}catch(Exception ex){error(errors,file,p+".pattern","正则表达式无效");}
                if(type.equals("string")&&(f.path("length").asInt(255)<1||f.path("length").asInt(255)>4000)) error(errors,file,p+".length","字符串长度须为1~4000");
                if(type.equals("decimal")&&(f.path("precision").asInt(18)<1||f.path("precision").asInt(18)>38||f.path("scale").asInt(2)<0||f.path("scale").asInt(2)>f.path("precision").asInt(18))) error(errors,file,p,"非法decimal精度");
                if(type.equals("enum")) {
                    if(!f.path("options").isArray()||f.path("options").isEmpty()) error(errors,file,p+".options","枚举选项必填");
                    Set<String> values=new HashSet<>();
                    for(JsonNode o:f.path("options")) if(!o.has("value")||!o.has("label")||!values.add(o.path("value").asText())) error(errors,file,p+".options","枚举选项需要不重复的value和label");
                }
                if(type.equals("ref")&&!models.containsKey(f.path("ref").asText())) error(errors,file,p+".ref","引用模型不存在");
            }
            JsonNode state=def.path("stateMachine");
            if(def.has("relations")) {
                if(!def.get("relations").isArray())error(errors,file,"$.relations","relations必须为数组");
                Set<String> relationCodes=new HashSet<>();
                for(JsonNode relation:def.get("relations")){
                    if(!relation.path("code").isTextual()||!identifier(relation.path("code").asText())||!relationCodes.add(relation.path("code").asText())||!relation.path("name").isTextual())error(errors,file,"$.relations","关联编码与名称非法或重复");
                    try{RelationPaths.validate(models,e.getKey(),relation.path("target").asText(),relation.get("paths"));}catch(RpcException ex){error(errors,file,"$.relations",ex.getMessage());}
                }
            }
            if(!state.isMissingNode()) {
                String field=state.path("field").asText();JsonNode sf=fields(def).get(field);
                if(sf==null||!sf.path("type").asText().equals("enum")) error(errors,file,"$.stateMachine.field","状态机字段必须为已声明的枚举字段");
                Set<String> values=new HashSet<>();if(sf!=null)sf.path("options").forEach(o->values.add(o.path("value").asText()));
                if(!values.contains(state.path("initial").asText())) error(errors,file,"$.stateMachine.initial","初始状态不属于枚举");
                if(!state.path("transitions").isArray()) error(errors,file,"$.stateMachine.transitions","状态转移必须为数组");
                for(JsonNode t:state.path("transitions")) {
                    if(!values.contains(t.path("from").asText())||!values.contains(t.path("to").asText())) error(errors,file,"$.stateMachine.transitions","状态转移包含未知状态");
                    if(t.has("guard")&&!Conditions.supported(t.get("guard"))) error(errors,file,"$.stateMachine.transitions.guard","守卫仅支持布尔值或 field 比较 字面量");
                }
            }
        }
    }
    private void validateViews(Map<String,JsonNode> models,Map<String,JsonNode> views,Map<String,JsonNode> services,Map<String,String> locations,List<Map<String,String>> errors) {
        for(var e:views.entrySet()) {
            JsonNode v=e.getValue();String file=locations.get("views:"+e.getKey()),type=v.path("type").asText();
            if(!Set.of("list","form").contains(type)) error(errors,file,"$.type","视图类型仅支持list/form");
            if(!v.path("name").isTextual()||v.path("name").asText().isBlank()) error(errors,file,"$.name","视图名称必须为非空字符串");
            if(type.equals("list")&&(!v.path("table").isObject()||!v.path("table").path("columns").isArray())) error(errors,file,"$.table.columns","列表视图必须包含table对象和columns数组");
            if((type.equals("form")||v.has("fields"))&&!v.path("fields").isArray()) error(errors,file,"$.fields","表单fields必须为数组");
            if(v.has("search")&&(!v.get("search").isObject()||!v.get("search").path("fields").isArray())) error(errors,file,"$.search.fields","search必须为对象且fields必须为数组");
            if(v.has("buttons")&&!v.get("buttons").isArray()) error(errors,file,"$.buttons","buttons必须为数组");
            if(v.path("table").has("buttons")&&!v.path("table").get("buttons").isArray()) error(errors,file,"$.table.buttons","buttons必须为数组");
            JsonNode model=models.get(v.path("model").asText());
            if(model==null){error(errors,file,"$.model","视图引用模型不存在");continue;}
            Map<String,JsonNode> fields=fields(model);
            validateCharts(v,fields,file,errors);
            List<JsonNode> refs=new ArrayList<>();v.path("fields").forEach(refs::add);v.path("table").path("columns").forEach(refs::add);v.path("search").path("fields").forEach(refs::add);
            for(JsonNode f:refs) {
                if(!f.isObject()||(!fields.containsKey(f.path("field").asText())&&!Set.of("id","version","created_at","updated_at").contains(f.path("field").asText()))) error(errors,file,"$.fields","视图引用未知属性："+f.path("field").asText());
                if(f.has("widget")&&!Set.of("input","textarea","number","select","radio","date","datetime","switch","ref").contains(f.get("widget").asText())) error(errors,file,"$.fields.widget","不支持的控件类型："+f.get("widget").asText());
                if(f.has("span")&&(!f.get("span").isIntegralNumber()||f.get("span").asInt()<1||f.get("span").asInt()>24)) error(errors,file,"$.fields.span","栅格span须为1~24的整数");
            }
            for(JsonNode search:v.path("search").path("fields")) if(!fields.containsKey(search.path("field").asText())||!fields.get(search.path("field").asText()).path("searchable").asBoolean()) error(errors,file,"$.search.fields","搜索字段必须声明searchable");
            List<JsonNode> buttons=new ArrayList<>();v.path("buttons").forEach(buttons::add);v.path("table").path("buttons").forEach(buttons::add);
            Set<String> buttonCodes=new HashSet<>();
            for(JsonNode b:buttons) {
                String action=b.path("action").asText(),buttonCode=b.path("code").asText();
                if(!identifier(buttonCode)||!buttonCodes.add(buttonCode)) error(errors,file,"$.buttons.code","按钮编码非法或重复");
                if(action.startsWith("openForm:")) {
                    JsonNode target=views.get(action.substring(9));
                    if(target==null||!target.path("type").asText().equals("form")) error(errors,file,"$.buttons.action","按钮引用表单视图不存在");
                }else if(action.startsWith("service:")) {
                    String call=action.substring(8);String[] parts=call.split("\\.",-1);
                    boolean valid=parts.length==1?services.containsKey(call):parts.length==2&&(parts[0].equals("service")?services.containsKey(parts[1]):models.containsKey(parts[0])&&ACTIONS.contains(parts[1]));
                    if(!valid)error(errors,file,"$.buttons.action","按钮引用服务或模型动作不存在："+call);
                }else if(!Set.of("close","refresh").contains(action)) error(errors,file,"$.buttons.action","不支持的按钮动作："+action);
            }
        }
    }
    private void validateMenus(List<JsonNode> menus,Map<String,JsonNode> views,Set<String> codes,List<Map<String,String>> errors) {
        for(JsonNode m:menus) {
            String code=m.path("code").asText();
            if(!m.path("code").isTextual()||!identifier(code)||!codes.add(code)) error(errors,"menus","$."+code,"菜单编码非法或全局重复");
            if(!m.path("name").isTextual()||m.path("name").asText().isBlank()) error(errors,"menus","$."+code+".name","菜单名称必须为非空字符串");
            if(m.has("children")&&!m.get("children").isArray()) error(errors,"menus","$."+code+".children","children必须为数组");
            List<JsonNode> children=new ArrayList<>();m.path("children").forEach(children::add);
            if(children.isEmpty()&&!views.containsKey(m.path("view").asText())) error(errors,"menus","$."+code+".view","叶子菜单引用视图不存在");
            validateMenus(children,views,codes,errors);
            if(m.path("children").isArray()) ((com.fasterxml.jackson.databind.node.ArrayNode)m.get("children")).removeAll().addAll(children.stream().sorted(Comparator.comparingInt(c->c.path("order").asInt(0))).toList());
        }
    }
    private void validateCharts(JsonNode view,Map<String,JsonNode> fields,String file,List<Map<String,String>> errors) {
        if(!view.has("chart"))return;
        JsonNode charts=view.get("chart");
        if(!charts.isArray()||charts.size()>6){error(errors,file,"$.chart","chart必须为最多6项的数组");return;}
        Set<String> codes=new HashSet<>();
        for(JsonNode chart:charts) {
            String path="$.chart."+chart.path("code").asText();
            if(!chart.isObject()||!identifier(chart.path("code").asText())||!codes.add(chart.path("code").asText())
                    ||!Set.of("bar","line").contains(chart.path("type").asText())||chart.path("title").asText().isBlank())
                error(errors,file,path,"图表编码、标题或类型无效");
            Set<String> groups=new HashSet<>();
            if(!chart.path("groupBy").isArray()||chart.path("groupBy").isEmpty()||chart.path("groupBy").size()>4)error(errors,file,path+".groupBy","图表需要1~4个分组字段");
            for(JsonNode field:chart.path("groupBy"))if(!field.isTextual()||!fields.containsKey(field.asText())||!groups.add(field.asText()))error(errors,file,path+".groupBy","分组字段未知或重复");
            if(chart.has("firstBy")&&(!chart.get("firstBy").isTextual()||!fields.containsKey(chart.get("firstBy").asText())
                    ||!Set.of("ref","string","integer","enum").contains(fields.getOrDefault(chart.path("firstBy").asText(),mapper.createObjectNode()).path("type").asText())))error(errors,file,path+".firstBy","firstBy必须为已声明的标识字段");
            Set<String> aliases=new HashSet<>();
            if(!chart.path("metrics").isArray()||chart.path("metrics").isEmpty()||chart.path("metrics").size()>10)error(errors,file,path+".metrics","图表需要1~10个指标");
            for(JsonNode metric:chart.path("metrics")) {
                String op=metric.path("op").asText(),field=metric.path("field").asText(),alias=metric.path("as").asText();
                if(!alias.matches("[a-zA-Z][a-zA-Z0-9_]*")||groups.stream().anyMatch(g->g.equalsIgnoreCase(alias))||!aliases.add(alias.toLowerCase(Locale.ROOT)))error(errors,file,path+".metrics","指标别名非法或重复");
                if(!Set.of("count","sum","avg").contains(op)||(!field.isBlank()&&!fields.containsKey(field))
                        ||(!op.equals("count")&&!Set.of("integer","decimal").contains(fields.getOrDefault(field,mapper.createObjectNode()).path("type").asText())))error(errors,file,path+".metrics","指标函数或字段无效");
            }
            Set<String> metricNames=new HashSet<>();chart.path("metrics").forEach(metric->metricNames.add(metric.path("as").asText()));
            Set<String> names=new HashSet<>(metricNames);
            if(chart.has("ratios")&&!chart.get("ratios").isArray())error(errors,file,path+".ratios","ratios必须为数组");
            for(JsonNode ratio:chart.path("ratios")) {
                String alias=ratio.path("as").asText();
                if(!alias.matches("[a-zA-Z][a-zA-Z0-9_]*")||!aliases.add(alias.toLowerCase(Locale.ROOT))||!names.add(alias)
                        ||!metricNames.contains(ratio.path("numerator").asText())||!metricNames.contains(ratio.path("denominator").asText())
                        ||ratio.has("subtract")&&!metricNames.contains(ratio.path("subtract").asText()))error(errors,file,path+".ratios","比例指标必须引用已声明的聚合指标");
            }
            if(!chart.path("series").isArray()||chart.path("series").isEmpty()||chart.path("series").size()>4)error(errors,file,path+".series","图表需要1~4个数据系列");
            for(JsonNode series:chart.path("series"))if(!names.contains(series.path("field").asText())||series.path("name").asText().isBlank())error(errors,file,path+".series","数据系列引用未知指标或缺少名称");
        }
    }
    private void validateServices(Map<String,JsonNode> models,Map<String,JsonNode> services,Map<String,String> locations,List<Map<String,String>> errors) {
        Map<String,Set<String>> graph=new HashMap<>();
        for(var e:services.entrySet()) {
            JsonNode service=e.getValue();String file=locations.get("services:"+e.getKey());Set<String> params=new HashSet<>();
            if(!service.path("name").isTextual()||service.path("name").asText().isBlank()) error(errors,file,"$.name","服务名称必须为非空字符串");
            for(String flag:List.of("internalOnly","systemScope")) if(service.has(flag)&&!service.get(flag).isBoolean())error(errors,file,"$."+flag,"必须为布尔值");
            if(service.path("systemScope").asBoolean()&&(!service.path("internalOnly").asBoolean()||!service.path("return").isObject()))
                error(errors,file,"$.systemScope","系统数据范围只允许内部服务，且必须显式投影return对象");
            if(service.has("submitLabel")&&(!service.get("submitLabel").isTextual()||service.get("submitLabel").asText().isBlank()||service.get("submitLabel").asText().length()>40))
                error(errors,file,"$.submitLabel","提交按钮文字必须为1至40字字符串");
            if(service.has("resultFields")) {
                JsonNode resultFields=service.get("resultFields");Set<String> resultNames=new HashSet<>();
                if(!resultFields.isArray()||resultFields.isEmpty()||resultFields.size()>20)
                    error(errors,file,"$.resultFields","结果展示字段必须为1至20项数组");
                for(JsonNode field:resultFields) {
                    String code=field.path("field").asText();
                    if(!field.path("field").isTextual()||!identifier(code)||!resultNames.add(code)
                            ||!field.path("label").isTextual()||field.path("label").asText().isBlank()||field.path("label").asText().length()>80
                            ||!service.path("return").isObject()||!service.path("return").has(code))
                        error(errors,file,"$.resultFields","结果字段须唯一、带1至80字标签并引用return中的顶层字段");
                }
            }
            for(JsonNode p:service.path("params")) {
                String name=p.path("name").asText();if(!p.path("name").isTextual()||!identifier(name)||!params.add(name)||!TYPES.contains(p.path("type").asText())) error(errors,file,"$.params","服务参数编码重复/非法或类型不支持");
                if(p.path("type").asText().equals("ref")&&!models.containsKey(p.path("ref").asText())) error(errors,file,"$.params.ref","引用模型不存在");
                if(p.has("required")&&!p.get("required").isBoolean()) error(errors,file,"$.params.required","必须为布尔值");
            }
            if(!service.path("params").isArray()) error(errors,file,"$.params","服务params必须为数组");
            if(!service.path("steps").isArray()||service.path("steps").isEmpty()) error(errors,file,"$.steps","服务steps必须为非空数组");
            if(service.has("guards")&&!service.get("guards").isArray()) error(errors,file,"$.guards","guards必须为数组");
            if(service.has("transactional")&&!service.get("transactional").isBoolean()) error(errors,file,"$.transactional","transactional必须为布尔值");
            for(JsonNode guard:service.path("guards")) {
                if(guard.has("expression")) error(errors,file,"$.guards.expression","不支持自由表达式，请使用condition结构");
                if(guard.has("condition")&&!validCondition(guard.get("condition"))) error(errors,file,"$.guards.condition","条件须为all/any数组或含left/op/right的比较，操作符仅eq/ne/gt/ge/lt/le/in");
            }
            if(!service.path("onError").asText("rollback").equals("rollback")) error(errors,file,"$.onError","当前仅支持rollback；补偿模式尚未实现");
            if(service.has("idempotencyKey")&&(!params.contains(service.get("idempotencyKey").asText())||!service.path("transactional").asBoolean(true))) error(errors,file,"$.idempotencyKey","幂等键必须引用已声明参数，且服务必须使用事务");
            List<JsonNode> steps=new ArrayList<>();service.path("guards").forEach(steps::add);collectSteps(service.path("steps"),steps,file,errors,0);
            Set<String> edges=new HashSet<>();graph.put(e.getKey(),edges);
            for(JsonNode step:steps) {
                String call=step.path("call").asText();String[] parts=call.split("\\.",-1);
                if(parts.length!=2||parts[0].equals("meta")) {error(errors,file,"$.steps.call","服务调用必须为model.action或service.code，禁止调用管理接口");continue;}
                Set<String> allowed=new HashSet<>();
                if(parts[0].equals("service")) {
                    JsonNode target=services.get(parts[1]);
                    if(target==null) error(errors,file,"$.steps.call","目标服务不存在："+call);
                    else target.path("params").forEach(p->allowed.add(p.path("name").asText()));
                    edges.add(parts[1]);
                }else if(!models.containsKey(parts[0])||!ACTIONS.contains(parts[1])) error(errors,file,"$.steps.call","目标模型或动作不存在："+call);
                else {
                    switch(parts[1]) {
                        case "create" -> fields(models.get(parts[0])).forEach((key,f)->{if(f.path("editable").asBoolean(true))allowed.add(key);});
                        case "update" -> {fields(models.get(parts[0])).forEach((key,f)->{if(f.path("editable").asBoolean(true))allowed.add(key);});allowed.addAll(Set.of("id","version"));}
                        case "read" -> allowed.add("id");
                        case "delete" -> allowed.addAll(Set.of("id","version"));
                        case "adjust" -> allowed.addAll(Set.of("id","field","delta","version"));
                        case "transition" -> allowed.addAll(Set.of("id","to","version","reason"));
                        case "list" -> allowed.addAll(Set.of("page","pageSize","filters","sortField","order"));
                        case "aggregate" -> allowed.addAll(Set.of("groupBy","metrics","filters"));
                        case "related" -> allowed.addAll(Set.of("id","model","field","page","pageSize","paths","filters"));
                        case "batchCreate","batchDelete" -> allowed.add("records");
                        default -> { }
                    }
                }
                if(!step.path("map").isObject()) error(errors,file,"$.steps.map","map必须为参数映射对象");
                step.path("map").fieldNames().forEachRemaining(key->{if(!allowed.contains(key))error(errors,file,"$.steps.map."+key,"目标入参不在白名单");});
            }
        }
        for(String code:graph.keySet()) if(cyclic(code,graph,new HashSet<>(),new HashSet<>())) error(errors,locations.get("services:"+code),"$.steps","服务调用存在循环依赖");
    }
    private boolean cyclic(String code,Map<String,Set<String>> graph,Set<String> visiting,Set<String> done) {
        if(done.contains(code))return false;if(!visiting.add(code))return true;
        for(String next:graph.getOrDefault(code,Set.of())) if(cyclic(next,graph,visiting,done))return true;
        visiting.remove(code);done.add(code);return false;
    }
    private boolean validCondition(JsonNode condition) {
        if(!condition.isObject())return false;
        if(condition.has("all")||condition.has("any")) {
            JsonNode children=condition.has("all")?condition.get("all"):condition.get("any");
            if(!children.isArray()||children.isEmpty())return false;
            for(JsonNode child:children)if(!validCondition(child))return false;
            return true;
        }
        return condition.has("left")&&condition.has("right")&&Set.of("eq","ne","gt","ge","lt","le","in").contains(condition.path("op").asText());
    }
    private void collectSteps(JsonNode source,List<JsonNode> target,String file,List<Map<String,String>> errors,int depth) {
        if(depth>8){error(errors,file,"$.steps","步骤嵌套不能超过8层");return;}
        for(JsonNode step:source){
            if(step.has("when")&&!validCondition(step.get("when")))error(errors,file,"$.steps.when","条件结构无效");
            if(step.has("forEach")){
                if(!step.get("forEach").isTextual()||!step.path("steps").isArray()||step.path("steps").isEmpty())error(errors,file,"$.steps.forEach","循环需要来源路径及非空steps数组");
                else collectSteps(step.get("steps"),target,file,errors,depth+1);
            }else target.add(step);
        }
    }
    private boolean identifier(String code) { return code.matches("[a-z][a-zA-Z0-9_]*"); }
    private void error(List<Map<String,String>> errors,String file,String path,String message) { errors.add(Map.of("file",Objects.toString(file,"unknown"),"path",path,"message",Objects.toString(message,"校验失败"))); }
}
