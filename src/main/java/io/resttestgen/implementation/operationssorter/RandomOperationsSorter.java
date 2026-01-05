package io.resttestgen.implementation.operationssorter;

import io.resttestgen.core.Environment;
import io.resttestgen.core.testing.operationsorter.StaticOperationsSorter;

import java.util.Collections;
import java.util.LinkedList;

public class RandomOperationsSorter extends StaticOperationsSorter {

    public RandomOperationsSorter() {
        queue = new LinkedList<>(Environment.getInstance().getOpenAPI().getOperations());
        //shuffle 函数将操作随机打乱
        Collections.shuffle(queue);
    }
}
