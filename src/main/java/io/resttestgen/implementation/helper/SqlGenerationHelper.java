package io.resttestgen.implementation.helper;


import io.resttestgen.core.openapi.Operation;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.checker.MysqlWhereConditionChecker;
import io.resttestgen.implementation.sql.checker.MysqlWhereConditionChecker.ControlType;
import io.resttestgen.implementation.sql.matcher.ApiParameterMatcher;

import java.util.*;

public class SqlGenerationHelper {

    public enum SqlOperator {
        EQUALS("="),
        NOT_EQUALS("<>"),
        GREATER_THAN(">"),
        GREATER_THAN_OR_EQUALS(">="),
        LESS_THAN("<"),
        LESS_THAN_OR_EQUALS("<="),
        IN("IN"),
        NOT_IN("NOT IN"),
        BETWEEN("BETWEEN"),
        LIKE("LIKE"),
        IS_NULL("IS NULL");

        private final String symbol;
        SqlOperator(String symbol) { this.symbol = symbol; }
        public String getSymbol() { return symbol; }
    }

    /**
     * 生成 WHERE 子句并清洗参数 Map。
     * <p>
     * ⚠️ 注意：此方法会<b>直接修改 (Mutate)</b> 传入的 whereParams Map！
     * 方法执行后，Map 中将只包含 JDBC 需要填入占位符 (?) 的参数。
     * 控制参数 (limit, sort) 和 NULL 值参数会被生成到 SQL 字符串中，并从 Map 中移除。
     *
     * @param whereParams 包含所有候选参数的 Map (必须是可修改的 Map，如 LinkedHashMap)
     * @return 生成的 SQL 片段 (包含 WHERE 子句 和 后缀如 LIMIT/ORDER BY)
     */
    public static String generateWhereClauseAndCleanParams(Map<String, Object> whereParams, Operation op, ConvertSequenceToTable convertSequenceToTable) {
        if (whereParams == null || whereParams.isEmpty()) {
            return "";
        }

        StringBuilder whereSql = new StringBuilder();

        // 临时变量存排序状态
        Object limitVal = null;
        Object offsetVal = null;
        String orderByCol = null;
        String orderByDir = null;
        boolean isFirstCondition = true;

        // 使用 Iterator 遍历，以便在遍历过程中安全地删除元素 (remove)
        Iterator<Map.Entry<String, Object>> iterator = whereParams.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            String paramName = entry.getKey();
            Object val = entry.getValue();

            //检查是否为控制关键字
            ControlType type = MysqlWhereConditionChecker.getControlType(paramName);
            if (type != ControlType.NONE && type != ControlType.EXCLUDE) {
                //是控制参数：处理并从 Map 中移除
                switch (type) {
                    case LIMIT:
                        limitVal = val; // 暂存，不拼字符串
                        break;
                    case OFFSET:
                        offsetVal = val; // 暂存，不拼字符串
                        break;
                    case SORT:
                        String v = String.valueOf(val);
                        if ("desc".equalsIgnoreCase(orderByDir) || "asc".equalsIgnoreCase(orderByDir)) {
                            orderByCol = "id";
                            orderByDir = v.toLowerCase();
                        } else {
                            orderByCol = convertSequenceToTable.getColumnNameByName(v, op);
                            orderByDir = "asc";
                        }
                        break;
                    default:
                        // 不应到达这里
                        break;
                }
                iterator.remove();
                continue;
            }
            //处理普通参数
            SqlOperator operator = inferOperator(paramName, val);
            String newName = cleanColumnName(paramName, operator, op, convertSequenceToTable);
            String colName = convertSequenceToTable.getColumnNameByName(newName, op);
            if (isFirstCondition) {
                whereSql.append(" WHERE ");
                isFirstCondition = false;
            } else {
                whereSql.append(" AND ");
            }
            //拼接 SQL 主体并收集参数
            switch (operator) {
                case IS_NULL:
                    whereSql.append(colName).append(" IS NULL");
                    iterator.remove();
                    break;
                case IN:
                case NOT_IN:
                    whereSql.append(colName).append(" ")
                            .append(operator.getSymbol()).append(" ")
                            .append(formatListValues(val)); // 直接拼 (1, 2)
                    iterator.remove();
                    break;
                case BETWEEN:
                    List<?> list = convertToList(val);
                    whereSql.append(colName).append(" BETWEEN ")
                            .append(formatSingleValue(list.get(0))).append(" AND ")
                            .append(formatSingleValue(list.get(1)));
                    iterator.remove();
                    break;
                case LIKE:
                    // 【保留】并原地修改值为 %val%
                    String likeVal = "%" + val + "%";
                    // formatSingleValue 会负责处理单引号转义
                    whereSql.append(colName).append(" LIKE ")
                            .append(formatSingleValue(likeVal));
                    iterator.remove();
                    break;
                default: // =, >, < 等
                    whereSql.append(colName).append(" ")
                            .append(operator.getSymbol()).append(" ?");
                    // 【保留】Map 中保留该参数，等待 JDBC 设置
                    break;
            }
        }

