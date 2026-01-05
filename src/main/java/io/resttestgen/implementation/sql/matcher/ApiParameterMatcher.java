package io.resttestgen.implementation.sql.matcher;

import io.resttestgen.core.datatype.parameter.Parameter;
import io.resttestgen.core.datatype.parameter.attributes.ParameterLocation;
import io.resttestgen.core.datatype.parameter.attributes.ParameterType;
import io.resttestgen.core.datatype.parameter.attributes.ParameterTypeFormat;
import io.resttestgen.core.datatype.parameter.structured.ArrayParameter;
import io.resttestgen.core.datatype.parameter.structured.ObjectParameter;
import io.resttestgen.implementation.sql.checker.SemanticConflictChecker;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ApiParameterMatcher {
    // --- 权重配置 ---
    private static final double WEIGHT_DESCRIPTION = 0.1; // 描述占 10%
    private static final double WEIGHT_SCHEMA = 0.4;      // 结构/约束占 50%
    private static final double WEIGHT_RESOURCE = 0.5;    // 资源一致性占 50%

    /**
     * 判断两个参数是否是同一个逻辑参数
     * @return 0.0 ~ 1.0 的分数。通常 > 0.80 可视为同一个。
     */
    public static boolean isSameParameter(Parameter p1, String path1, Parameter p2, String path2) {
        double score = compare(p1, path1, p2, path2);
        return score >= 0.80;
    }

    public static double compare(Parameter p1, String path1, Parameter p2, String path2) {
        // 1. 【基础门槛】Type 必须完全一致
        if (!isBasicMetaMatch(p1, p2)) {
            return 0.0;
        }

        // 2. 【同一端点判定】逻辑
        // 如果路径相同 (例如都是 /api/users)，只是方法不同 (GET vs DELETE)
        // 且名字类型位置相同 -> 直接认为是同一参数
        if (p1.getName() == p2.getName() && (path1 != null  && path1.equals(path2))) {
            return 1.0;
        }
        //同一个端点下，名字不同肯定不是同一个参数，直接返回0.0
        if (p1.getName() != p2.getName() && (path1 != null  && path1.equals(path2))
                && p1.getOperation().getMethod() == p2.getOperation().getMethod()) {
            return 0.0;
        }
        if (SemanticConflictChecker.isSemanticConflict(p1.getName().toString(), p2.getName().toString())) {
            // 发现互斥语义，直接判死刑，返回 0.0
            return 0.0;
        }

        // 3. 【不同端点】深度分析逻辑
        return calculateDeepSimilarity(p1, path1, p2, path2);
    }

    private static boolean isBasicMetaMatch(Parameter p1, Parameter p2) {
        //检查类型
        if (!p1.getType().toString().equals(p2.getType().toString())) {
            return false;
        }
        return true;
    }

    private static double calculateDeepSimilarity(Parameter p1, String path1, Parameter p2, String path2) {
        double currentScore = 0.0;

        //描述相似度 (Description) - 10%
        String desc1 = normalize(p1.getDescription());
        String desc2 = normalize(p2.getDescription());
        double descScore = 0.0;
        if (!desc1.isEmpty() && !desc2.isEmpty()) {
            descScore = calculateLevenshteinSimilarity(desc1, desc2);
        } else if (desc1.isEmpty() && desc2.isEmpty()) {
            descScore = 0.5; // 都没描述，给个中性分
        }
        currentScore += descScore * WEIGHT_DESCRIPTION;

        //Schema 约束相似度 - 50%
        double schemaScore = calculateSchemaScore(p1, p2);
        currentScore += schemaScore * WEIGHT_SCHEMA;

        //Resource (资源) 检测 - 40% + 惩罚机制
        double resourceScore = 0.0;
        ParameterLocation loc1 = p1.getLocation();
        ParameterLocation loc2 = p2.getLocation();
        // Path 参数 一定是普通参数，进行id的比较，是否是统一路径占比很大
        if (loc1 == ParameterLocation.PATH && loc2 == ParameterLocation.PATH) {
            String res1 = extractPathResourceContext(path1, p1);
            String res2 = extractPathResourceContext(path2, p2);
            double nameScore = computeStringSimilarity(p1.getName().toString(), p2.getName().toString());
            if (res1.equals(res2)) {
                //同一个资源节点下，大概率是同一个参数
                resourceScore = 0.2 * nameScore + 0.8;
            } else if (p1.getName().toString().equals(p2.getName().toString())) {
                //不是同一个资源，但是名字相同，说明可能是类似的资源下的同名参数
                resourceScore = 0.4;
            } else {
                currentScore *= 0.5; //大幅度惩罚
            }
        } else if (loc1 == ParameterLocation.PATH || loc2 == ParameterLocation.PATH) {
            // 简单处理：只要名字高度相似 (user_id vs id) 且 资源名包含在 Body名里，就给分
//            String res1 = extractPathResourceContext(path1, p1);
//            String res2 = extractPathResourceContext(path2, p2);
//            double pathScore = computeStringSimilarity(res1, res2);
//            double nameScore = computeStringSimilarity(p1.getName().toString(), p2.getName().toString());
//            resourceScore = 0.5 * pathScore + 0.5 * nameScore;
            resourceScore = computeStringSimilarity(p1.getNormalizedName().toString(), p2.getNormalizedName().toString());
        } else {
            // 如果是array，加上考虑其reference的类型即可
            if (p1.getType() == ParameterType.ARRAY) {
                //比较Reference是否一致
                Parameter ref1 = ((ArrayParameter) p1).getReferenceElement();
                Parameter ref2 = ((ArrayParameter) p2).getReferenceElement();
                //ref的类型一致
                if (ref1.getType().toString().equals(ref2.getType().toString())) {
                    //如果数组里面不是Object
                    if (ref1.getType() != ParameterType.OBJECT) {
                        resourceScore = computeStringSimilarity(ref1.getNormalizedName().toString(), ref2.getNormalizedName().toString());
                    } else {
                        //如果是数组类型的Object，比较其Properties
                        List<Parameter> ob1 = ((ObjectParameter) ref1).getProperties();
                        List<Parameter> ob2 = ((ObjectParameter) ref2).getProperties();
                        double structureScore = calculateObjectSimilarity(ob1, ob2);
                        // 如果重合度太低（比如小于 70%），说明内部结构完全不同，大概率不是同一种资源
                        if (structureScore < 0.7) {
                            resourceScore = 0.0;
                        } else {
                            // 结构很像，说明是同一个资源
                            resourceScore = structureScore;
                        }
                    }
                } else {
                    //ref类型不一致，说明资源不一致
                    return 0.0;
                }
            } else {
                //考虑是否是Object的属性类型
                if (p1.getTableName() != null && p2.getTableName() != null) {
                    //都是Object的属性，先判断其是否属于同一个object
                    if (p1.getParent().equals(p2.getParent())) {
                        //同一个object，且name相同，则认为是同一个资源
                        if (p1.getTableName().equals(p2.getTableName())) {
                            resourceScore = 1.0;
                        } else {
                            resourceScore = computeStringSimilarity(p1.getNormalizedName().toString(), p2.getNormalizedName().toString());
                        }
                    } else {
                        resourceScore = computeStringSimilarity(p1.getTableName(), p2.getTableName());
                    }
                } else if (p1.getTableName() != null || p2.getTableName() != null) {
                    //如果一个是object子属性，一个不是，则直接赋值为0
                    currentScore = 0.0;
                } else {
                    //都没有object信息，则考虑name的相似度
                    resourceScore = computeStringSimilarity(p1.getNormalizedName().toString(), p2.getNormalizedName().toString());
                }
            }

        }
        currentScore += resourceScore * WEIGHT_RESOURCE;

        return Math.min(currentScore, 1.0);
    }

    private static double calculateSchemaScore(Parameter p1, Parameter p2) {
        if (p1 == null || p2 == null) {
            return 0.0;
        }
        double matches = 1;
        double checks = 1;
        double noPath = 0;
        // 1. 检查 Format (ParameterTypeFormat)
        // 逻辑：MISSING 被视为"无约束"，不参与比对。
        // 只有当两者都不是 MISSING 时，才进行严格比对。
        boolean f1Valid = (p1.getFormat() != null && p1.getFormat() != ParameterTypeFormat.MISSING);
        boolean f2Valid = (p2.getFormat() != null && p2.getFormat() != ParameterTypeFormat.MISSING);
        if (f1Valid || f2Valid) {
            checks++;
            // 如果两个都有定义且相等，或者其中一个 MISSING (视作宽容匹配? 不，通常 Fuzzing 需要严格)。
            // 这里采用严格策略：如果一方定义了 UUID，另一方是 MISSING，视为不匹配。
            if (f1Valid && f2Valid && p1.getFormat() == p2.getFormat()) {
                matches++;
            }
        }
        // 2. 检查 Style (ParameterStyle)
        // 逻辑：Style 都有默认值 (FORM)，所以总是进行比对。
        // 序列化方式不同 (如 form vs simple) 会导致后端解析失败，是关键差异。
        if (p1.getStyle() != null || p2.getStyle() != null) {
            checks++;
            if (p1.getStyle() == p2.getStyle()) {
                matches++;
            }
            if ((p1.getLocation() == ParameterLocation.PATH && p2.getLocation() == ParameterLocation.QUERY) ||
                    (p2.getLocation() == ParameterLocation.PATH && p1.getLocation() == ParameterLocation.QUERY) ||
                    (p1.getLocation() == ParameterLocation.PATH && p2.getLocation() == ParameterLocation.REQUEST_BODY) ||
                    (p2.getLocation() == ParameterLocation.PATH && p1.getLocation() == ParameterLocation.REQUEST_BODY)) {
                //path和其他参数的style不一样，但是实际上是同一个参数
                matches++;
                noPath++;
            }
        }
        // 3. 检查 EnumValues (Set<Object>)
        // 逻辑：非空才比对。如果一个有枚举，一个没枚举，视为冲突。
        boolean hasEnum1 = (p1.getEnumValues() != null && !p1.getEnumValues().isEmpty());
        boolean hasEnum2 = (p2.getEnumValues() != null && !p2.getEnumValues().isEmpty());
        if (hasEnum1 || hasEnum2) {
            checks++; // 只要有一方有枚举，就开启检查
            // 只有当双方都有枚举，且枚举集合内容完全一致时，才算匹配
            if (hasEnum1 && hasEnum2) {
                if (p1.getEnumValues().equals(p2.getEnumValues())) {
                    matches++;
                }
            }
            // 如果 hasEnum1 != hasEnum2，matches 不加分，相当于扣分
        }
        // 4. 检查 DefaultValue (Object)
        // 逻辑：默认值暗示了业务逻辑的初始状态。
        if (p1.getDefaultValue() != null || p2.getDefaultValue() != null) {
            checks++;
            // 使用 Objects.equals 处理可能的 null 和对象内容比较
            if (Objects.equals(p1.getDefaultValue(), p2.getDefaultValue())) {
                matches++;
            }
        }
        // 5. 检查 Examples (Set<Object>)
        // 逻辑：Examples 通常是辅助文档，权重可以低一点，或者作为辅助验证。
        // 这里采用宽松策略：如果两个集合有交集 (Intersection)，认为相似度较高；
        // 但为了保持算法简单（不依赖外部库计算交集），这里暂用全等判断，或者你也可以改为“只要都不为空就加分”。
        // 下面采用严格全等判断：
        boolean hasEx1 = (p1.getExamples() != null && !p1.getExamples().isEmpty());
        boolean hasEx2 = (p2.getExamples() != null && !p2.getExamples().isEmpty());
        if (hasEx1 || hasEx2) {
            // 只有当两者都有示例时才计入检查，因为示例经常被省略
            // 如果一方有示例一方没有，不应该视为冲突（不 checks++）
            if (hasEx1 && hasEx2) {
                checks++;
                // 比较示例集合是否相同
                if (p1.getExamples().equals(p2.getExamples())) {
                    matches++;
                }
            }
        }
        // 如果 checks 为 0 (例如都是 MISSING format, 都是默认 Style, 没枚举, 没默认值)
        // 说明这俩都是最普通的参数，返回0.5，要求其他格式完全一致才可以
        if (checks == 1) {
            return 1.0;
        }

        return matches / (checks + noPath);
    }

    /**
     * 计算两个对象结构的相似度 (Jaccard Index)
     * @param props1 对象 A 的属性列表
     * @param props2 对象 B 的属性列表
     * @return 0.0 ~ 1.0 (1.0 表示结构完全一致)
     */
    private static double calculateObjectSimilarity(List<Parameter> props1, List<Parameter> props2) {
        // 防御性检查
        if (props1 == null || props1.isEmpty()) {
            return (props2 == null || props2.isEmpty()) ? 1.0 : 0.0;
        }
        if (props2 == null || props2.isEmpty()) {
            return 0.0;
        }

        // 1. 提取特征签名 (Signature Extraction)
        // 我们不仅比较名字，最好连类型一起比较，防止同名不同义
        Set<String> set1 = toSignatureSet(props1);
        Set<String> set2 = toSignatureSet(props2);

        // 2. 计算交集 (Intersection) -> 两个都有的字段
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2); // 保留 set1 中也存在于 set2 的元素

        // 3. 计算并集 (Union) -> 所有的字段去重
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        // 4. Jaccard 公式: 交集大小 / 并集大小
        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    /**
     * 将参数列表转换为特征签名集合
     * 签名格式: "name:type" (例如 "id:integer", "username:string")
     */
    private static Set<String> toSignatureSet(List<Parameter> params) {
        return params.stream()
                .map(p -> {
                    // 必须做归一化处理，防止 UserId 和 user_id 被当成两个
                    String cleanName = normalize(p.getName().toString());
                    String type = p.getType().toString().toLowerCase();
                    return cleanName + ":" + type;
                })
                .collect(Collectors.toSet());
    }

    /**
     * 核心：提取参数所属的“资源”
     * 针对 /pages/{id} -> 提取出 "pages"
     * 针对 /comments/{id} -> 提取出 "comments"
     */
    public static String extractPathResourceContext(String path, Parameter param) {
        String[] segments = path.split("/");
        String paramName = param.getName().toString(); // 获取参数名字符串
        if (param.getLocation() == ParameterLocation.PATH) {
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];

                // 匹配 {id} 或 :id
                // 注意：这里要做去空格处理，防止 swagger 定义里有奇怪的空格
                boolean isTarget = segment.trim().equals("{" + paramName + "}")
                        || segment.trim().equals(":" + paramName);

                if (isTarget) {
                    // 找到了参数占位符，返回它的前一个段
                    if (i > 0) {
                        String resourceCandidate = segments[i - 1];
                        // 如果前一段是空的 (例如 // 双斜杠)，或者也是参数，这里可以做进一步防御
                        return resourceCandidate.isEmpty() ? "root" : resourceCandidate;
                    } else {
                        // 如果参数在第一段 (e.g. /{dbName}/tables)，归属为 root
                        return "root";
                    }
                }
            }
        } else {
            // 从后往前遍历
            for (int i = segments.length - 1; i >= 0; i--) {
                String segment = segments[i].trim();
                //跳过变量占位符 (包含 { } 或以 : 开头)
                if (segment.contains("{") || segment.contains("}") || segment.startsWith(":")) {
                    continue;
                }

                if (segment.isEmpty()) {
                    continue;
                }
                return segment;
            }
        }

        return "root";
    }

    public static double computeStringSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        String n1 = normalize(s1);
        String n2 = normalize(s2);
        if (n1.equals(n2)) {
            return 1.0;
        }
        // 3. 计算编辑距离 (Levenshtein Distance)
        return calculateLevenshteinSimilarity(n1, n2);
    }

    // --- 字符串处理工具 ---

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("_", "").replace("-", "").toLowerCase();
    }

    private static double calculateLevenshteinSimilarity(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i-1][j]+1, dp[i][j-1]+1), dp[i-1][j-1]+cost);
            }
        }
        int maxLen = Math.max(s1.length(), s2.length());
        return 1.0 - ((double) dp[s1.length()][s2.length()] / maxLen);
    }
}
