package io.resttestgen.implementation.oracle;

import io.resttestgen.core.datatype.HttpStatusCode;
import io.resttestgen.core.testing.Oracle;
import io.resttestgen.core.testing.TestInteraction;
import io.resttestgen.core.testing.TestResult;
import io.resttestgen.core.testing.TestSequence;
import io.resttestgen.implementation.sql.SqlInteraction;

/**
 * A differential testing oracle that compares the outcome of the REST API execution
 * with the outcome of a simulated SQL execution on a shadow database.
 */
public class SqlDiffOracle extends Oracle {

    public static final String SQL_INTERACTION_TAG = "SQL_INTERACTION";

    @Override
    public TestResult assertTestSequence(TestSequence testSequence) {
        TestResult testResult = new TestResult();

        if (!testSequence.isExecuted()) {
            return testResult.setError("One or more interaction in the sequence have not been executed.");
        }

        for (TestInteraction testInteraction : testSequence) {
            // Retrieve the SqlInteraction attached to the TestInteraction
            Object tag = testInteraction.getTag(SQL_INTERACTION_TAG);

            if (tag == null || !(tag instanceof SqlInteraction)) {
                // If no SQL interaction is present, we cannot perform differential testing for this interaction
                continue;
            }

            SqlInteraction sqlInteraction = (SqlInteraction) tag;
            HttpStatusCode statusCode = testInteraction.getResponseStatusCode();

            // 1. Compare Execution Status
            if (sqlInteraction.isSuccess()) {
                // Case: SQL Success
                if (statusCode.isSuccessful()) {
                    // SQL Success + API Success = PASS
                    testResult.setPass("Both SQL simulation and API execution succeeded.");
                } else if (statusCode.isClientError()) {
                    // SQL Success + API Client Error = POTENTIAL BUG (False Positive possible)
                    // The API rejected something the DB accepted (e.g., extra validation logic in code)
                    testResult.setFail("Difference detected: SQL simulation succeeded, but API returned Client Error (" + statusCode + ").");
                } else if (statusCode.isServerError()) {
                    // SQL Success + API Server Error = FAIL
                    testResult.setFail("Difference detected: SQL simulation succeeded, but API returned Server Error (" + statusCode + ").");
                }
            } else {
                // Case: SQL Failed (e.g., constraint violation)
                if (statusCode.isSuccessful()) {
                    // SQL Fail + API Success = FAIL (Serious Bug)
                    // The API accepted data that violates DB constraints (or the shadow DB schema is stricter)
                    testResult.setFail("Difference detected: SQL simulation failed (" + sqlInteraction.getErrorMessage() + "), but API succeeded (" + statusCode + ").");
                } else if (statusCode.isClientError()) {
                    // SQL Fail + API Client Error = PASS
                    // Both rejected the request (likely for the same reason)
                    testResult.setPass("Both SQL simulation and API execution rejected the request.");
                } else if (statusCode.isServerError()) {
                    // SQL Fail + API Server Error = FAIL (Graceful handling expected)
                    testResult.setFail("SQL simulation failed, and API crashed with Server Error (" + statusCode + "). Expected Client Error.");
                }
            }

            // TODO: Future improvement - Compare returned data (sqlInteraction.getQueryResults() vs response body)
        }

        testSequence.addTestResult(this, testResult);
        return testResult;
    }
}

