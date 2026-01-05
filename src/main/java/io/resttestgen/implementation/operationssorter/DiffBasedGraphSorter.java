package io.resttestgen.implementation.operationssorter;

import com.google.common.collect.Sets;
import io.resttestgen.core.Environment;
import io.resttestgen.core.datatype.HttpMethod;
import io.resttestgen.core.datatype.NormalizedParameterName;
import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.helper.ExtendedRandom;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.core.operationdependencygraph.DependencyEdge;
import io.resttestgen.core.operationdependencygraph.OperationDependencyGraph;
import io.resttestgen.core.operationdependencygraph.OperationNode;
import io.resttestgen.core.testing.operationsorter.StaticOperationsSorter;
import io.resttestgen.core.testing.parametervalueprovider.ParameterValueProviderCachedFactory;
import io.resttestgen.implementation.parametervalueprovider.ParameterValueProviderType;
import io.resttestgen.implementation.parametervalueprovider.single.ResponseDictionaryParameterValueProvider;

import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
/*
  该sorter用来从ODG中选择一条测试序列  --- 使用dfs从Post节点开始，优先选择出度多的节点，有多少个Post节点就有多少个对应的Delete节点，用一个map来进行post和delete的映射
 */
public class DiffBasedGraphSorter extends StaticOperationsSorter {

    private int maximumAttempts = 10;

    private final OperationDependencyGraph graph = Environment.getInstance().getOperationDependencyGraph();
    private final ExtendedRandom random = Environment.getInstance().getRandom();
    //用来记录post和delete的映射关系
    private final Map<Operation, Operation> postToDeleteMap = new HashMap<>();
    //dfs最长长度
    private final int maxDepth = 20;
    //用来记录所有的post节点
    private final List<OperationNode> postNodes = new ArrayList<>();
    private final List<OperationNode> deleteNodes = new ArrayList<>();


    public DiffBasedGraphSorter() {
        computePostToDeleteMap();
        computeAllPost();
        computeAllDelete();
        getTestSequence();
    }

    public Map<Operation, Operation> getPostToDeleteMap() {
        return postToDeleteMap;
    }

    public List<OperationNode> getAllPostNodes() {
        return postNodes;
    }

    public Set<OperationNode> getAllNodes() {
        return graph.getGraph().vertexSet();
    }

    public List<OperationNode> getAllDeleteNodes() {
        return deleteNodes;
    }

    @Override
    public Operation removeFirst() {
        Operation removedOperation = super.removeFirst();
        graph.increaseOperationTestingAttempts(removedOperation);
        return removedOperation;
    }
    /**,
     * 获得所有的post节点
     */
    public void computeAllPost() {
        postNodes.addAll(graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.POST)
                .collect(Collectors.toList()));
        Collections.shuffle(postNodes);
    }
    /**.
     * 获得所有的delete节点
     */
    public void computeAllDelete() {
        deleteNodes.addAll(graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.DELETE)
                .collect(Collectors.toList()));
        Collections.shuffle(deleteNodes);
    }
    /**.
     * 用来生成post和delete的映射关系
     */
    public void computePostToDeleteMap() {
        List<OperationNode> postNodes = graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.POST)
                .collect(Collectors.toList());
        List<OperationNode> deleteNodes = graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.DELETE)
                .collect(Collectors.toList());
