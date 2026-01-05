package io.resttestgen.implementation.sql;

import io.resttestgen.core.Environment;
import io.resttestgen.core.datatype.NormalizedParameterName;
import io.resttestgen.core.datatype.parameter.attributes.ParameterType;
import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.datatype.parameter.structured.ArrayParameter;
import io.resttestgen.core.datatype.parameter.structured.ObjectParameter;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.core.datatype.parameter.Parameter;
import io.resttestgen.core.operationdependencygraph.OperationDependencyGraph;
import io.resttestgen.core.operationdependencygraph.OperationNode;
import io.resttestgen.implementation.helper.DatabaseHelper;
import io.resttestgen.implementation.sql.checker.MySQLKeywordChecker;
import io.resttestgen.implementation.sql.model.ParameterCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

//一步，人工主动判断，第二步，是机器自动自动根据名称判断
//人工判断，名字相同，则是同一字段，名字不同，但是端点路径有包含关系，也可以认为是同一字段
// 将操作序列转换为表格形式的类
// Object，Array直接用JSON存,因为数据库支持JSON存储和查询
public class ConvertSequenceToTable {
    private static final Logger log = LoggerFactory.getLogger(ConvertSequenceToTable.class);
    private final OperationDependencyGraph graph = Environment.getInstance().getOperationDependencyGraph();
    private final Set<String> mySQLKeywords = MySQLKeywordChecker.getKeywords();
    //收集所有的API参数,已经对Object进行扁平化处理，但是Array还是存JSON格式
    protected final List<Parameter> tableParameters = new ArrayList<>();
    //聚类之后的参数，每一个聚类都是数据库的一列
    protected final List<ParameterCluster> clusters = new ArrayList<>();
    //存的是API的名字和类型
    protected final Map<String, String> tableColumns = new HashMap();
    //存的是实际的数据库的列名和类型
    protected final Map<String, String> tableColumnTypes = new HashMap();
    //存的是参数名和实际数据库列名的映射
    protected final Map<String, String> parameterToColumnMap = new HashMap();
    //列名和特殊的where查询的参数映射
    protected final Map<String, String> columnToWhereParameterMap = new HashMap<>();
    private final DatabaseHelper databaseHelper = DatabaseHelper.getInstance();
    private final String tableName = "api_test_data_" + Environment.getInstance().getApiUnderTest().getName();

    /**
     * 目前建的表，如果是Array，则存为JSON格式，如果是Object，则拆开存
     */
    public ConvertSequenceToTable() {
        computeTableColumns();
    }
    public ConvertSequenceToTable(List<Operation> queue) {
        computeTableColumnsByQueue(queue);
    }

    public Map<String, String> getTableColumns() {
        return tableColumns;
    }

    public Map<String, String> getTableColumnTypes() {
        return tableColumnTypes;
    }

    public String getTableName() {
        return tableName;
    }

    public List<Parameter> getTableParameters() {
        return tableParameters;
    }

    public void computeTableColumnsByQueue(List<Operation> queue) {
        for (Operation op : queue) {
            //获取header所有参数名称
            getTableNameForBasicParameters(op.getHeaderParameters());
            //获取query所有参数名称
            getTableNameForBasicParameters(op.getQueryParameters());
            //获取path所有参数名称
            getTableNameForBasicParameters(op.getPathParameters());
            //获取cookie所有参数名称
            getTableNameForBasicParameters(op.getCookieParameters());
            //获取body所有参数名称
            //body肯定是一个Object,直接每个字段拆开就行，Array直接存
            computeTableNameFromObjectParameter((ObjectParameter) op.getRequestBody());
        }
    }

