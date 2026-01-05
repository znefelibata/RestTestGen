package io.resttestgen.implementation.sql.model;

import io.resttestgen.core.datatype.parameter.Parameter;
import io.resttestgen.core.datatype.parameter.attributes.ParameterLocation;
import io.resttestgen.core.datatype.parameter.attributes.ParameterType;
import io.resttestgen.implementation.sql.checker.MysqlWhereConditionChecker;
import io.resttestgen.implementation.sql.matcher.ApiParameterMatcher;

import java.util.*;

public class ParameterCluster {
    private final List<Parameter> parameters = new ArrayList<>();
    //聚类里面总的参数类型 --- 在数据库创建的时候只需要创建一列
    protected ParameterType clusterType = ParameterType.MISSING;
    //创建到数据库的实际名称
    private String canonicalName;

    // 无参构造
    public ParameterCluster() {}

    // 带参构造
    public ParameterCluster(Parameter initialParam) {
        this.parameters.add(initialParam);
        this.clusterType = initialParam.getType() == ParameterType.UNKNOWN || initialParam.getType() == ParameterType.MISSING
                ? ParameterType.STRING : initialParam.getType();
    }

    public void addParameter(Parameter p) {
        this.parameters.add(p);
        this.clusterType = p.getType() == ParameterType.UNKNOWN || p.getType() == ParameterType.MISSING
                ? ParameterType.STRING : p.getType();
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    // 获取代表元素 (通常取第一个)
    public Parameter getRepresentative() {
        return parameters.isEmpty() ? null : parameters.get(0);
    }

    public ParameterType getClusterType() {
        return clusterType;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    //计算canonicalName
    public void computeCanonicalName() {
        if (canonicalName == null) {
            // 计算 canonicalName
            if (clusterType == ParameterType.ARRAY) {
                //是数组
                canonicalName = parameters.get(0).getTableName().toLowerCase();
            } else {
                //是pathId
                if (isPathParameter()) {
                    String res = ApiParameterMatcher.extractPathResourceContext(parameters.get(0).getOperation().getEndpoint(), parameters.get(0));
                    canonicalName = res + "_" + parameters.get(0).getName().toString().toLowerCase();
                } else if (isObjectValue()){
                    //是Object对象
                    canonicalName = parameters.get(0).getTableName().toLowerCase();
                } else {
                    canonicalName = parameters.get(0).getNormalizedName().toString().toLowerCase();
                }
            }
        }
    }

    /**
     * 计算规范化名称 (数据库列名)
     * @param usedNames 全局已使用的列名集合 (用于解决重名问题)
     */
    public void computeCanonicalName(Set<String> usedNames, Set<String> mysqlKeywords, Map<String, String> columnToWhereParameterMap) {
        if (this.canonicalName != null) {
            return;
        }

        String baseName = "";
        Parameter p = parameters.get(0); // 取第一个参数作为代表
        for (Parameter parameter : parameters) {
            if ("id".equals(parameter.getName().toString())) {
                p = parameter;
                break;
            }
        }
        String res = ApiParameterMatcher.extractPathResourceContext(p.getOperation().getEndpoint(), p);
        // --- 1. 原始名称提取逻辑 (保持你原有的业务逻辑) ---
        if (clusterType == ParameterType.ARRAY) {
            // 针对 entities[source_type]_arr 这种情况
            baseName = p.getTableName();
        } else {
            if (isPathParameter()) {
                // Path 参数：增加资源前缀，如 users_id
                baseName = res + "_" + p.getName().toString();
            } else if (isObjectValue()) {
                // Object 对象
                baseName = p.getTableName();
            } else {
                // 普通参数
                baseName = p.getName().toString();
            }
        }
        // 将所有非 字母、数字 的字符替换为下划线，比如 "entities[source_type]_arr" -> "entities_source_type__arr"
        String sanitized = sanitizeColumnName(baseName);

        //解决重名 (解决 name 重复问题)
        this.canonicalName = resolveCollision(sanitized, usedNames, mysqlKeywords, p);

        //判断是否是特殊的where字段
        if (MysqlWhereConditionChecker.isControlParameter(p.getName().toString())) {
            columnToWhereParameterMap.put(this.canonicalName, p.getName().toString());
        }

        // 将最终确定的名字加入全局集合
        usedNames.add(this.canonicalName);
    }

    /**
     * 清洗字符串，使其符合数据库列名规范
     */
    private String sanitizeColumnName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown_column";
        }

        // 1. 转小写
        String s = raw.toLowerCase();

        // 2. 正则替换：将所有 非字母(a-z)、非数字(0-9) 替换为 下划线(_)
        // [^a-z0-9] 表示匹配任何不是字母和数字的字符
        s = s.replaceAll("[^a-z0-9]", "_");

        // 3. 优化：去除多余的连续下划线 (比如 __ -> _)
        s = s.replaceAll("_+", "_");

        // 4. 去除首尾的下划线
        if (s.startsWith("_")) s = s.substring(1);
        if (s.endsWith("_")) s = s.substring(0, s.length() - 1);

        // 5. 兜底：如果清洗完变空了 (比如原名是 "[]")
        if (s.isEmpty()) {
            return "param_" + UUID.randomUUID();
        }
        return s;
    }
    /**
     * 解决命名冲突
     * 策略：name -> name_2 -> name_3
     */
    private String resolveCollision(String candidate, Set<String> usedNames, Set<String> mysqlKeywords, Parameter p) {
        String currentName = candidate;

        // 只要满足任意一个条件，就说明冲突，需要改名
        if (!usedNames.contains(currentName) && !mysqlKeywords.contains(currentName.toUpperCase())) {
            return currentName;
        }

        // 如果当前名字不仅是简单的 param，而是加上了 context，可能就既不重复也不是关键字了
        // 例如: "order" (关键字) -> "create_order" (合法)
        // 注意：如果已经是 Path 参数(上面逻辑加过前缀了)，这里跳过，避免加两次
        if (!isPathParameter()) {
            String resContext = ApiParameterMatcher.extractPathResourceContext(p.getOperation().getEndpoint(), p);
            // 清洗 context + 原名
            String contextName = sanitizeColumnName(resContext + "_" + candidate);
            // 再次检查这个新名字是否冲突
            if (!usedNames.contains(contextName) && !mysqlKeywords.contains(contextName.toUpperCase())) {
                return contextName;
            }
            // 如果加了上下文还冲突 (比如 contextName 也是个关键字，虽然概率很低)，则进入策略 B
            currentName = contextName;
        }

        // name -> name_2 -> name_3
        int counter = 2;
        String tempName = currentName + "_" + counter;

        // 循环直到找到一个既不在 usedNames 也不在 mysqlKeywords 的名字
        while (usedNames.contains(tempName) || mysqlKeywords.contains(tempName)) {
            counter++;
            tempName = currentName + "_" + counter;
        }

        return tempName;
    }

    public boolean isPathId() {
        for (Parameter p : parameters) {
            String paramName = p.getName().toString().toLowerCase();
            if (("id".equals(paramName) || paramName.endsWith("id") || paramName.startsWith("id")) && p.getLocation() == ParameterLocation.PATH) {
                return true;
            }
        }
        return false;
    }

    public boolean isPathParameter() {
        for (Parameter p : parameters) {
            if (p.getLocation() == ParameterLocation.PATH) {
                return true;
            }
        }
        return false;
    }
    public boolean isObjectValue() {
        for (Parameter p : parameters) {
            String tableName = p.getTableName();
            if (tableName == null || tableName.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