//        Collections.shuffle(postNodes);
//        Collections.shuffle(deleteNodes);
        int size = Math.min(postNodes.size(), deleteNodes.size());
        for (OperationNode postNode : postNodes) {
            Operation postOperation = postNode.getOperation();
            String postEndpoint = postOperation.getEndpoint();
            for (OperationNode deleteNode : deleteNodes) {
                String deleteEndpoint = deleteNode.getOperation().getEndpoint();
                Operation deleteOperation = deleteNode.getOperation();
                if (postEndpoint.equals(deleteEndpoint) || deleteEndpoint.contains(postEndpoint) || postEndpoint.contains(deleteEndpoint)) {
                    postToDeleteMap.put(postOperation, deleteOperation);
                }
            }
        }
    }

    //用dfs得到测试序列
    public void getTestSequence() {
        emptyCurrentQueue();
        //从所有的post节点中随机选择一个作为起始节点
        Random random = new Random();
//        OperationNode startNode = postNodes.get(random.nextInt(postNodes.size()));
        //选择一个入度最小，出度最大的post节点作为起始节点
        OperationNode startNode = graph.findBestDfsStartNode(postNodes);
        if (startNode == null) {
            throw new IllegalStateException("No POST operation found in the Operation Dependency Graph.");
        }

        Set<OperationNode> visited = new HashSet<>();
        Deque<OperationNode> stack = new ArrayDeque<>();
        stack.push(startNode);
        while (!stack.isEmpty()) {
            OperationNode currentNode = stack.pop();
            if (!visited.contains(currentNode)) {
                visited.add(currentNode);
                queue.addLast(currentNode.getOperation());
                if (queue.size() >= maxDepth) {
                    break;
                }
                //启发式访问出度更高的节点优先
                List<OperationNode> neighbors = graph.getGraph().outgoingEdgesOf(currentNode).stream()
                        .map(edge -> graph.getGraph().getEdgeTarget(edge))
                        .filter(node -> !visited.contains(node))
                        .sorted(Comparator.comparingInt(node -> graph.getGraph().outgoingEdgesOf(node).size()))
                        .collect(Collectors.toList());
                for (OperationNode neighbor : neighbors) {
                    stack.push(neighbor);
                }
            }
        }
        //将测试序列里面添加几个delete操作
        List<Operation> operationsToAdd = new ArrayList<>();
        int cnt = 3;
        for (Operation operation : queue) {
            if (operation.getMethod() == HttpMethod.POST && postToDeleteMap.containsKey(operation) && cnt > 0) {
                operationsToAdd.add(postToDeleteMap.get(operation));
                cnt--;
            }
        }
        while (cnt > 0) {
            OperationNode deleteNode = deleteNodes.get(random.nextInt(deleteNodes.size()));
            if (deleteNode != null && !operationsToAdd.contains(deleteNode.getOperation())) {
                operationsToAdd.add(deleteNode.getOperation());
                cnt--;
            }
        }
        queue.addAll(operationsToAdd);
    }

    /**
     * Removes all elements in the queue
     */
    private void emptyCurrentQueue() {
        while (!queue.isEmpty()) {
            queue.removeFirst();
        }
    }

    /**
     * Compute the number of unsatisfied parameters by subtracting the set of satisfied parameters from the set of total
     * parameters in the operation. Moreover, removes the parameters that are not in the graph, but have a value stored
     * in the global dictionary.
     * @param node the node in the operation dependency graph.
     * @return the number of unsatisfied parameters.
     */
    private int computeNumberOfUnsatisfiedParameters(OperationNode node) {
        Set<NormalizedParameterName> satisfiedParameters = graph.getGraph().outgoingEdgesOf(node).stream()
                .filter(DependencyEdge::isSatisfied)
                .map(DependencyEdge::getNormalizedName)
                .collect(Collectors.toSet());
        Set<NormalizedParameterName> allParametersInOperation = node.getOperation().getReferenceLeaves().stream()
                .map(LeafParameter::getNormalizedName)
                .collect(Collectors.toSet());
        Set<NormalizedParameterName> unsatisfiedParameters = Sets.difference(allParametersInOperation, satisfiedParameters);

        ResponseDictionaryParameterValueProvider provider = (ResponseDictionaryParameterValueProvider) ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.RESPONSE_DICTIONARY);
        provider.setSameNormalizedNameValueSourceClass();

        Set<NormalizedParameterName> parametersInDictionary = new HashSet<>();
        for (NormalizedParameterName unsatisfiedParameter : unsatisfiedParameters) {
            List<LeafParameter> foundParameters = node.getOperation().searchReferenceRequestParametersByNormalizedName(unsatisfiedParameter)
                    .stream().filter(p -> p instanceof LeafParameter).map(p -> (LeafParameter) p).collect(Collectors.toList());
            if (foundParameters.size() > 0) {
                LeafParameter parameter = foundParameters.get(0);
                if (provider.countAvailableValuesFor(parameter) > 0) {
                    parametersInDictionary.add(unsatisfiedParameter);
                }
            }
        }
        return Sets.difference(unsatisfiedParameters, parametersInDictionary).size();
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("The number of maximum attempts must be greater or equal to 1.");
        }
        this.maximumAttempts = maximumAttempts;
    }
}