    public void computeTableColumns() {
        Set<OperationNode> set = graph.getGraph().vertexSet();
        for (OperationNode node : set) {
            Operation op = node.getOperation();
            List<Parameter> parameters = new ArrayList<>();
            parameters.addAll(op.getHeaderParameters());
            parameters.addAll(op.getQueryParameters());
            parameters.addAll(op.getPathParameters());
            parameters.addAll(op.getCookieParameters());
            if (op.getRequestBody() != null) {
                if (op.getRequestBody() instanceof ObjectParameter) {
                    parameters.addAll(((ObjectParameter) op.getRequestBody()).getProperties());
                }
            }
//             这里所有的object参数都被拆开了， 但是Array没有拆开，直接存JSON
            for (Parameter parameter : parameters) {
                if (parameter.getType() == ParameterType.ARRAY) {
                    flatten(parameter);
                } else if (parameter.getType() == ParameterType.OBJECT) {
                    tableParameters.addAll(flatten(parameter));
                    continue;
                }
                tableParameters.add(parameter);
            }
        }
        // 进行聚类去重
        SmileClusteringEngine engine = new SmileClusteringEngine();
        //多少个聚类就是多少个数据库的列名
        clusters.addAll(engine.performClustering(tableParameters));
        //计算了每个聚类的canonicalName
        Set<String> existingNames = new HashSet<>();
        clusters.forEach(cluter -> cluter.computeCanonicalName(existingNames, mySQLKeywords, columnToWhereParameterMap));
        //实现API名和数据库列名的映射
        for (ParameterCluster cluster : clusters) {
            for (Parameter p : cluster.getParameters()) {
                String uniqueKey = computeSchemaMapName(p);
                parameterToColumnMap.put(uniqueKey, cluster.getCanonicalName());
            }
        }
    }

    public void computeTableNameFromObjectParameter(ObjectParameter objectParameter) {
        if (objectParameter == null) {
            return;
        } else {
            objectParameter.getProperties().forEach(parameter -> {
                if (parameter.getType() == ParameterType.OBJECT) {
                    computeTableNameFromObjectParameter((ObjectParameter) parameter);

                }
                getTableNameForBasicParameters(Set.of(parameter));
            });
        }
    }

