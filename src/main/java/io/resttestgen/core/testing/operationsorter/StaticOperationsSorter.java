package io.resttestgen.core.testing.operationsorter;

import io.resttestgen.core.openapi.Operation;

import java.util.LinkedList;

/**
 * Class providing a static order for the operations to test. The order is decided a priori (no changes during the test
 * execution), so the complete queue of operations can be returned anytime.
 * 该类为待测试操作提供静态顺序。该顺序是预先确定的（测试执行期间不会更改），因此可以随时返回完整的操作队列。
 */
public abstract class StaticOperationsSorter extends OperationsSorter {

    public LinkedList<Operation> getOperationsQueue() {
        return queue;
    }
}
