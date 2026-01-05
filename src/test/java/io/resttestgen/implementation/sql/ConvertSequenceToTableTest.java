package io.resttestgen.implementation.sql;

import io.resttestgen.boot.ApiUnderTest;
import io.resttestgen.boot.Starter;
import io.resttestgen.core.Environment;
import io.resttestgen.core.openapi.CannotParseOpenApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class ConvertSequenceToTableTest {
    private static Environment environment;
    @BeforeAll
    public static void setUp() throws CannotParseOpenApiException, IOException {
        environment = Starter.initEnvironment(ApiUnderTest.loadApiFromFile("gitlab"));
    }

    @Test
    public void testComputeTableColumns() {
        ConvertSequenceToTable convertSequenceToTable = new ConvertSequenceToTable();
        System.out.println("Computed Table Columns:" + convertSequenceToTable.getTableColumns().size());
        convertSequenceToTable.getTableColumns().forEach((key, value) -> {
            System.out.println("Column Name: " + key + ", Data Type: " + value);
        });
        convertSequenceToTable.createTableByColumns();
    }
}
