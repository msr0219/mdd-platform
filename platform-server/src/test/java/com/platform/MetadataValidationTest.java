package com.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.meta.MetaEngine;
import com.platform.meta.MetaSnapshot;
import com.platform.rpc.RpcException;
import com.platform.service.CrudExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Each suite has its own copied metadata and database; source JSON is never edited. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:metadata-boundary-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "platform.demo.enabled=false", "platform.auth.bootstrap-password=InitialTest!12345"
})
class MetadataValidationTest {
    static final Path META=copyMetadata();
    final Map<Path,byte[]> originals=new LinkedHashMap<>();
    @Autowired MetaEngine engine;
    @Autowired CrudExecutor crud;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    static Path copyMetadata() {
        try {
            Path source=Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta");
            Path target=Files.createDirectories(Path.of("target/metadata-validation-"+UUID.randomUUID())).toAbsolutePath();
            try(var paths=Files.walk(source)) {
                for(Path file:paths.toList()) {
                    Path dest=target.resolve(source.relativize(file));
                    if(Files.isDirectory(file))Files.createDirectories(dest);else Files.copy(file,dest);
                }
            }
            return target;
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) {registry.add("platform.meta-dir",()->META.toString());}
    void write(String relative,String content) throws Exception {
        Path path=META.resolve(relative);
        if(!originals.containsKey(path))originals.put(path,Files.exists(path)?Files.readAllBytes(path):null);
        Files.createDirectories(path.getParent());Files.writeString(path,content);
    }
    void write(String relative,JsonNode content) throws Exception {write(relative,mapper.writeValueAsString(content));}
    @AfterEach void restoreFixtures() throws Exception {
        for(var entry:originals.entrySet())if(entry.getValue()==null)Files.deleteIfExists(entry.getKey());else Files.write(entry.getKey(),entry.getValue());
        engine.reload();
    }
    RpcException rejected() {
        MetaSnapshot before=engine.snapshot();RpcException error=assertThrows(RpcException.class,engine::reload);
        assertEquals(-32005,error.getCode());assertSame(before,engine.snapshot(),"失败重载不得切换快照");assertNotNull(error.getData());return error;
    }
    ObjectNode field(String code,String type) {
        return mapper.createObjectNode().put("code",code).put("name",code).put("type",type).put("editable",true).put("listable",true).put("searchable",true);
    }
    ObjectNode model(String code) {
        ObjectNode model=mapper.createObjectNode();model.putObject("model").put("code",code).put("name",code);
        model.putArray("fields").add(field("title","string").put("length",80));return model;
    }
    ObjectNode service(String code,String call,ObjectNode map) {
        ObjectNode service=mapper.createObjectNode().put("code",code).put("name",code).put("transactional",true);
        service.putArray("params");service.putArray("steps").addObject().put("call",call).set("map",map);return service;
    }
    ObjectNode serviceDocument(ObjectNode... services) {
        ObjectNode doc=mapper.createObjectNode();ArrayNode array=doc.putArray("services");for(ObjectNode service:services)array.add(service);return doc;
    }

    @Test void malformedJsonDoesNotReplaceRunningDefinitions() throws Exception {
        write("models/invalid.json","{bad-json");
        RpcException error=rejected();assertTrue(mapper.valueToTree(error.getData()).path("errors").get(0).has("file"));
        assertTrue(engine.snapshot().models().containsKey("student"));
    }
    @Test void rowScopesMustUseCreatorsOrAcyclicReferencesToScopedParents() throws Exception {
        ObjectNode bad=model("boundary_scope");ObjectNode options=(ObjectNode)bad.get("model");options.put("rowScope","own");
        write("models/invalid_scope.json",bad);rejected();
        options.putObject("rowScope").put("creator",false);write("models/invalid_scope.json",bad);rejected();
        options.putObject("rowScope").put("via","title");write("models/invalid_scope.json",bad);rejected();
        ((ArrayNode)bad.get("fields")).add(field("courseId","ref").put("ref","course"));options.putObject("rowScope").put("via","courseId");
        write("models/invalid_scope.json",bad);rejected();
        ((ArrayNode)bad.get("fields")).add(field("studentId","ref").put("ref","student"));options.putObject("rowScope").put("via","studentId").put("allowUnassigned","true");
        write("models/invalid_scope.json",bad);rejected();
        options.putObject("rowScope").put("via","studentId").put("allowUnassigned",false);
        write("models/invalid_scope.json",bad);engine.reload();
        options.putObject("rowScope").put("creator",true).put("sql","1=1");write("models/invalid_scope.json",bad);rejected();
        ObjectNode other=model("boundary_scope_parent");((ArrayNode)other.get("fields")).add(field("childId","ref").put("ref","boundary_scope"));
        ((ObjectNode)other.get("model")).putObject("rowScope").put("via","childId");
        ((ArrayNode)bad.get("fields")).add(field("parentId","ref").put("ref","boundary_scope_parent"));options.putObject("rowScope").put("via","parentId");
        write("models/invalid_scope.json",bad);write("models/scope_parent.json",other);rejected();
    }
    @Test void systemScopeIsOnlyAllowedForExplicitlyProjectedInternalServices() throws Exception {
        ObjectNode helper=service("boundary_system_scope","student.list",mapper.createObjectNode());helper.put("systemScope",true);
        write("services/system_scope.json",serviceDocument(helper));rejected();
        helper.put("internalOnly",true);write("services/system_scope.json",serviceDocument(helper));rejected();
        helper.put("return","steps[0].result");write("services/system_scope.json",serviceDocument(helper));rejected();
        helper.putObject("return").put("processed",true);write("services/system_scope.json",serviceDocument(helper));engine.reload();
        helper.put("systemScope","true");write("services/system_scope.json",serviceDocument(helper));rejected();
    }
    @Test void serviceResultPresentationRequiresValidDeclaredFieldsAndLabels() throws Exception {
        ObjectNode query=service("boundary_result","student.list",mapper.createObjectNode());
        query.putObject("return").put("total","steps[0].result.total");
        query.putObject("resultFields").put("total","总数");
        write("services/invalid_result.json",serviceDocument(query));rejected();
        query.putArray("resultFields").addObject().put("field","missing").put("label","不存在的字段");
        write("services/invalid_result.json",serviceDocument(query));rejected();
        query.putArray("resultFields").addObject().put("field","total").put("label","");
        write("services/invalid_result.json",serviceDocument(query));rejected();
        ArrayNode fields=query.putArray("resultFields");fields.addObject().put("field","total").put("label","总数");fields.add(fields.get(0).deepCopy());
        write("services/invalid_result.json",serviceDocument(query));rejected();
        query.putArray("resultFields").addObject().put("field","total").put("label","总数");query.put("submitLabel",true);
        write("services/invalid_result.json",serviceDocument(query));rejected();
        query.put("submitLabel","查询");write("services/invalid_result.json",serviceDocument(query));engine.reload();
        assertTrue(engine.snapshot().services().containsKey("boundary_result"));
    }
    @Test void relationPathsMustFollowRealReferenceFields() throws Exception {
        ObjectNode bad=model("boundary_relation");ObjectNode relation=bad.putArray("relations").addObject().put("code","invalid_hop").put("name","非法跳转").put("target","student");
        relation.putArray("paths").addArray().addObject().put("model","student").put("field","title").put("direction","out");
        write("models/invalid_relation.json",bad);rejected();
    }
    @Test void attachmentPolicyMustBeBooleanAndInternalFileTablesAreReserved() throws Exception {
        ObjectNode bad=model("boundary_attachments");
        ((ObjectNode)bad.get("model")).put("attachments","true");
        write("models/invalid_attachments.json",bad);rejected();
        ((ObjectNode)bad.get("model")).put("attachments",true).put("table","t_attachment_file");
        write("models/invalid_attachments.json",bad);rejected();
    }
    @Test void modelFieldsAndCodesMustHaveTheDeclaredStructure() throws Exception {
        ObjectNode bad=model("boundary_shape");bad.set("fields",mapper.createObjectNode().set("title",field("title","string")));
        write("models/invalid.json",bad);rejected();
        bad=model("boundary_shape");((ObjectNode)bad.get("model")).put("code",true);
        write("models/invalid.json",bad);rejected();
        bad=model("boundary_shape");((ObjectNode)bad.path("fields").get(0)).put("code",true);
        write("models/invalid.json",bad);rejected();
    }
    @Test void dangerousIdentifiersReservedColumnsAndPhysicalTablesAreRejected() throws Exception {
        for(String fieldName:new String[]{"id","VERSION","created_at","title;DROP_TABLE"}) {
            ObjectNode bad=model("boundary_reserved");bad.putArray("fields").add(field(fieldName,"string"));
            write("models/invalid.json",bad);rejected();
        }
        for(String table:new String[]{"t_student","t_state_event","t_demo_seed","t_service_idempotency","t_bad;DROP_TABLE"}) {
            ObjectNode bad=model("boundary_reserved");((ObjectNode)bad.get("model")).put("table",table);
            write("models/invalid.json",bad);rejected();
        }
        for(String code:new String[]{"meta","service"}) {write("models/invalid.json",model(code));rejected();}
    }
    @Test void attributesCannotDifferOnlyByDatabaseCase() throws Exception {
        ObjectNode bad=model("boundary_case");((ArrayNode)bad.get("fields")).add(field("Title","string"));
        write("models/invalid.json",bad);rejected();
    }
    @Test void viewArraysAndSupportedWidgetsAreValidatedBeforePublishing() throws Exception {
        ObjectNode view=mapper.createObjectNode().put("code","boundary_view").put("name","边界视图").put("type","list").put("model","student");
        view.putObject("table").set("columns",mapper.createObjectNode());
        ObjectNode document=mapper.createObjectNode();document.putArray("views").add(view);
        write("views/invalid.json",document);rejected();
        ((ObjectNode)view.get("table")).putArray("columns").addObject().put("field","name");
        view.putObject("search").set("fields",mapper.createObjectNode());
        write("views/invalid.json",document);rejected();
        view.remove("search");view.put("type","form");view.putArray("fields").addObject().put("field","name").put("widget","arbitrary-script");
        write("views/invalid.json",document);rejected();
    }
    @Test void buttonsMustTargetExistingModelsServicesAndFormViews() throws Exception {
        ObjectNode view=mapper.createObjectNode().put("code","boundary_button").put("name","按钮视图").put("type","form").put("model","student");
        view.putArray("fields").addObject().put("field","name");
        ObjectNode button=view.putArray("buttons").addObject().put("code","bad").put("name","坏按钮");
        ObjectNode document=mapper.createObjectNode();document.putArray("views").add(view);
        for(String action:new String[]{"service:noSuchService","service:student.dropTable","openForm:student_list","run:arbitrary"}) {
            button.put("action",action);write("views/invalid.json",document);rejected();
        }
    }
    @Test void menuChildrenMustBeAnArrayAndCodesMustBeGloballyUnique() throws Exception {
        ObjectNode menu=mapper.createObjectNode().put("code","boundary_menu").put("name","菜单").put("view","student_list");
        menu.set("children",mapper.createObjectNode());ObjectNode document=mapper.createObjectNode();document.putArray("menus").add(menu);
        write("menus/invalid.json",document);rejected();
        menu.remove("children");((ArrayNode)document.get("menus")).add(menu.deepCopy());
        write("menus/invalid.json",document);rejected();
    }
    @Test void serviceCyclesAndAdministrativeCallsCannotBePublished() throws Exception {
        ObjectNode one=service("boundary_one","service.boundary_two",mapper.createObjectNode());
        ObjectNode two=service("boundary_two","service.boundary_one",mapper.createObjectNode());
        write("services/invalid.json",serviceDocument(one,two));rejected();
        write("services/invalid.json",serviceDocument(service("boundary_admin","meta.reload",mapper.createObjectNode())));rejected();
    }
    @Test void eachActionHasItsOwnParameterWhitelistAndGuardsHaveAClosedGrammar() throws Exception {
        write("services/invalid.json",serviceDocument(service("boundary_map","student.read",mapper.createObjectNode().put("name","unrelated"))));rejected();
        ObjectNode def=service("boundary_map","student.read",mapper.createObjectNode().put("id",1));
        def.putArray("guards").addObject().put("call","student.read").put("expression","doAnything()").set("map",mapper.createObjectNode().put("id",1));
        write("services/invalid.json",serviceDocument(def));rejected();
        ((ObjectNode)def.path("guards").get(0)).remove("expression");
        ((ObjectNode)def.path("guards").get(0)).putObject("condition").put("left","result.age").put("op","execute").put("right",1);
        write("services/invalid.json",serviceDocument(def));rejected();
    }
    @Test void addingARequiredColumnToANonemptyTableKeepsOldMetadataUsable() throws Exception {
        ObjectNode def=model("boundary_required");write("models/boundary_required.json",def);engine.reload();
        crud.execute(engine.snapshot(),"boundary_required","create",mapper.createObjectNode().put("title","原数据"));
        ((ArrayNode)def.get("fields")).add(field("mustHave","integer").put("required",true));
        write("models/boundary_required.json",def);rejected();
        JsonNode created=mapper.valueToTree(crud.execute(engine.snapshot(),"boundary_required","create",mapper.createObjectNode().put("title","仍可创建")));
        assertEquals("仍可创建",created.path("title").asText());assertFalse(created.has("mustHave"));
    }
    @Test void invalidChartReferencesKeepThePreviousMetadataSnapshot() throws Exception {
        ObjectNode view=engine.snapshot().views().get("machine_inspection_list").deepCopy();
        ((ObjectNode)view.path("chart").get(0)).put("firstBy","missingAssembly");
        view.put("code","invalid_chart_list");ObjectNode document=mapper.createObjectNode();document.putArray("views").add(view);
        write("views/invalid-chart.json",document);
        assertTrue(mapper.valueToTree(rejected().getData()).toString().contains("firstBy"));
    }
    @Test void incompatibleDatabaseTypeLengthPrecisionAndIdChangesAreDetected() throws Exception {
        ObjectNode def=model("boundary_types");((ArrayNode)def.get("fields")).add(field("amount","decimal").put("precision",18).put("scale",2));
        write("models/boundary_types.json",def);engine.reload();
        ObjectNode title=(ObjectNode)def.path("fields").get(0),amount=(ObjectNode)def.path("fields").get(1);
        title.put("type","integer");write("models/boundary_types.json",def);rejected();title.put("type","string");
        title.put("length",160);write("models/boundary_types.json",def);rejected();title.put("length",80);
        amount.put("scale",4);write("models/boundary_types.json",def);rejected();amount.put("scale",2);
        ((ObjectNode)def.get("model")).put("idType","uuid");write("models/boundary_types.json",def);rejected();
        assertEquals("string",engine.snapshot().models().get("boundary_types").path("fields").get(0).path("type").asText());
    }
    @Test void additiveNullableFieldsWorkAndRemovedOptionalColumnsAreNotExposed() throws Exception {
        ObjectNode def=model("boundary_optional");write("models/boundary_optional.json",def);engine.reload();
        ((ArrayNode)def.get("fields")).add(field("extra","string"));write("models/boundary_optional.json",def);engine.reload();
        JsonNode created=mapper.valueToTree(crud.execute(engine.snapshot(),"boundary_optional","create",mapper.createObjectNode().put("title","记录").put("extra","旧列内容")));
        ((ArrayNode)def.get("fields")).remove(1);write("models/boundary_optional.json",def);engine.reload();
        JsonNode read=mapper.valueToTree(crud.execute(engine.snapshot(),"boundary_optional","read",mapper.createObjectNode().set("id",created.get("id"))));
        assertFalse(read.has("extra"));
        assertEquals("旧列内容",jdbc.queryForObject("SELECT `extra` FROM t_boundary_optional WHERE id=?",String.class,created.path("id").longValue()),"仅移除元模型属性不能删除物理数据");
    }
    @Test void aggregatePreservesDeclaredCamelCaseGroupFieldsAndMetricAliases() {
        crud.execute(engine.snapshot(),"part_batch","create",mapper.createObjectNode()
                .put("batchNo","AGG-"+UUID.randomUUID().toString().substring(0,8)).put("partName","聚合测试件")
                .put("qualityStatus","GOOD").put("quantity",7));
        ObjectNode request=mapper.createObjectNode();request.putArray("groupBy").add("qualityStatus");
        request.putArray("metrics").addObject().put("op","sum").put("field","quantity").put("as","totalQuantity");
        JsonNode result=mapper.valueToTree(crud.execute(engine.snapshot(),"part_batch","aggregate",request));
        JsonNode row=result.path("records").get(0);
        assertEquals("GOOD",row.path("qualityStatus").asText());assertEquals(7,row.path("totalQuantity").asInt());
        assertFalse(row.has("qualitystatus"));assertFalse(row.has("totalquantity"));
    }
}
