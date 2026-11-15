package com.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.meta.MetaEngine;
import com.platform.service.CrudExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: a million rows live only in a disposable in-memory H2 database. */
@EnabledIfSystemProperty(named="platform.perf",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:performance-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","platform.demo.enabled=false","platform.auth.bootstrap-password=InitialTest!12345"})
class PerformanceAcceptanceTest {
    @Autowired JdbcTemplate jdbc;@Autowired CrudExecutor crud;@Autowired MetaEngine engine;@Autowired ObjectMapper mapper;
    @DynamicPropertySource static void meta(DynamicPropertyRegistry r){r.add("platform.meta-dir",()->(Files.isDirectory(Path.of("../meta"))?Path.of("../meta"):Path.of("meta")).toAbsolutePath().toString());}
    @Test void millionRowFilteredListP95() throws Exception {
        for(int batch=0;batch<1000;batch++){
            List<Object[]> data=new ArrayList<>();for(int n=0;n<1000;n++){int i=batch*1000+n;data.add(new Object[]{"PERF-"+i,"学生"+i,i%100});}
            jdbc.batchUpdate("INSERT INTO t_student(studentNo,name,age) VALUES(?,?,?)",data);
        }
        assertEquals(1_000_000,jdbc.queryForObject("SELECT COUNT(*) FROM t_student",Integer.class));
        var params=mapper.valueToTree(Map.of("page",1,"pageSize",20,"filters",List.of(Map.of("field","name","op","like","value","学生9"))));
        for(int n=0;n<5;n++)crud.execute(engine.snapshot(),"student","list",params);
        List<Double> samples=new ArrayList<>();for(int n=0;n<30;n++){long start=System.nanoTime();var result=mapper.valueToTree(crud.execute(engine.snapshot(),"student","list",params));assertTrue(result.path("total").asInt()>0);assertEquals(20,result.path("records").size());samples.add((System.nanoTime()-start)/1_000_000.0);}
        Collections.sort(samples);double p95=samples.get((int)Math.ceil(samples.size()*.95)-1);
        Path report=Path.of("target/performance-report.txt");Files.writeString(report,"Database=H2 in-memory\nRows=1000000\nScenario=name LIKE '%学生9%', pageSize=20, count+list\nSamples=30\nP95_ms="+p95+"\nScope=service layer, single caller, excludes HTTP/auth/network\n");
        assertTrue(p95<=1000,"P95超过1秒，实测："+p95+"ms");
    }
}
