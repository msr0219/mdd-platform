package com.platform.dao;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.*;
import static com.platform.meta.Definitions.*;

@Component
public class DdlSynchronizer {
    private static final Logger log=LoggerFactory.getLogger(DdlSynchronizer.class);
    private final JdbcTemplate jdbc;
    private final boolean mysql;
    public DdlSynchronizer(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        mysql=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")));
    }
    public void synchronize(Map<String,JsonNode> models) {
        // H2/MySQL DDL may commit implicitly. Check every existing table before any additive DDL.
        for(JsonNode def:models.values()) validateExisting(def,models);
        for(JsonNode def:models.values()) {
            String table=table(def),idType=def.path("model").path("idType").asText("auto");
            List<String> columns=new ArrayList<>();
            columns.add("`id` "+(idType.equals("uuid")?"VARCHAR(64) PRIMARY KEY":idType.equals("auto")?"BIGINT AUTO_INCREMENT PRIMARY KEY":"BIGINT PRIMARY KEY"));
            for(JsonNode f:def.path("fields")) columns.add(column(f,models,true));
            columns.add("`version` BIGINT NOT NULL DEFAULT 0");
            columns.add("`created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
            columns.add("`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
            columns.add("`created_by` VARCHAR(64) DEFAULT 'demo'");columns.add("`updated_by` VARCHAR(64) DEFAULT 'demo'");
            if(logicalDelete(def))columns.add("`deleted` BOOLEAN NOT NULL DEFAULT FALSE");
            jdbc.execute("CREATE TABLE IF NOT EXISTS "+q(table)+" ("+String.join(",",columns)+")");
            Set<String> existing=columns(table);
            for(JsonNode f:def.path("fields")) {
                String name=f.path("code").asText();
                if(!existing.contains(name.toLowerCase(Locale.ROOT))) {
                    // Existing rows cannot acquire a required value implicitly. Reject instead of silently weakening constraints.
                    long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+q(table),Long.class);
                    if(f.path("required").asBoolean()&&count>0) throw new IllegalStateException("表 "+table+" 非空，新增必填字段 "+name+" 需要先迁移数据");
                    jdbc.execute("ALTER TABLE "+q(table)+" ADD COLUMN "+column(f,models,true));
                    if(f.hasNonNull("defaultValue")&&f.get("defaultValue").isValueNode()) {
                        JsonNode value=f.get("defaultValue");Object parameter=value.isNumber()?value.decimalValue():value.isBoolean()?value.booleanValue():value.asText();
                        jdbc.update("UPDATE "+q(table)+" SET "+q(name)+"=? WHERE "+q(name)+" IS NULL",parameter);
                    }
                    log.info("Added column {}.{}",table,name);
                }
                if(f.path("unique").asBoolean()||f.path("index").asBoolean()) {
                    String index=(f.path("unique").asBoolean()?"ux_":"ix_")+table+"_"+name;
                    if(index.length()>60)index=index.substring(0,45)+Integer.toUnsignedString(index.hashCode());
                    if(!indexes(table).contains(index.toLowerCase(Locale.ROOT)))jdbc.execute("CREATE "+(f.path("unique").asBoolean()?"UNIQUE ":"")+"INDEX "+q(index)+" ON "+q(table)+" ("+q(name)+")");
                }
            }
            if(def.path("model").path("rowScope").path("creator").asBoolean()) {
                String index="ix_"+table+"_created_by";
                if(index.length()>60)index=index.substring(0,45)+Integer.toUnsignedString(index.hashCode());
                if(!indexes(table).contains(index.toLowerCase(Locale.ROOT)))jdbc.execute("CREATE INDEX "+q(index)+" ON "+q(table)+" (`created_by`)");
            }
            Set<String> expected=new HashSet<>(fields(def).keySet().stream().map(s->s.toLowerCase(Locale.ROOT)).toList());
            expected.addAll(Set.of("id","version","created_at","updated_at","created_by","updated_by","deleted"));
            for(String old:existing)if(!expected.contains(old))log.warn("Column {}.{} is not in metadata; never dropping automatically",table,old);
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_state_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, model_code VARCHAR(100) NOT NULL, record_id VARCHAR(64) NOT NULL, from_state VARCHAR(100), to_state VARCHAR(100) NOT NULL, reason VARCHAR(1000), actor VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }
    private record ColumnInfo(int type,int size,int scale,boolean nullable,boolean autoIncrement) { }
    private Map<String,ColumnInfo> columnInfo(String table) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Map<String,ColumnInfo>>)c->{
            Map<String,ColumnInfo> result=new HashMap<>();
            for(String candidate:List.of(table,table.toUpperCase(Locale.ROOT))) {
                try(ResultSet rs=c.getMetaData().getColumns(c.getCatalog(),null,candidate,null)) {
                    while(rs.next())result.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),new ColumnInfo(rs.getInt("DATA_TYPE"),rs.getInt("COLUMN_SIZE"),rs.getInt("DECIMAL_DIGITS"),rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,"YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"))));
                }
                if(!result.isEmpty())break;
            }
            return result;
        });
    }
    private void validateExisting(JsonNode def,Map<String,JsonNode> models) {
        String table=table(def);Map<String,ColumnInfo> existing=columnInfo(table);if(existing.isEmpty())return;
        List<String> incompatible=new ArrayList<>();
        String idType=def.path("model").path("idType").asText("auto");ColumnInfo id=existing.get("id");
        if(id==null||(idType.equals("uuid")?id.type!=Types.VARCHAR||id.size!=64:id.type!=Types.BIGINT)||id!=null&&id.autoIncrement!=idType.equals("auto"))incompatible.add("id类型/生成策略");
        for(JsonNode field:def.path("fields")) {
            String name=field.path("code").asText(),type=field.path("type").asText();ColumnInfo col=existing.get(name.toLowerCase(Locale.ROOT));
            if(col==null) {
                if(field.path("required").asBoolean()&&jdbc.queryForObject("SELECT COUNT(*) FROM "+q(table),Long.class)>0)incompatible.add("非空表不能自动新增必填字段 "+name);
                continue;
            }
            boolean match=switch(type) {
                case "string" -> col.type==Types.VARCHAR&&col.size==field.path("length").asInt(255);
                case "enum" -> col.type==Types.VARCHAR&&col.size==100;
                case "text" -> Set.of(Types.CLOB,Types.LONGVARCHAR,Types.VARCHAR).contains(col.type);
                case "integer" -> col.type==Types.BIGINT;
                case "decimal" -> Set.of(Types.DECIMAL,Types.NUMERIC).contains(col.type)&&col.size==field.path("precision").asInt(18)&&col.scale==field.path("scale").asInt(2);
                case "boolean" -> Set.of(Types.BOOLEAN,Types.BIT,Types.TINYINT).contains(col.type);
                case "date" -> col.type==Types.DATE;
                case "datetime" -> col.type==Types.TIMESTAMP;
                case "ref" -> models.get(field.path("ref").asText()).path("model").path("idType").asText("auto").equals("uuid")?col.type==Types.VARCHAR&&col.size==64:col.type==Types.BIGINT;
                default -> false;
            };
            if(!match||col.nullable==field.path("required").asBoolean())incompatible.add(name+"类型/长度/精度/必填约束");
        }
        Set<String> expected=new HashSet<>(fields(def).keySet().stream().map(s->s.toLowerCase(Locale.ROOT)).toList());
        for(var entry:existing.entrySet())if(!expected.contains(entry.getKey())&&!Set.of("id","version","created_at","updated_at","created_by","updated_by","deleted").contains(entry.getKey())&&!entry.getValue().nullable)incompatible.add("不能移除数据库必填列 "+entry.getKey());
        if(!incompatible.isEmpty()) {
            String message="表 "+table+" 存在不兼容变更，需要人工迁移："+String.join("；",incompatible);
            log.warn(message);throw new IllegalStateException(message);
        }
    }
    private String column(JsonNode f,Map<String,JsonNode> models,boolean constraints) {
        String type=f.path("type").asText();
        String sql=switch(type) {
            case "string" -> "VARCHAR("+f.path("length").asInt(255)+")";
            case "text" -> "TEXT";
            case "integer" -> "BIGINT";
            case "decimal" -> "DECIMAL("+f.path("precision").asInt(18)+","+f.path("scale").asInt(2)+")";
            case "boolean" -> "BOOLEAN";
            case "date" -> "DATE";
            case "datetime" -> "TIMESTAMP";
            case "enum" -> "VARCHAR(100)";
            case "ref" -> models.get(f.path("ref").asText()).path("model").path("idType").asText("auto").equals("uuid")?"VARCHAR(64)":"BIGINT";
            default -> throw new IllegalArgumentException("Unsupported type "+type);
        };
        return q(f.path("code").asText())+" "+sql+(constraints&&f.path("required").asBoolean()?" NOT NULL":"");
    }
    private Set<String> columns(String table) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Set<String>>)c->{
            Set<String> result=new HashSet<>();DatabaseMetaData md=c.getMetaData();
            for(String candidate:List.of(table,table.toUpperCase(Locale.ROOT))) {
                try(ResultSet rs=md.getColumns(c.getCatalog(),null,candidate,null)){while(rs.next())result.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}
                if(!result.isEmpty())break;
            }
            return result;
        });
    }
    private Set<String> indexes(String table) {
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Set<String>>)c->{
            Set<String> result=new HashSet<>();
            for(String candidate:List.of(table,table.toUpperCase(Locale.ROOT))) {
                try(ResultSet rs=c.getMetaData().getIndexInfo(c.getCatalog(),null,candidate,false,false)){while(rs.next()){String n=rs.getString("INDEX_NAME");if(n!=null)result.add(n.toLowerCase(Locale.ROOT));}}
                if(!result.isEmpty())break;
            }
            return result;
        });
    }
}