    public void getTableNameForBasicParameters(Set<Parameter> parameters) {
        for (Parameter parameter : parameters) {
            NormalizedParameterName paramName = parameter.getNormalizedName();
            if ("id".equals(parameter.getName().toString())) {
                tableColumns.put(paramName.toString().toLowerCase(), parameter.getType() == ParameterType.UNKNOWN ? "string" : parameter.getType().toString());
            } else {
                tableColumns.put(parameter.getName().toString(), parameter.getType() == ParameterType.UNKNOWN ? "string" : parameter.getType().toString());
            }
        }
    }
    //格式还需要进行转化
    public void createTableByColumns() {
        StringBuilder createTableSQL = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " (\n");
        createTableSQL.append("    id INT PRIMARY KEY AUTO_INCREMENT,\n");
        for (ParameterCluster pc : clusters) {
            String columnName = pc.getCanonicalName();
            String columnType = pc.getClusterType().toString();
            String sqlType;
            switch (columnType) {
                case "integer":
                    sqlType = "INT";
                    break;
                case "number":
                    sqlType = "DECIMAL(10,4)";
                    break;
                case "boolean":
                    sqlType = "TINYINT(1) DEFAULT 0";
                    break;
                case "string":
                    sqlType = "VARCHAR(100)";
                    break;
                case "object":
                case "array":
                    sqlType = "JSON";
                    break;
                default:
                    sqlType = "VARCHAR(100)";
                    break;
            }
            createTableSQL.append("    ").append(columnName).append(" ").append(sqlType).append(",\n");
            tableColumnTypes.put(columnName, sqlType);
        }
        // Remove the last comma and newline
        createTableSQL.setLength(createTableSQL.length() - 2);
        createTableSQL.append("\n) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;");
        try {
            databaseHelper.executeCreate(createTableSQL.toString());
        } catch (RuntimeException e) {
            log.error("Failed to create table for API: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void dropTable() {
        String dropTableSQL = "DROP TABLE IF EXISTS " + tableName;
        try {
            databaseHelper.executeCreate(dropTableSQL);
        } catch (RuntimeException e) {
            log.error("Failed to drop table for API: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * [入口] 将任意深度的参数拆解为扁平列表
     * @param rootParam 根参数 (通常是 requestBody)
     */
    public static List<Parameter> flatten(Parameter rootParam) {
        List<Parameter> leaves = new ArrayList<>();
        // 初始路径为空字符串
        recurseExtract(rootParam, leaves, "");
        return leaves;
    }

    /**
     * [递归核心]
     * @param currentParam 当前正在处理的参数
     * @param collector 结果收集器
     * @param parentPath 父级路径 (例如 "user.profile")
     */
    private static void recurseExtract(Parameter currentParam, List<Parameter> collector,
                                       String parentPath) {
        if (currentParam == null) {
            return;
        }

        // 2. 计算当前参数的 JsonPath
        // 获取参数名 (如果是在 Array 里，名字可能是 null 或 empty，这没关系，路径主要靠 parentPath)
        String myName = (currentParam.getName() != null) ? currentParam.getName().toString() : "";

        // 生成完整路径
        // 如果 parentPath 为空，说明是根节点；否则拼接 "."
        String currentPath;
        if (parentPath.isEmpty()) {
            currentPath = myName;
        } else if (currentParam.getParent().getType() == ParameterType.ARRAY) {
            // 如果父级是数组，不要加 "."，而是直接依附于数组标记 "[]"
            currentPath = parentPath + "_arr";;
        } else {
            // 如果我是数组里的元素，不要加 "."，而是直接依附于数组标记 "[]"
            // (这一步已经在父级递归调用时处理了，见下方 Array 逻辑)
            currentPath = parentPath + "_" + myName;
        }

        // Case A: 这是一个 Object (容器)
        if (currentParam instanceof ObjectParameter) {
            ObjectParameter objParam = (ObjectParameter) currentParam;
            // 遍历所有属性
            if (objParam.getProperties() != null) {
                for (Parameter prop : objParam.getProperties()) {
                    // 递归：路径变为 "currentPath.propName"
                    recurseExtract(prop, collector, currentPath);
                }
            }
        }

        // Case B: 这是一个 Array (容器)
        else if (currentParam instanceof ArrayParameter) {
            ArrayParameter arrParam = (ArrayParameter) currentParam;
            arrParam.setTableName(currentPath + "_arr");
            // 【关键点】提取数组的元素原型 (Reference Element)
            Parameter elementParam = arrParam.getReferenceElement();

            if (elementParam != null) {
                recurseExtract(elementParam, collector, currentPath);
            }
        }
//         Case C: 这是一个叶子节点 (String, Int, Boolean...)
        else if (currentParam instanceof LeafParameter) {
            // 只有叶子节点才加入结果列表
            currentParam.setTableName(currentPath);
            collector.add(currentParam);
        }
    }
    //根据参数名获取对应的规范名
    public String computeSchemaMapName(Parameter p) {
        String method = p.getOperation().getMethod().toString();
        String path = p.getOperation().getEndpoint();
        String paramName = p.getName().toString();
        return method + " " + path + " # " + paramName;
    }

    //根据参数获取对应的数据库列名
    public String getColumnNameByParameter(Parameter parameter) {
        String schemaMapName =computeSchemaMapName(parameter);
        return parameterToColumnMap.get(schemaMapName);
    }

    public String getColumnNameByName(String name, Operation operation) {
        String method = operation.getMethod().toString();
        String path = operation.getEndpoint();
        String schemaMapName = method + " " + path + " # " + name;
        return parameterToColumnMap.get(schemaMapName);
    }
    //根据数据库列名获取对应的数据库列类型
    public String getColumnTypeForParameterName(String columnName) {
        return tableColumnTypes.get(columnName);
    }


}
