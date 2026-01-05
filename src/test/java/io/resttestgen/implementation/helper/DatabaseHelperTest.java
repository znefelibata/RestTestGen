package io.resttestgen.implementation.helper;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DatabaseHelperTest {

    @Test
    public void testGetInstance(){
        DatabaseHelper dbHelper1 = DatabaseHelper.getInstance();
        DatabaseHelper dbHelper2 = DatabaseHelper.getInstance();
        assert dbHelper1 == dbHelper2; // Both references should point to the same instance
        System.out.println("dbHelper1 == dbHelper2: " + (dbHelper1 == dbHelper2));
        HikariDataSource dataSource = dbHelper1.getDataSource();
        try(Connection connection = dataSource.getConnection();
//            PreparedStatement ps = connection.prepareStatement("CREATE TABLE IF NOT EXISTS test (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50), tags JSON)");
//            PreparedStatement ps = connection.prepareStatement("INSERT INTO test (name, tags) \n" +
//                    "VALUES ('Laptop', '[\"electronics\", \"computer\", \"work\"]');");
            PreparedStatement ps = connection.prepareStatement("SELECT * FROM test\n" +
                    "WHERE JSON_CONTAINS(tags, '\"work\"');");
            ) {
            ps.execute();
            ResultSet rs = ps.getResultSet();
            while (rs != null && rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                String tags = rs.getString("tags");
                System.out.println("ID: " + id + ", Name: " + name + ", Tags: " + tags);
            }
        }catch (SQLException e) {
            throw new RuntimeException(e);
        }
        System.out.println("dataSource: " + dataSource);
        assert dataSource != null; // DataSource should not be null
        DatabaseHelper.closePool();

        System.out.println("DataSource is closed: " + dataSource.isClosed());

    }
}
