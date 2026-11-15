package com.platform.meta;

import com.fasterxml.jackson.databind.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import static com.platform.meta.Definitions.*;

/** Additive, idempotent ref-table backfills. Configurations never contain SQL. */
@Component @Order(110)
public class DataBackfillRunner implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(DataBackfillRunner.class);
    private final MetaEngine engine;private final ObjectMapper mapper;private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Path directory;
    public DataBackfillRunner(MetaEngine engine,ObjectMapper mapper,JdbcTemplate jdbc,PlatformTransactionManager manager,@Value("${platform.meta-dir:../meta}") String directory){this.engine=engine;this.mapper=mapper;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.directory=Path.of(directory).resolve("backfills");}
    @Override public void run(ApplicationArguments args) throws Exception {apply();}
    public void apply() throws Exception {
        if(!Files.isDirectory(directory))return;
        List<Plan> plans=new ArrayList<>();
        try(var files=Files.list(directory)){for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList())plans.add(plan(mapper.readTree(file.toFile())));}
        tx.executeWithoutResult(status->{for(Plan plan:plans){int added=jdbc.update(plan.sql,plan.args.toArray());if(added>0)log.info("Data backfill {} added {} records",plan.code,added);}});
    }
    private record Plan(String code,String sql,List<Object> args) { }
    private Plan plan(JsonNode definition){
        var snapshot=engine.snapshot();String source=definition.path("source").asText(),target=definition.path("target").asText(),code=definition.path("code").asText();
        if(!code.matches("[a-zA-Z][a-zA-Z0-9_]*")||source.equals(target))throw invalid("回填编码或源/目标模型非法");
        JsonNode from=model(snapshot,source),to=model(snapshot,target);
        if(!to.path("model").path("idType").asText("auto").equals("auto"))throw invalid("回填目标必须采用auto主键");
        JsonNode mapping=definition.path("fields"),keys=definition.path("keyFields");
        if(!mapping.isObject()||mapping.isEmpty()||!keys.isArray()||keys.isEmpty()||keys.size()>5)throw invalid("回填需要字段映射与1~5个键字段");
        Map<String,JsonNode> targetFields=fields(to);List<String> columns=new ArrayList<>(),values=new ArrayList<>(),matches=new ArrayList<>();List<Object> args=new ArrayList<>();
        mapping.fields().forEachRemaining(e->{if(!targetFields.containsKey(e.getKey()))throw invalid("回填目标字段不存在："+e.getKey());columns.add(q(e.getKey()));values.add(expression(e.getValue(),from,args));});
        columns.addAll(List.of("`created_by`","`updated_by`"));values.addAll(List.of("?","?"));args.add("system");args.add("system");
        Set<String> seen=new HashSet<>();
        for(JsonNode key:keys){String name=key.asText();if(!key.isTextual()||!mapping.has(name)||!seen.add(name))throw invalid("回填键必须引用唯一的映射字段");JsonNode value=mapping.get(name);
            if(value.has("const")&&value.get("const").isNull())throw invalid("回填键不能为null");
            if(value.has("source")&&!value.get("source").asText().equals("id")&&!fields(from).get(value.get("source").asText()).path("required").asBoolean())throw invalid("回填键的源字段必须非空");
            matches.add("t."+q(name)+"="+expression(value,from,args));
        }
        String sql="INSERT INTO "+q(table(to))+" ("+String.join(",",columns)+") SELECT "+String.join(",",values)+" FROM "+q(table(from))+" s WHERE 1=1"+(logicalDelete(from)?" AND s.`deleted`=FALSE":"")+" AND NOT EXISTS (SELECT 1 FROM "+q(table(to))+" t WHERE "+String.join(" AND ",matches)+")";
        return new Plan(code,sql,args);
    }
    private String expression(JsonNode value,JsonNode source,List<Object> args){
        if(!value.isObject()||value.size()!=1)throw invalid("回填仅接受source字段或const标量");
        if(value.has("source")){String field=value.get("source").asText();if(!field.equals("id")&&!fields(source).containsKey(field))throw invalid("回填源字段不存在");return "s."+q(field);}
        if(value.has("const")&&!value.get("const").isContainerNode()){JsonNode literal=value.get("const");args.add(literal.isNull()?null:literal.isBoolean()?literal.booleanValue():literal.isNumber()?literal.decimalValue():literal.asText());return "?";}
        throw invalid("非法回填表达式");
    }
    private IllegalStateException invalid(String message){return new IllegalStateException(message);}
}
