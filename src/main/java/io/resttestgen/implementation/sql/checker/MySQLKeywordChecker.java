package io.resttestgen.implementation.sql.checker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

public class MySQLKeywordChecker {
    // 1. 使用 Set 存储关键字，用于高效的 "contains" 检查
    // 我们将其设为私有和静态，使其在类加载时初始化一次
    private static final Set<String> KEYWORDS = new HashSet<>();

    // 2. 关键字文件的文件名
    private static final String KEYWORD_FILE = "mysql_keywords.txt";
    
    static {
        // 使用 try-with-resources 确保流被正确关闭
        try (
                InputStream is = MySQLKeywordChecker.class.getClassLoader().getResourceAsStream(KEYWORD_FILE);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // 忽略空行和注释行 (以 # 开头)
                if (!line.isEmpty() && !line.startsWith("#")) {
                    KEYWORDS.add(line.toUpperCase());
                }
            }

            System.out.println("MySQL 关键字检查器：成功加载 " + KEYWORDS.size() + " 个关键字。");

        } catch (IOException e) {
            // 在静态初始化块中，如果发生严重错误，通常抛出 RuntimeException 来使程序失败
            throw new RuntimeException("初始化 MySQL 关键字检查器失败", e);
        }
    }

    /**
     * 检查一个给定的名称是否为 MySQL 的保留关键字,如果是则转换成非关键字
     *
     * @param name 要检查的名称 (例如：列名或表名)
     * @return 如果是关键字，返回 true；否则返回 false
     */
    public static String convertKeywordToApi(String name) {
        if (KEYWORDS.contains(name.toUpperCase())) {
            return name + "_api";
        } else {
            return name;
        }
    }

    public static void addKeyword(String keyword) {
        KEYWORDS.add(keyword.toUpperCase());
    }

    public static Set<String> getKeywords() {
        return KEYWORDS;
    }
}
