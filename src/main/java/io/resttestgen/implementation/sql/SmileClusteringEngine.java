package io.resttestgen.implementation.sql;

import io.resttestgen.core.datatype.parameter.Parameter;
import io.resttestgen.core.datatype.parameter.attributes.ParameterLocation;
import io.resttestgen.implementation.sql.matcher.ApiParameterMatcher;
import io.resttestgen.implementation.sql.model.ParameterCluster;
import io.resttestgen.implementation.sql.model.ParameterInstance;
import smile.clustering.DBSCAN;
import smile.clustering.HierarchicalClustering;
import smile.clustering.linkage.CompleteLinkage;
import smile.clustering.linkage.Linkage;
import smile.clustering.linkage.UPGMALinkage;
import smile.math.distance.Distance;
import java.util.ArrayList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SmileClusteringEngine {

    // --- 配置区域 ---
    // 相似度阈值 0.80 -> 意味着距离阈值 (Epsilon) 为 0.20
    // 距离 = 1.0 - 相似度
    private static final double EPSILON = 0.2;

    // 最小点数 (MinPts)：设为 1。
    // 含义：只要有一个点，它自己也能成为一个簇。
    // 原因：在 API 测试中，独一无二的参数（孤立点）是完全正常的，不能被当做噪音丢弃。
    private static final int MIN_PTS = 1;
    // 存储所有的聚类结果 (用于最终输出)
    private final List<ParameterCluster> allClusters = new ArrayList<>();

    // 切割阈值 (Cut Height)
    // 对应之前的 Epsilon。如果相似度 > 0.8，则距离 < 0.2。
    // 在树状图中，我们在 0.2 的高度横切一刀。
    private static final double CUT_HEIGHT = 0.20;

    // 【优化核心】预筛选分桶 Map
    // Key: "LOCATION:TYPE" (例如 "PATH:INTEGER", "QUERY:STRING")
    // Value: 该类型下的所有簇
//    private final Map<String, List<ParameterCluster>> bucketMap = new HashMap<>();

    /**
     * [核心入口] 执行聚类
     * @param parameters 已经扁平化处理过的所有参数实例
     */
    public List<ParameterCluster> performClustering(List<Parameter> parameters) {
        List<ParameterCluster> finalClusters = new ArrayList<>();

        // 1. 分桶 (Bucketing)
        // 直接使用 Parameter 对象生成 key
        Map<String, List<Parameter>> buckets = parameters.stream()
                .collect(Collectors.groupingBy(this::generateBucketKey));

        // 2. 遍历桶
        buckets.forEach((key, bucketParams) -> {
            if (bucketParams.isEmpty()) {
                return;
            }

            if (bucketParams.size() == 1) {
                finalClusters.add(new ParameterCluster(bucketParams.get(0)));
                return;
            }

            // 3. 桶内聚类
//            finalClusters.addAll(runDbscanInBucket(bucketParams));
            finalClusters.addAll(runHacInBucket(bucketParams));
        });

        return finalClusters;
    }
//    private List<ParameterCluster> runDbscanInBucket(List<Parameter> bucketParams) {
//        // Smile 要求数组输入
//        Parameter[] dataArray = bucketParams.toArray(new Parameter[0]);
//
//        // 4. 定义距离函数
//        Distance<Parameter> distanceMetric = (p1, p2) -> {
//            // 直接从 Parameter 对象中获取上下文信息
//            double similarity = ApiParameterMatcher.compare(
//                    p1, p1.getOperation().getEndpoint(), // <--- 关键变化
//                    p2, p2.getOperation().getEndpoint()  // <--- 关键变化
//            );
//            return Math.max(0.0, 1.0 - similarity);
//        };
//
//        // 5. 运行 DBSCAN
//        DBSCAN<Parameter> dbscan = DBSCAN.fit(dataArray, distanceMetric, MIN_PTS, EPSILON);
//
//        // 6. 解析结果
//        int[] labels = dbscan.y;
//        Map<Integer, ParameterCluster> tempGroup = new HashMap<>();
//
//        for (int i = 0; i < labels.length; i++) {
//            int clusterId = labels[i];
//            // 将 dataArray[i] 加入对应的临时组
//            // 注意：ParameterCluster 需要修改为接收 Parameter
//            tempGroup.computeIfAbsent(clusterId, k -> new ParameterCluster())
//                    .addParameter(dataArray[i]);
//        }
//
//        return new ArrayList<>(tempGroup.values());
//    }

    private List<ParameterCluster> runHacInBucket(List<Parameter> bucketParams) {
        int n = bucketParams.size();
        Parameter[] params = bucketParams.toArray(new Parameter[0]);

        // --- A. 计算距离矩阵 (Distance Matrix) ---
        // HAC 需要预先知道所有点之间的距离。这是一个 O(N^2) 的操作。
        // matrix[i][j] 表示第 i 个参数和第 j 个参数的距离
        double[][] matrix = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < i; j++) { // 只算下三角，利用对称性
                double distance = calculateDistance(params[i], params[j]);
                matrix[i][j] = distance;
                matrix[j][i] = distance; // 对称赋值
            }
            matrix[i][i] = 0.0; // 自己和自己的距离是 0
        }

        // --- B. 选择连接策略 (Linkage) ---
        // 【关键】使用 CompleteLinkage (全连接)
        // 含义：合并两个簇时，距离取两个簇中"最远"成员的距离。
        // 效果：这会迫使簇保持紧凑，只有当新加入的点和簇里"所有人"都足够近时，才能加入。
        // 完美解决 sticky -> [unknown] -> language 的桥接问题。
