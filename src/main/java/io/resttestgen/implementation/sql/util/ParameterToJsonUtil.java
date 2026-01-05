package io.resttestgen.implementation.sql.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.resttestgen.core.datatype.parameter.Parameter;
import io.resttestgen.core.datatype.parameter.structured.ArrayParameter;
import io.resttestgen.core.datatype.parameter.structured.ObjectParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class ParameterToJsonUtil {

    private static final Logger log = LoggerFactory.getLogger(ParameterToJsonUtil.class);

    // ObjectMapper 是线程安全的，建议全局复用一个实例以提升性能
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 私有构造函数，防止工具类被实例化
    private ParameterToJsonUtil() {}

    /**
     * 将任意对象（包括 List, Array, Map）转换为 JSON 字符串
     *
     * @param object 需要转换的对象 (通常是 leaf.getValue())
     * @return JSON 格式的字符串，如果输入为 null 则返回 null
     */
    public static String toJsonString(Object object) {
        // 1. 如果本来就是 null，直接返回 null，交给 JDBC 处理 setNull
        if (object == null) {
            return null;
        }

        // 2. 如果本来就是 String，尝试判断它是不是已经是 JSON 了
        // (可选策略：如果你确定 value 可能是原生字符串，也可能是 JSON 串，可以根据业务决定是否直接返回)
        if (object instanceof String) {
            return (String) object;
        }

        try {
            // 3. 核心转换逻辑：将 List/Array/Map 转为 JSON String
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert object to JSON: {}", object, e);
            // 4. 兜底策略：如果转换失败，返回对象的 toString()，保证程序不崩
            return object.toString();
        }
    }

    /**
     * (可选) 专门针对集合类型的转换，带有类型检查
     */
    public static String arrayToJsonString(Object potentiallyArray) {
        if (potentiallyArray == null) {
            return null;
        }

        // 检查是否是 数组、List 或 Map
        boolean isArrayType = potentiallyArray instanceof Collection<?>
                || potentiallyArray.getClass().isArray()
                || potentiallyArray instanceof Map<?, ?>;

        if (isArrayType) {
            return toJsonString(potentiallyArray);
        } else {
            // 如果不是数组类型，按照普通字符串处理
            return String.valueOf(potentiallyArray);
        }
    }

    /**
     * 获取 ArrayParameter 中所有元素的值
     * 返回结果可能是 List<String>, List<Integer>, List<List<String>> 或者 List<Map<String, Object>> (针对对象数组)
     */
    public static List<Object> getArrayValues(ArrayParameter arrayParameter) {
        List<Object> resultList = new ArrayList<>();
        //如果是Object类型的数组
        for (Parameter element : arrayParameter.getElements()) {
            if (element instanceof ObjectParameter) {
                //Object 类型 -> 转成 Map, JSON 序列化时会变成 {...}
                resultList.add(getObjectValues((ObjectParameter) element));

            } else if (element instanceof ArrayParameter) {
                //嵌套数组递归调用自己
                resultList.add(getArrayValues((ArrayParameter) element));

            } else {
                //如果是基本类型 (LeafParameter) -> 直接取值
                resultList.add(element.getValue());
            }
        }
        return resultList;
    }

    /**
     * 将 ObjectParameter 转换为 Map
     * 只有把 Object 转成 Map，JSON 库才能把它序列化成 {"key": "val"}
     */
    public static Map<String, Object> getObjectValues(ObjectParameter objectParameter) {
        // 使用 LinkedHashMap 保持字段顺序
        Map<String, Object> map = new LinkedHashMap<>();

        for (Parameter child : objectParameter.getProperties()) {
            String fieldName = child.getName().toString();

            if (child instanceof ObjectParameter) {
                // 递归：字段也是对象
                map.put(fieldName, getObjectValues((ObjectParameter) child));
            } else if (child instanceof ArrayParameter) {
                // 递归：字段是数组
                map.put(fieldName, getArrayValues((ArrayParameter) child));
            } else {
                // 基本字段
                map.put(fieldName, child.getValue());
            }
        }
        return map;
    }
}