        if (orderByCol != null) {
            whereSql.append(" ORDER BY ").append(orderByCol);
            if (!orderByDir.isEmpty()) {
                whereSql.append(" ").append(orderByDir);
            }
        }
        // 2. 组装 Limit 和 Offset (核心修改)
        if (limitVal != null) {
            whereSql.append(" LIMIT ").append(parseSafeLong(limitVal));

            // 只有当 LIMIT 存在时，OFFSET 才有意义
            if (offsetVal != null) {
                whereSql.append(" OFFSET ").append(parseSafeLong(offsetVal));
            }
        }
        return whereSql.toString();
    }

    private static SqlOperator inferOperator(String paramName, Object val) {
        String name = paramName.toLowerCase();
        if ("null".equalsIgnoreCase(val.toString())) {
            return SqlOperator.IS_NULL;
        }
        if (name.endsWith("_exclude") || name.endsWith("_not_in")) {
            return SqlOperator.NOT_IN;
        }
        // 显式的包含后缀 (通常用于强制 List 语义)
        if (name.endsWith("_include")) {
            return SqlOperator.IN;
        }
        // 集合判断 (List/Array)
        if (val instanceof List || val.getClass().isArray()) {
            List<?> list = convertToList(val);
            // 如果是 exclude/except，则是 NOT IN (之前讲过)
            if (MysqlWhereConditionChecker.getControlType(paramName) == ControlType.EXCLUDE) {
                return SqlOperator.NOT_IN;
            }
            String n = name.toLowerCase();

            // 显式的范围关键字
            boolean hasRangeKeyword = n.contains("range") || n.contains("between") || n.contains("interval") || n.contains("period");

            // 隐含的时间范围 (启发式规则)
            // 如果名字包含 date/time/at，且数组长度刚好是 2，极大概率是范围查询
            // 例如: ?created_at=[t1, t2] 或 ?updated_time=[t1, t2]
            boolean looksLikeTimeRange = (n.contains("date") || n.contains("time") || n.endsWith("_at")) && list.size() == 2;

            if (hasRangeKeyword || looksLikeTimeRange) {
                return SqlOperator.BETWEEN;
            }

            return SqlOperator.IN;
        }
        if (name.endsWith("_like") || name.contains("search") || name.equals("q")) {
            return SqlOperator.LIKE;
        }
        // min_xx -> 想要 xx 的最小值是 val -> xx >= val
        if (name.startsWith("min_") || name.endsWith("_min") || name.endsWith("_gte")) {
            return SqlOperator.GREATER_THAN_OR_EQUALS;
        }
        // max_xx -> 想要 xx 的最大值是 val -> xx <= val
        if (name.startsWith("max_") || name.endsWith("_max") || name.endsWith("_lte")) {
            return SqlOperator.LESS_THAN_OR_EQUALS;
        }
        // 显式的大于小于
        if (name.endsWith("_gt")) {
            return SqlOperator.GREATER_THAN;
        }
        if (name.endsWith("_lt")) {
            return SqlOperator.LESS_THAN;
        }
        if (name.endsWith("_ne") || name.endsWith("_neq")) {
            return SqlOperator.NOT_EQUALS;
        }
        // 默认
        return SqlOperator.EQUALS;
    }

    /**
     * 清洗列名 (修正版)
     */
    private static String cleanColumnName(String paramName, SqlOperator operator, Operation operation, ConvertSequenceToTable convertSequenceToTable) {
        if (operator != SqlOperator.IN && operator != SqlOperator.NOT_IN) {
            // 对于非 IN/NOT IN，不需要特殊处理，直接返回
            return paramName;
        }
        //没有指定资源的，先默认资源id，再表id
        if ("include".equals(paramName) || "exclude".equals(paramName)) {
            String res = extractPathResourceContext(operation.getEndpoint(), paramName);
            String name = res + "_id";
            if (convertSequenceToTable.getColumnNameByName(name, operation) != null) {
                return name;
            } else {
                return "id";
            }
        }
        if (paramName.startsWith("include_") || paramName.startsWith("exclude_") || paramName.endsWith("_include") ||
                paramName.endsWith("_exclude") || paramName.endsWith("_not_in")) {
            paramName = paramName.replaceFirst(
                    "(?i)^(include|exclude)_|_(include|exclude|not_in)$",
                    "");
        }

        //对于不包含include或者exclude或者not_in的参数名
        String res = extractPathResourceContext(operation.getEndpoint(), paramName);
        //先判断有无资源的id列
        String name = res + "_id";
        if (convertSequenceToTable.getColumnNameByName(name, operation) != null) {
            return name;
        } else if (convertSequenceToTable.getColumnNameByName(paramName + "_id", operation) != null) {
            //再判断有无参数名 + id 对应的列
            return paramName + "_id";
        } else if (convertSequenceToTable.getColumnNameByName(paramName, operation) != null) {
            //最后判断有无参数名对应的列
            return paramName;
        } else {
            //都没有就返回参数名
            return "id";
        }
    }

    /**
     * 【关键】将 Java 对象格式化为 SQL 值字符串
     * String -> 'abc'
     * Number -> 123
     * Date -> '2023-01-01'
     */
    private static String formatSingleValue(Object val) {
        if (val == null) {
            return "NULL";
        }
        if (val instanceof Number || val instanceof Boolean) {
            return val.toString();
        }
        // 字符串需要加单引号，并转义内部的单引号
        String strVal = val.toString();
        // 简单的防注入：把 ' 变成 ''
        return "'" + strVal.replace("'", "''") + "'";
    }

    /**
     * 格式化列表 (1, 'a', 'b')
     */
    private static String formatListValues(Object val) {
        List<?> list = convertToList(val);
        if (list.isEmpty()) {
            return "(NULL)";
        }

        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < list.size(); i++) {
            sb.append(formatSingleValue(list.get(i)));
            if (i < list.size() - 1) {
                sb.append(",");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private static List<?> convertToList(Object val) {
        if (val instanceof List) {
            return (List<?>) val;
        }
        return Collections.singletonList(val);
    }

    private static String extractPathResourceContext(String endpoint, String paramName) {
        if (endpoint == null || endpoint.isEmpty()) {
            return "";
        }
        if (paramName == null) {
            paramName = "";
        }

        // Remove leading/trailing slashes and split
        String[] segments = endpoint.replaceAll("^/|/$", "").split("/");

        // Case 1: paramName is "id" -> look for {id} in path and return the preceding segment
        if ("id".equalsIgnoreCase(paramName)) {
            for (int i = 0; i < segments.length; i++) {
                // Check for {id} case-insensitively
                if (segments[i].equalsIgnoreCase("{id}") && i > 0) {
                    return segments[i - 1];
                }
            }
        }

        // Case 2: paramName is not "id" (or {id} not found) -> return the last non-parameter segment
        // Example: /pages/{id}/test -> test
        // Example: /users/{id} -> users (skips {id})
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            // If it's not a parameter (doesn't contain { or }), return it.
            if (!segment.contains("{") && !segment.contains("}")) {
                return segment;
            }
        }

        return "";
    }

    // 辅助解析方法：确保 Limit/Offset 即使收到 String 也能转成 Long，收到乱码则归零
    private static long parseSafeLong(Object val) {
        if (val == null) {
            return 0L;
        }
        try {
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
            // 尝试解析字符串 "10" -> 10
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            // 如果传入的是 "abc" 或 "10' OR 1=1"，解析失败，返回默认值
            return 0L; // 或者 10L，视需求而定
        }
    }
}
