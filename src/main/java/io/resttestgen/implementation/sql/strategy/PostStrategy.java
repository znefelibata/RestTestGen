package io.resttestgen.implementation.sql.strategy;

import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.datatype.parameter.structured.ArrayParameter;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.SqlInteraction;
import io.resttestgen.implementation.sql.util.ParameterToJsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class PostStrategy extends RestStrategy {
    private static final Logger log = LoggerFactory.getLogger(PostStrategy.class);

    @Override
    public SqlInteraction operationToSQL(Operation op, ConvertSequenceToTable convertSequenceToTable) {
        //根据Operation对象，生成对应的Insert语句
        //获取所有的叶子参数
        Map<String, Object> columnValues = new LinkedHashMap<>();
        Collection<LeafParameter> leaves = op.getLeaves();
        Collection<ArrayParameter> arrays = op.getArrays();

        StringBuilder insertTableSQL = new StringBuilder("INSERT INTO "  + convertSequenceToTable.getTableName () + " (");
        for (LeafParameter leaf : leaves) {
            if (!(leaf.getParent() instanceof ArrayParameter)) {
                String columnName = convertSequenceToTable.getColumnNameByParameter(leaf);
                if (columnName != null) {
                    insertTableSQL.append(columnName).append(", ");
                    columnValues.put(columnName, leaf.getValue());
                }
            }
        }
        //Array转换成JSON类型 object是直接拆开了
        for (ArrayParameter array : arrays) {
            if (array.getElements().isEmpty()) {
                continue;
            }
            String columnName = convertSequenceToTable.getColumnNameByParameter(array);
            if (columnName != null) {
                insertTableSQL.append(columnName).append(", ");
                List<Object> arrayValues = ParameterToJsonUtil.getArrayValues(array);
                columnValues.put(columnName, ParameterToJsonUtil.arrayToJsonString(arrayValues));
            }
        }
        if (columnValues.isEmpty()) {
            log.error("Skipping INSERT generation: No parameters found for operation {}", op.getEndpoint());
            throw new RuntimeException("Dangerous Operation: INSERT without columnValues is not allowed for " + op.getEndpoint());
        }

        insertTableSQL.setLength(insertTableSQL.length() - 2);
        insertTableSQL.append(") VALUES (");
        for (int i = 0; i < columnValues.size(); i++) {
            insertTableSQL.append("?, ");
        }
        insertTableSQL.setLength(insertTableSQL.length() - 2);
        insertTableSQL.append(");");
        SqlInteraction sqlInteraction = new SqlInteraction();
        try {
            databaseHelper.executePost(insertTableSQL.toString(), columnValues, sqlInteraction);
        } catch (RuntimeException e) {
            log.error("Failed to generate SQL interaction for {} operation {}: {}",op.getMethod(), op.getEndpoint(), e.getMessage());
        }
        return sqlInteraction;
    }
}
