package io.resttestgen.implementation.sql.factory;

import io.resttestgen.core.datatype.HttpMethod;
import io.resttestgen.implementation.sql.strategy.*;

import java.util.HashMap;
import java.util.Map;

public class RestStrategyFactory {
    // 注册表（相当于一个策略容器）
    private static final Map<HttpMethod, RestStrategy> strategyMap = new HashMap<>();

    // 静态块注册策略
    static {
        strategyMap.put(HttpMethod.GET, new GetStrategy());
        strategyMap.put(HttpMethod.POST, new PostStrategy());
        strategyMap.put(HttpMethod.PUT, new PutStrategy());
        strategyMap.put(HttpMethod.DELETE, new DeleteStrategy());
        strategyMap.put(HttpMethod.PATCH, new PutStrategy());
    }

    // 工厂方法：根据枚举返回策略
    public static RestStrategy getStrategy(HttpMethod method) {
        RestStrategy strategy = strategyMap.get(method);
        if (strategy == null) {
            throw new IllegalArgumentException("No strategy found for " + method);
        }
        return strategy;
    }
}
