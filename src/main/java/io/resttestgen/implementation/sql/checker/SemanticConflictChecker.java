package io.resttestgen.implementation.sql.checker;

public class SemanticConflictChecker {

    // --- 配置区域 ---

    // 1. 绝对互斥的“反义词对” (Antonym Pairs)
    // 只要出现这一对，不管长得多像，都是互斥的
    private static final String[][] ANTONYM_PAIRS = {
            {"include", "exclude"},
            {"min", "max"},
            {"start", "end"},
            {"begin", "finish"},
            {"first", "last"},
            {"prev", "next"},
            {"before", "after"},
            {"width", "height"}, // 宽高通常不混用
            {"lat", "lon"},      // 经纬度不混用
            {"latitude", "longitude"}
    };

    // 2. 互斥的“数据类型后缀” (Incompatible Data Types)
    // 针对 author_ip vs author_url, user_id vs user_name
    private static final String[][] DATA_TYPE_CONFLICTS = {
            {"ip", "url"},
            {"ip", "uri"},
            {"ip", "domain"},
            {"id", "name"},
            {"id", "title"},
            {"id", "desc"}, // id vs description
            {"email", "name"},
            {"email", "username"},
            {"phone", "email"}
    };

    // 3. 需要剥离的“通用噪音后缀” (Common Noise Suffixes)
    // 针对 date_format vs time_format
    // 如果两个词都以此结尾，我们把后缀去掉，比较剩下的部分 (Stem)
    private static final String[] NOISE_SUFFIXES = {
            "format", "config", "type", "mode", "style", "value", "str", "list", "array", "info"
    };

    /**
     * [核心入口] 判断两个参数名是否存在语义冲突
     * @param name1 原始参数名 (建议传入 normalized 之前的，如果有的话；或者 normalized 后的也可以)
     * @param name2 原始参数名
     * @return true 表示存在冲突（应直接给 0 分）
     */
    public static boolean isSemanticConflict(String name1, String name2) {
        if (name1 == null || name2 == null) return false;

        // 1. 预处理：统一转小写，去下划线 (使用你现有的 normalize 逻辑)
        String n1 = normalize(name1);
        String n2 = normalize(name2);

        // 如果归一化后完全相等，那肯定没冲突
        if (n1.equals(n2)) return false;

        // --- 策略 A: 检查反义词 (include vs exclude) ---
        if (checkAntonyms(n1, n2)) {
            return true;
        }

        // --- 策略 B: 检查数据类型后缀 (author_ip vs author_url) ---
        // 只有当两者前缀相似度较高时（比如都有 author），后缀的差异才致命
        // 这里简化处理：只要分别以此结尾，就认为冲突
        if (checkDataTypeConflicts(n1, n2)) {
            return true;
        }

        // --- 策略 C: 剥离噪音后缀 (date_format vs time_format) ---
        // 如果我们去掉了共同后缀后，剩下的部分产生了冲突
        if (checkStemConflict(n1, n2)) {
            return true;
        }

        return false;
    }

    // --- 内部逻辑实现 ---

    private static boolean checkAntonyms(String n1, String n2) {
        for (String[] pair : ANTONYM_PAIRS) {
            String a = pair[0];
            String b = pair[1];
            // 检查包含关系：比如 min_price vs max_price
            if ((n1.contains(a) && n2.contains(b)) || (n1.contains(b) && n2.contains(a))) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkDataTypeConflicts(String n1, String n2) {
        for (String[] pair : DATA_TYPE_CONFLICTS) {
            String typeA = pair[0];
            String typeB = pair[1];

            // 检查结尾：比如 authorip (endsWith ip) vs authorurl (endsWith url)
            // 注意：normalize 后没有下划线，所以直接匹配结尾
            boolean matchA = n1.endsWith(typeA);
            boolean matchB = n2.endsWith(typeB);

            // 如果 n1 结尾是 typeA, n2 结尾是 typeB -> 冲突
            if ((n1.endsWith(typeA) && n2.endsWith(typeB)) ||
                    (n1.endsWith(typeB) && n2.endsWith(typeA))) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkStemConflict(String n1, String n2) {
        for (String suffix : NOISE_SUFFIXES) {
            if (n1.endsWith(suffix) && n2.endsWith(suffix)) {
                // 两个都以 "format" 结尾
                // 提取词干：dateformat -> date, timeformat -> time
                String stem1 = n1.substring(0, n1.length() - suffix.length());
                String stem2 = n2.substring(0, n2.length() - suffix.length());

                // 如果去掉后缀后变空了 (比如参数名就是 "format")，跳过
                if (stem1.isEmpty() || stem2.isEmpty()) continue;

                // 递归检查词干是否有冲突！
                // 比如 date vs time (这里可以复用 checkAntonyms 或者加新的 Time/Date 逻辑)
                if (isTimeDateConflict(stem1, stem2)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 专门处理 Date vs Time 的逻辑
    private static boolean isTimeDateConflict(String s1, String s2) {
        boolean hasDate1 = s1.contains("date");
        boolean hasTime1 = s1.contains("time");
        boolean hasDate2 = s2.contains("date");
        boolean hasTime2 = s2.contains("time");

        // 纯 Date vs 纯 Time -> 互斥
        boolean pureDate1 = hasDate1 && !hasTime1;
        boolean pureTime1 = hasTime1 && !hasDate1;
        boolean pureDate2 = hasDate2 && !hasTime2;
        boolean pureTime2 = hasTime2 && !hasDate2;

        if ((pureDate1 && pureTime2) || (pureTime1 && pureDate2)) return true;

        return false;
    }

    // 复用你的 normalize
    private static String normalize(String s) {
        return s.replace("_", "").replace("-", "").toLowerCase();
    }
}