//        Linkage linkage = new CompleteLinkage(matrix);
        Linkage linkage = new UPGMALinkage(matrix);

        // --- C. 训练模型 (Fit) ---
        HierarchicalClustering hac = HierarchicalClustering.fit(linkage);
        double[] heights = hac.getHeight();
        double rootHeight = heights[heights.length - 1]; // 树的最高点

        int[] labels;

        // 2. 判断切割高度
        if (CUT_HEIGHT >= rootHeight - 0.000001) {
            // 情况 A: 刀举得太高了 (桶内参数都很相似，最大差异也没超过阈值)
            // 没必要切了，所有人都是一家人 -> 归为同一个簇 (Cluster 0)
            labels = new int[bucketParams.size()];
            // Java 数组默认初始就是 0，所以这里其实不用循环赋值，为了清晰写出来
            // for(int i=0; i<labels.length; i++) labels[i] = 0;
        } else {
            // 情况 B: 树很高，需要在 CUT_HEIGHT 处切一刀
            labels = hac.partition(CUT_HEIGHT);
        }

        // --- E. 结果组装 ---
        Map<Integer, ParameterCluster> tempGroup = new HashMap<>();
        for (int i = 0; i < labels.length; i++) {
            int clusterId = labels[i];
            tempGroup.computeIfAbsent(clusterId, k -> new ParameterCluster())
                    .addParameter(params[i]);
        }

        return new ArrayList<>(tempGroup.values());
    }

    /**
     * 封装距离计算逻辑
     */
    private double calculateDistance(Parameter p1, Parameter p2) {
        // 调用之前的 Matcher
        double similarity = ApiParameterMatcher.compare(
                p1, p1.getOperation().getEndpoint(),
                p2, p2.getOperation().getEndpoint()
        );
        // 相似度转距离
        return Math.max(0.0, 1.0 - similarity);
    }

    /**
     * 将一个新的参数放入引擎，它会自动归类到现有的 Cluster，或者创建新 Cluster
     */
//    public void processParameter(String path, Parameter param) {
//
//        // 1. 【生成分桶 Key】
//        // 只有 Key 相同的参数才有可能是同一个参数，不用跟所有簇去比
//        String bucketKey = generateBucketKey(param);
//
//        // 2. 【获取候选簇】
//        // 如果 Map 里没有这个 Key，自动创建一个空列表
//        List<ParameterCluster> candidates = bucketMap.computeIfAbsent(bucketKey, k -> new ArrayList<>());
//
//        ParameterCluster matchedCluster = null;
//        double bestScore = -1.0;
//
//        // 判定阈值 (与 ApiParameterMatcher 保持一致)
//        double threshold = 0.85;
//
//        // 3. 【只遍历候选簇】
//        // 性能提升点：只比较 location 和 type 相同的簇
//        for (ParameterCluster cluster : candidates) {
//            Parameter representative = cluster.getRepresentative();
//            // 调用核心比较器
//            double score = ApiParameterMatcher.compare(
//                    param, path,
//                    representative, representative.getOperation().getEndpoint()
//            );
//            // 寻找最高分的匹配
//            if (score >= threshold && score > bestScore) {
//                bestScore = score;
//                matchedCluster = cluster;
//            }
//        }
//
//        // 4. 【决策与存储】
//        if (matchedCluster != null) {
//            // 找到了组织 -> 加入现有簇
//            matchedCluster.addParameter(param);
//        } else {
//            // 没找到 -> 自立门户
//            ParameterCluster newCluster = new ParameterCluster(param);
//
//            // 同时加入到 总列表 和 分桶列表
//            allClusters.add(newCluster);
//            candidates.add(newCluster);
//        }
//    }

    /**
     * 生成分桶 Key
     * 逻辑：Type 是硬性门槛，必须一致才能比较。
     * 格式示例: "ANY_LOC:INTEGER", "ANY_LOC:STRING", "ANY_LOC:OBJECT"
     */
    private String generateBucketKey(Parameter param) {
        String type = (param.getType() != null) ? param.getType().toString() : "UNKNOWN_TYPE";
        if (param.getLocation() == ParameterLocation.COOKIE || param.getLocation() == ParameterLocation.HEADER) {
            return param.getLocation() + ":" + param.getType();
        }
        return "ANY_LOC:" + param.getType();

    }

    /**
     * 获取最终所有的聚类结果
     */
    public List<ParameterCluster> getClusters() {
        return allClusters;
    }
}
