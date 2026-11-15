package com.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.meta.MetaSnapshot;
import com.platform.auth.AuthContext;
import com.platform.auth.RowScope;
import com.platform.rpc.RpcDispatcher;
import com.platform.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interprets service JSON; business changes and the idempotency receipt share one transaction. */
@Service
public class ServiceOrchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceOrchestrator.class);
    private static final Pattern PATH_PART = Pattern.compile("(?:^|\\.)([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)\\]");
    private final RpcDispatcher dispatcher;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final ServiceCallLog logs;
    private final RowScope rowScope;
    private final ThreadLocal<Deque<String>> callStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Deque<ServiceCallLog.Entry>> logStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<List<ServiceCallLog.Entry>> logEntries = ThreadLocal.withInitial(ArrayList::new);

    public ServiceOrchestrator(RpcDispatcher dispatcher, JdbcTemplate jdbc, ObjectMapper mapper,
                               PlatformTransactionManager transactionManager, RowScope rowScope, ServiceCallLog logs) {
        this.dispatcher = dispatcher;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowScope = rowScope;
        this.transaction = new TransactionTemplate(transactionManager);
        this.logs = logs;
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_service_idempotency ("
                + "service_code VARCHAR(128) NOT NULL, request_key VARCHAR(160) NOT NULL, "
                + "params_hash VARCHAR(64) NOT NULL, result_json TEXT, created_at TIMESTAMP NOT NULL, "
                + "PRIMARY KEY(service_code, request_key))");
    }

    public Object invoke(MetaSnapshot snapshot, String serviceCode, JsonNode suppliedParams) {
        JsonNode definition = snapshot.services().get(serviceCode);
        if (definition == null) throw new RpcException(-32601, "服务不存在：" + serviceCode);
        Deque<String> stack = callStack.get();
        if (stack.contains(serviceCode) || stack.size() >= 32)
            throw new RpcException(-32010, "自定义服务存在循环或嵌套过深");
        boolean topLevel = stack.isEmpty();
        String digest = digest(suppliedParams == null ? NullNode.instance : suppliedParams);
        ServiceCallLog.Entry entry = new ServiceCallLog.Entry(logStack.get().peek(), serviceCode, suppliedParams, digest,
                definition.path("transactional").asBoolean(true));
        String traceId = entry.id;
        logStack.get().push(entry); logEntries.get().add(entry);
        Integer errorCode = null;
        stack.push(serviceCode);
        try {
            boolean systemScope = definition.path("systemScope").asBoolean();
            if (systemScope && (topLevel || !definition.path("internalOnly").asBoolean()))
                throw new RpcException(-32021, "系统数据范围仅允许内部业务服务调用");
            ObjectNode params = validateParams(definition, suppliedParams);
            String keyName = definition.path("idempotencyKey").asText("");
            if (!keyName.isBlank() && params.path(keyName).isValueNode()) {
                String key = params.path(keyName).asText();
                if (key.length() <= 160) entry.requestKey = key;
            }
            ObjectNode receiptScope=mapper.createObjectNode();receiptScope.set("params",params);receiptScope.put("actor",AuthContext.actor());
            if(AuthContext.current()!=null) {
                receiptScope.set("permissions",mapper.valueToTree(AuthContext.current().permissions().stream().sorted().toList()));
                ObjectNode policies=receiptScope.putObject("rowScopes");
                snapshot.models().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(modelEntry->{
                    JsonNode policy=modelEntry.getValue().path("model").get("rowScope");
                    if(policy!=null)policies.set(modelEntry.getKey(),policy);
                });
            }
            String paramsDigest = digest(receiptScope);
            boolean transactional = definition.path("transactional").asBoolean(true);
            if (!"rollback".equals(definition.path("onError").asText("rollback")))
                throw new RpcException(-32602, "本版本仅支持 onError=rollback");
            if (!transactional && definition.hasNonNull("idempotencyKey"))
                throw new RpcException(-32602, "幂等服务必须启用事务");
            Supplier<Object> body = () -> {
                // Recheck scoped refs before even replaying a receipt: privileges may have been revoked.
                for (JsonNode spec : definition.path("params")) {
                    JsonNode value=params.get(spec.path("name").asText());
                    JsonNode target=snapshot.models().get(spec.path("ref").asText());
                    if (spec.path("type").asText().equals("ref") && value!=null && !value.isNull()
                            && target!=null && rowScope.restricted(target)) {
                        ObjectNode lookup=mapper.createObjectNode();lookup.set("id",value);
                        dispatcher.dispatch(snapshot,spec.path("ref").asText()+".read",lookup);
                    }
                }
                return executeOnce(snapshot, serviceCode, definition, params, paramsDigest);
            };
            Supplier<Object> execution = systemScope ? () -> RowScope.system(body) : body;
            return transactional ? transaction.execute(status -> execution.get()) : execution.get();
        } catch (RpcException e) {
            errorCode = e.getCode();
            throw e;
        } catch (RuntimeException e) {
            errorCode = -32010;
            LOG.error("Service {} failed; trace {}", serviceCode, traceId, e);
            throw new RpcException(-32010, "服务执行失败", Map.of("traceId", traceId));
        } finally {
            entry.finish(errorCode);
            logStack.get().pop();
            stack.pop();
            if (topLevel) {
                callStack.remove();
                logs.save(logEntries.get(), entry);
                logEntries.remove(); logStack.remove();
            }
        }
    }

    private Object executeOnce(MetaSnapshot snapshot, String code, JsonNode definition,
                               ObjectNode params, String paramsDigest) {
        String keyName = definition.path("idempotencyKey").asText("");
        String requestKey = null;
        if (!keyName.isBlank()) {
            JsonNode key = params.get(keyName);
            if (key == null || key.isNull() || !key.isValueNode() || key.asText().isBlank()
                    || key.asText().length() > 160)
                throw new RpcException(-32602, "幂等键不能为空且长度不能超过160", Map.of("field", keyName));
            requestKey = key.asText();
            List<Map<String, Object>> stored = jdbc.queryForList(
                    "SELECT params_hash, result_json FROM t_service_idempotency WHERE service_code=? AND request_key=?",
                    code, requestKey);
            if (!stored.isEmpty()) {
                Map<String, Object> receipt = stored.get(0);
                if (!paramsDigest.equals(receipt.get("params_hash")))
                    throw new RpcException(-32012, "同一幂等键不能用于不同的请求参数");
                if (receipt.get("result_json") == null)
                    throw new RpcException(-32012, "请求正在处理中，请使用相同请求编号重试");
                logStack.get().peek().replayed = true;
                return readJson(receipt.get("result_json").toString());
            }
            try {
                // The UNIQUE/PRIMARY KEY is the concurrency gate, never a check-then-act alone.
                jdbc.update("INSERT INTO t_service_idempotency(service_code,request_key,params_hash,result_json,created_at) "
                        + "VALUES(?,?,?,NULL,CURRENT_TIMESTAMP)", code, requestKey, paramsDigest);
            } catch (DuplicateKeyException e) {
                // A competing transaction won. Retry uses its committed receipt; no business step ran here.
                throw new RpcException(-32012, "同一请求正在处理或已完成，请使用相同请求编号重试");
            }
        }

        ObjectNode context = mapper.createObjectNode();
        context.set("params", params);
        ArrayNode steps = context.putArray("steps");
        ArrayNode guards = context.putArray("guards");
        int guardIndex = 0;
        for (JsonNode guard : definition.path("guards")) {
            Object result;
            try {
                result = call(snapshot, guard, context);
            } catch (RpcException e) {
                throw new RpcException(-32011, guard.path("message").asText("前置条件检查失败"),
                        Map.of("guard", guardIndex, "cause", e.getMessage(), "causeCode", e.getCode()));
            }
            JsonNode resultNode = mapper.valueToTree(result);
            context.set("result", resultNode);
            if (!checkGuard(guard, context, resultNode))
                throw new RpcException(-32011, guard.path("message").asText("前置条件不满足"), Map.of("guard", guardIndex));
            guards.addObject().set("result", resultNode);
            guardIndex++;
        }
        context.remove("result");
        Object result = null;
        int stepIndex = 0;
        for (JsonNode step : definition.path("steps")) {
            try {
                result = executeStep(snapshot, step, context, 0);
                steps.addObject().set("result", mapper.valueToTree(result));
            } catch (RpcException e) {
                throw new RpcException(-32010, "服务步骤 " + (stepIndex + 1) + " 失败：" + e.getMessage(),
                        Map.of("step", stepIndex, "call", step.path("call").asText(),
                                "cause", e.getMessage(), "causeCode", e.getCode()));
            }
            stepIndex++;
        }
        if (definition.has("return")) result = evaluate(definition.get("return"), context);
        if (requestKey != null) {
            jdbc.update("UPDATE t_service_idempotency SET result_json=? WHERE service_code=? AND request_key=?",
                    writeJson(result), code, requestKey);
        }
        return result;
    }

    private Object call(MetaSnapshot snapshot, JsonNode step, ObjectNode context) {
        String method = step.path("call").asText();
        if (method.isBlank() || method.startsWith("meta."))
            throw new RpcException(-32602, "自定义服务不允许调用元数据管理方法");
        JsonNode mapped = evaluate(step.path("map"), context);
        if (!mapped.isObject()) throw new RpcException(-32602, "服务步骤 map 必须为对象");
        return dispatcher.dispatch(snapshot, method, mapped);
    }

    /** Bounded metadata loops let a batch event update affected records in the same transaction. */
    private Object executeStep(MetaSnapshot snapshot, JsonNode step, ObjectNode context, int depth) {
        if(depth>8)throw new RpcException(-32602,"服务步骤嵌套超过8层");
        if(step.has("when")&&!condition(step.get("when"),context))return null;
        if(!step.has("forEach"))return call(snapshot,step,context);
        JsonNode items=evaluate(step.get("forEach"),context);
        if(!items.isArray()||items.size()>200)throw new RpcException(-32602,"循环来源必须为不超过200项的数组");
        JsonNode previousItem=context.get("item"),previousSteps=context.get("loopSteps");
        List<Object> results=new ArrayList<>();
        try {
            for(JsonNode item:items){context.set("item",item);ArrayNode locals=context.putArray("loopSteps");
                for(JsonNode nested:step.path("steps")){Object result=executeStep(snapshot,nested,context,depth+1);locals.addObject().set("result",mapper.valueToTree(result));}
                results.add(locals.deepCopy());
            }
        }finally{if(previousItem==null)context.remove("item");else context.set("item",previousItem);if(previousSteps==null)context.remove("loopSteps");else context.set("loopSteps",previousSteps);}
        return results;
    }

    private boolean checkGuard(JsonNode guard, ObjectNode context, JsonNode result) {
        JsonNode condition = guard.get("condition");
        if (condition == null) {
            if (result.isBoolean()) return result.booleanValue();
            return !result.isObject() || !result.has("allowed") || result.path("allowed").asBoolean(false);
        }
        return condition(condition, context);
    }

    private boolean condition(JsonNode condition, ObjectNode context) {
        if (condition.has("all")) {
            for (JsonNode child : condition.get("all")) if (!condition(child, context)) return false;
            return true;
        }
        if (condition.has("any")) {
            for (JsonNode child : condition.get("any")) if (condition(child, context)) return true;
            return false;
        }
        JsonNode left = evaluate(condition.path("left"), context);
        JsonNode right = evaluate(condition.path("right"), context);
        String op = condition.path("op").asText("eq");
        if ("in".equals(op)) {
            if (!right.isArray()) throw new RpcException(-32602, "in条件右侧必须为数组");
            for (JsonNode option : right) if (equal(left, option)) return true;
            return false;
        }
        if ("eq".equals(op)) return equal(left, right);
        if ("ne".equals(op)) return !equal(left, right);
        if (left.isNull() || right.isNull()) return false;
        int comparison = left.isNumber() && right.isNumber()
                ? left.decimalValue().compareTo(right.decimalValue()) : left.asText().compareTo(right.asText());
        return switch (op) {
            case "gt" -> comparison > 0;
            case "ge" -> comparison >= 0;
            case "lt" -> comparison < 0;
            case "le" -> comparison <= 0;
            default -> throw new RpcException(-32602, "不支持的守卫操作符：" + op);
        };
    }

    private boolean equal(JsonNode left, JsonNode right) {
        return left.isNumber() && right.isNumber()
                ? left.decimalValue().compareTo(right.decimalValue()) == 0 : left.equals(right);
    }

    /** Closed expression vocabulary; JSON is never executed as JavaScript, SpEL or SQL. */
    private JsonNode evaluate(JsonNode expression, ObjectNode context) {
        if (expression == null || expression.isMissingNode()) return mapper.createObjectNode();
        if (expression.isTextual()) {
            String text = expression.textValue();
            if (text.startsWith("params.") || text.startsWith("steps[")
                    || text.startsWith("guards[") || text.startsWith("loopSteps[") || text.startsWith("item.") || text.equals("result") || text.startsWith("result."))
                return resolve(text, context);
            return expression.deepCopy();
        }
        if (expression.isObject()) {
            if (expression.size() == 1 && expression.has("const")) return expression.get("const").deepCopy();
            if (expression.size() == 1 && expression.has("negate"))
                return numeric(number(evaluate(expression.get("negate"), context)).negate());
            if (expression.size() == 1 && (expression.has("add") || expression.has("subtract"))) {
                boolean add = expression.has("add");
                JsonNode args = expression.get(add ? "add" : "subtract");
                if (!args.isArray() || args.size() < 2) throw new RpcException(-32602, "运算至少需要两个参数");
                BigDecimal value = number(evaluate(args.get(0), context));
                for (int i = 1; i < args.size(); i++) {
                    BigDecimal operand = number(evaluate(args.get(i), context));
                    value = add ? value.add(operand) : value.subtract(operand);
                }
                return numeric(value);
            }
            ObjectNode result = mapper.createObjectNode();
            expression.fields().forEachRemaining(entry -> result.set(entry.getKey(), evaluate(entry.getValue(), context)));
            return result;
        }
        if (expression.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            expression.forEach(item -> result.add(evaluate(item, context)));
            return result;
        }
        return expression.deepCopy();
    }

    private BigDecimal number(JsonNode node) {
        if (!node.isNumber()) throw new RpcException(-32602, "数值运算参数必须为数字");
        return node.decimalValue();
    }
    private JsonNode numeric(BigDecimal value) {
        return value.stripTrailingZeros().scale()<=0?com.fasterxml.jackson.databind.node.BigIntegerNode.valueOf(value.toBigIntegerExact()):DecimalNode.valueOf(value);
    }

    private JsonNode resolve(String path, JsonNode context) {
        Matcher matcher = PATH_PART.matcher(path);
        int end = 0;
        JsonNode cursor = context;
        while (matcher.find()) {
            if (matcher.start() != end) throw new RpcException(-32602, "无效的映射路径：" + path);
            cursor = matcher.group(1) != null ? cursor.path(matcher.group(1))
                    : cursor.path(Integer.parseInt(matcher.group(2)));
            end = matcher.end();
        }
        if (end != path.length() || cursor.isMissingNode())
            throw new RpcException(-32602, "映射路径不存在：" + path);
        return cursor.deepCopy();
    }

    private ObjectNode validateParams(JsonNode definition, JsonNode suppliedParams) {
        if (suppliedParams == null || suppliedParams.isNull()) suppliedParams = mapper.createObjectNode();
        if (!suppliedParams.isObject()) throw new RpcException(-32602, "服务参数必须为对象");
        ObjectNode params = ((ObjectNode) suppliedParams).deepCopy();
        Set<String> allowed = new HashSet<>();
        List<Map<String, String>> errors = new ArrayList<>();
        for (JsonNode spec : definition.path("params")) {
            String name = spec.path("name").asText(spec.path("code").asText());
            allowed.add(name);
            if (!params.has(name) && spec.has("defaultValue")) params.set(name, spec.get("defaultValue").deepCopy());
            JsonNode value = params.get(name);
            String label = spec.path("label").asText(name);
            if (value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
                if (spec.path("required").asBoolean(false)) errors.add(fieldError(name, label + "不能为空"));
                continue;
            }
            String type = spec.path("type").asText("string");
            boolean valid = switch (type) {
                case "string", "text", "enum", "date", "datetime" -> value.isTextual();
                case "integer" -> value.isIntegralNumber() && value.canConvertToLong();
                case "decimal" -> value.isNumber();
                case "boolean" -> value.isBoolean();
                case "ref" -> (value.isIntegralNumber() && value.canConvertToLong()) || value.isTextual();
                default -> false;
            };
            if (!valid) {
                errors.add(fieldError(name, label + "类型不正确，应为" + type));
                continue;
            }
            if (value.isNumber()) {
                if (spec.has("min") && value.decimalValue().compareTo(spec.get("min").decimalValue()) < 0)
                    errors.add(fieldError(name, label + "不能小于" + spec.get("min")));
                if (spec.has("max") && value.decimalValue().compareTo(spec.get("max").decimalValue()) > 0)
                    errors.add(fieldError(name, label + "不能大于" + spec.get("max")));
            }
            if (value.isTextual()) {
                String text = value.textValue();
                int maxLength = spec.path("maxLength").asInt(spec.path("length").asInt(Integer.MAX_VALUE));
                int minLength = spec.path("minLength").asInt(0);
                if (text.length() > maxLength) errors.add(fieldError(name, label + "长度不能超过" + maxLength));
                if (text.length() < minLength) errors.add(fieldError(name, label + "长度不能少于" + minLength));
                if (spec.has("pattern") && !Pattern.matches(spec.get("pattern").asText(), text))
                    errors.add(fieldError(name, label + "格式不正确"));
                if ("date".equals(type) || "datetime".equals(type)) {
                    try {
                        if ("date".equals(type)) LocalDate.parse(text);
                        else LocalDateTime.parse(text.replace(' ', 'T'));
                    } catch (DateTimeParseException ex) {
                        errors.add(fieldError(name, label + "日期格式不正确"));
                    }
                }
                if ("enum".equals(type) && spec.has("options")) {
                    boolean found = false;
                    for (JsonNode option : spec.get("options"))
                        if (text.equals(option.isObject() ? option.path("value").asText() : option.asText())) found = true;
                    if (!found) errors.add(fieldError(name, label + "不在允许的选项内"));
                }
            }
        }
        Iterator<String> names = params.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) errors.add(fieldError(name, "不允许的服务参数：" + name));
        }
        if (!errors.isEmpty()) throw new RpcException(-32602, "服务参数校验失败", Map.of("fields", errors));
        return params;
    }

    private Map<String, String> fieldError(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    private String digest(JsonNode value) {
        try {
            byte[] bytes = writeJson(canonical(value)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            ObjectNode sorted = mapper.createObjectNode();
            fields.forEach(field -> sorted.set(field, canonical(value.get(field))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = mapper.createArrayNode();
            value.forEach(item -> sorted.add(canonical(item)));
            return sorted;
        }
        return value;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RpcException(-32010, "服务结果无法序列化");
        }
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new RpcException(-32010, "幂等记录无法读取");
        }
    }

}
