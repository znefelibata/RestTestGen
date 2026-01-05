package io.resttestgen.core.testing.operationsorter;

public abstract class DynamicOperationsSorter extends OperationsSorter {

    @Override
    public boolean isEmpty() {
        refresh();
        return super.isEmpty();
    }

    /**
     * This method is implemented in subclasses, and it is intended to refresh the queue in the operations sorter
     * 此方法在子类中实现，用于刷新操作排序器中的队列
     */
    public abstract void refresh();
}
