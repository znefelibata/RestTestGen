package io.resttestgen.implementation.sql.checker;

import org.checkerframework.checker.units.qual.A;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 用于检查参数是否为 MySQL 的控制关键字（Control Keywords）。
 * 这些参数不应出现在 WHERE 子句中，而应拼接到 SQL 语句末尾或被忽略。
 */
public class MysqlWhereConditionChecker {

    public enum ControlType {
        LIMIT,      // 分页数量 (limit, size)
        OFFSET,     // 分页偏移 (offset, page)
        SORT,       // 排序字段 (sort_by, order_by) -> 对应 SQL 的 "列名"
        EXCLUDE,    // 是特殊的数组字段，NOT IN
        NONE        // 普通参数 (WHERE 条件)
    }

    // 使用 Set 提高查找性能 (O(1))
    // 归一化策略：所有关键字存储为 "小写" 且 "无下划线" 的形式

    // 1. 分页相关 (LIMIT / OFFSET)
    private static final Set<String> LIMIT_KEYWORDS = Stream.of(
            "limit", "count", "size", "pagesize", "perpage", "max"
    ).collect(Collectors.toSet());

    private static final Set<String> OFFSET_KEYWORDS = Stream.of(
            "offset", "page", "p", "start", "skip"
    ).collect(Collectors.toSet());

    // 2. 排序相关 (ORDER BY)
    private static final Set<String> SORT_KEYWORDS = Stream.of(
            "sort", "sortby", "order", "orderby", "ordering"
    ).collect(Collectors.toSet());

    private static final Set<String> EXCLUDE_KEYWORDS = Set.of(
            "exclude", "except", "without", "not_in", "not"
    );

    // 汇总所有控制关键字
    private static final Set<String> ALL_CONTROL_KEYWORDS = new HashSet<>();

    static {
        ALL_CONTROL_KEYWORDS.addAll(LIMIT_KEYWORDS);
        ALL_CONTROL_KEYWORDS.addAll(OFFSET_KEYWORDS);
        ALL_CONTROL_KEYWORDS.addAll(SORT_KEYWORDS);
        ALL_CONTROL_KEYWORDS.addAll(EXCLUDE_KEYWORDS);
    }

    public static ControlType getControlType(String paramName) {
        if (isLimitParameter(paramName)) {
            return ControlType.LIMIT;
        }
        if (isOffsetParameter(paramName)) {
            return ControlType.OFFSET;
        }
        if (isSortParameter(paramName)) {
            return ControlType.SORT;
        }
        if (isExcludeParameter(paramName)) {
            return ControlType.EXCLUDE;
        }
        return ControlType.NONE;
    }

    /**
     * 核心判断方法：检查该参数名是否是控制参数。
     * 如果返回 true，则该参数绝对不能直接放进 WHERE column = ? 中。
     *
     * @param paramName 参数名 (例如 "pageSize", "sort_by")
     * @return true 如果是控制参数
     */
    public static boolean isControlParameter(String paramName) {
        if (paramName == null || paramName.isEmpty()) {
            return false;
        }
        String normalized = normalize(paramName);
        return ALL_CONTROL_KEYWORDS.contains(normalized);
    }



    /**
     * 判断是否是 Limit 相关参数 (用于拼接到 SQL 末尾)
     */
    public static boolean isLimitParameter(String paramName) {
        return paramName != null && LIMIT_KEYWORDS.contains(normalize(paramName));
    }

    /**
     * 判断是否是 Offset 相关参数
     */
    public static boolean isOffsetParameter(String paramName) {
        return paramName != null && OFFSET_KEYWORDS.contains(normalize(paramName));
    }

    /**
     * 判断是否是 Sort/Order 相关参数 (用于拼接到 SQL 末尾)
     */
    public static boolean isSortParameter(String paramName) {
        return paramName != null && SORT_KEYWORDS.contains(normalize(paramName));
    }

    public static boolean isExcludeParameter(String paramName) {
        return paramName != null && EXCLUDE_KEYWORDS.contains(normalize(paramName));
    }

    /**
     * 归一化处理：转小写，去除下划线。
     * 例子:
     * "pageSize" -> "pagesize"
     * "sort_by"  -> "sortby"
     * "Limit"    -> "limit"
     */
    private static String normalize(String input) {
        return input.toLowerCase().replace("_", "");
    }
}
