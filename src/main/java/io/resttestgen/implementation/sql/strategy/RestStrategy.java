package io.resttestgen.implementation.sql.strategy;

import io.resttestgen.core.openapi.Operation;
import io.resttestgen.implementation.helper.DatabaseHelper;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.SqlInteraction;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

public abstract class RestStrategy {
    protected final DatabaseHelper databaseHelper = DatabaseHelper.getInstance();
    //表的列名集合,用来处理ResultSet中的数据
    protected final HashSet<String> tableColumns = new HashSet<>();
    //将一个Operation对象转换为对应的SQL语句
    public abstract SqlInteraction operationToSQL(Operation op, ConvertSequenceToTable convertSequenceToTable);

}
