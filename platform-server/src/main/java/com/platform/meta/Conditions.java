package com.platform.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.rpc.RpcException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small expression grammar; never evaluates JavaScript/SpEL/user code. */
public final class Conditions {
    private static final Pattern SIMPLE=Pattern.compile("^([a-zA-Z][a-zA-Z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(null|true|false|-?\\d+(?:\\.\\d+)?|'[^']*'|\"[^\"]*\")$");
    private Conditions() { }
    public static boolean supported(JsonNode node) { return node.isBoolean()||(node.isTextual()&&SIMPLE.matcher(node.asText().trim()).matches()); }
    public static boolean test(JsonNode condition,Map<String,Object> record) {
        if(condition==null||condition.isMissingNode())return true;
        if(condition.isBoolean())return condition.asBoolean();
        Matcher m=SIMPLE.matcher(condition.asText().trim());
        if(!m.matches())throw new RpcException(-32602,"不支持的条件表达式");
        Object left=record.get(m.group(1));String raw=m.group(3),op=m.group(2);Object right;
        if(raw.equals("null"))right=null;
        else if(raw.equals("true")||raw.equals("false"))right=Boolean.valueOf(raw);
        else if(raw.startsWith("'")||raw.startsWith("\""))right=raw.substring(1,raw.length()-1);
        else right=new BigDecimal(raw);
        if(op.equals("==")||op.equals("!=")) {
            boolean same=left instanceof Number&&right instanceof Number ? new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))==0 : Objects.equals(left,right);
            return op.equals("==")==same;
        }
        if(left==null||right==null)return false;
        int cmp=left instanceof Number&&right instanceof Number?new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())):left.toString().compareTo(right.toString());
        return switch(op){case ">"->cmp>0;case "<"->cmp<0;case ">="->cmp>=0;case "<="->cmp<=0;default->false;};
    }
}
