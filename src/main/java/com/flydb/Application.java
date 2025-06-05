package com.flydb;

import com.flydb.core.DatabaseConfig;
import com.flydb.core.FlyDB;
import com.flydb.core.Migration;
import com.flydb.core.MigrationScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
/**
 * FlyDB数据库迁移工具的Web应用入口类
 * 提供RESTful API接口，用于执行数据库迁移操作，主要功能包括：
 * - 初始化数据库版本控制
 * - 查询当前数据库版本
 * - 执行数据库迁移
 * - 执行版本回退
 */
@SpringBootApplication
@RestController
@RequestMapping("/api/flydb")
public class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    @Autowired
    private DatabaseConfig databaseConfig;

    @Value("${flydb.scripts.path:db/migration}")
    private String scriptsPath;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        
        // 使用 getActiveConnectionConfig() 方法获取连接配置
        DatabaseConfig.DatabaseConnection connection = databaseConfig.getActiveConnectionConfig();
        
        if (connection == null) {
            // 如果没有配置，使用默认配置
            dataSource.setUrl("jdbc:mysql://localhost:3306/flydb_dev?useSSL=false&serverTimezone=UTC");
            dataSource.setUsername("root");
            dataSource.setPassword("root");
            System.out.println("警告：使用默认数据库配置，因为无法获取活动连接配置");
        } else {
            dataSource.setUrl(connection.getUrl());
            dataSource.setUsername(connection.getUsername());
            dataSource.setPassword(connection.getPassword());
        }
        
        return dataSource;
    }

    /**
     * 初始化数据库版本控制
     * 创建版本控制表，用于记录数据库变更历史
     * 
     * @return ResponseEntity<String> 初始化结果
     */
    @PostMapping("/init")
    public ResponseEntity<String> initDatabase() {
        if (!databaseConfig.isEnableConcurrent() || databaseConfig.getConnections().size() <= 1) {
            try (Connection conn = dataSource().getConnection()) {
                FlyDB flyDB = new FlyDB(conn);
                flyDB.init();
                return ResponseEntity.ok("数据库初始化成功");
            } catch (SQLException e) {
                logger.error("数据库初始化失败", e);
                return ResponseEntity.internalServerError().body("数据库初始化失败: " + e.getMessage());
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(databaseConfig.getConnections().size());
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (Map.Entry<String, DatabaseConfig.DatabaseConnection> entry : databaseConfig.getConnections().entrySet()) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                DatabaseConfig.DatabaseConnection conn = entry.getValue();
                DriverManagerDataSource dataSource = new DriverManagerDataSource();
                dataSource.setUrl(conn.getUrl());
                dataSource.setUsername(conn.getUsername());
                dataSource.setPassword(conn.getPassword());
                try (Connection connection = dataSource.getConnection()) {
                    FlyDB flyDB = new FlyDB(connection);
                    flyDB.init();
                    return String.format("数据库 %s 初始化成功", entry.getKey());
                } catch (SQLException e) {
                    logger.error(String.format("数据库 %s 初始化失败", entry.getKey()), e);
                    return String.format("数据库 %s 初始化失败: %s", entry.getKey(), e.getMessage());
                }
            }, executor);
            futures.add(future);
        }

        List<String> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        executor.shutdown();
        return ResponseEntity.ok(String.join("\n", results));
    }

    /**
     * 获取当前数据库版本
     * 
     * @return ResponseEntity<String> 当前版本号
     */
    @GetMapping("/version")
    public ResponseEntity<String> getCurrentVersion() {
        try (Connection conn = dataSource().getConnection()) {
            FlyDB flyDB = new FlyDB(conn);
            String version = flyDB.getCurrentVersion();
            return ResponseEntity.ok(version);
        } catch (SQLException e) {
            logger.error("获取版本失败", e);
            return ResponseEntity.internalServerError().body("获取版本失败: " + e.getMessage());
        }
    }

    /**
     * 执行数据库迁移
     * 
     * @param targetVersion 目标版本号，如果为null则迁移到最新版本
     * @return ResponseEntity<String> 迁移结果
     */
    @PostMapping("/migrate")
    public ResponseEntity<String> migrate(@RequestParam(required = false) String targetVersion) {
        if (!databaseConfig.isEnableConcurrent() || databaseConfig.getConnections().size() <= 1) {
            try (Connection conn = dataSource().getConnection()) {
                return executeMigration(conn, targetVersion);
            } catch (SQLException e) {
                logger.error("迁移失败", e);
                return ResponseEntity.internalServerError().body("迁移失败: " + e.getMessage());
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(databaseConfig.getConnections().size());
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (Map.Entry<String, DatabaseConfig.DatabaseConnection> entry : databaseConfig.getConnections().entrySet()) {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                DatabaseConfig.DatabaseConnection conn = entry.getValue();
                DriverManagerDataSource dataSource = new DriverManagerDataSource();
                dataSource.setUrl(conn.getUrl());
                dataSource.setUsername(conn.getUsername());
                dataSource.setPassword(conn.getPassword());
                try (Connection connection = dataSource.getConnection()) {
                    String result = executeMigration(connection, targetVersion).getBody();
                    return String.format("数据库 %s: %s", entry.getKey(), result);
                } catch (SQLException e) {
                    logger.error(String.format("数据库 %s 迁移失败", entry.getKey()), e);
                    return String.format("数据库 %s 迁移失败: %s", entry.getKey(), e.getMessage());
                }
            }, executor);
            futures.add(future);
        }

        List<String> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        executor.shutdown();
        return ResponseEntity.ok(String.join("\n", results));
    }

    private ResponseEntity<String> executeMigration(Connection conn, String targetVersion) {
        try {
            FlyDB flyDB = new FlyDB(conn);
            Migration migration = new Migration(conn, scriptsPath);
            List<MigrationScript> scripts = migration.loadMigrationScripts();

            String currentVersion = flyDB.getCurrentVersion();
            int currentVersionNum = Integer.parseInt(currentVersion);
            int targetVersionNum = targetVersion != null ? Integer.parseInt(targetVersion) : Integer.MAX_VALUE;

            for (MigrationScript script : scripts) {
                int scriptVersion = Integer.parseInt(script.getVersion());
                if (scriptVersion > currentVersionNum && scriptVersion <= targetVersionNum) {
                    migration.executeMigration(script);
                }
            }

            return ResponseEntity.ok("迁移完成，当前版本: " + flyDB.getCurrentVersion());
        } catch (SQLException | IOException e) {
            logger.error("迁移执行失败", e);
            return ResponseEntity.internalServerError().body("迁移失败: " + e.getMessage());
        }
    }

    /**
     * 执行数据库版本回退
     * 
     * @param version 需要回退到的目标版本号
     * @return ResponseEntity<String> 回退结果
     */
    @PostMapping("/rollback/{version}")
    public ResponseEntity<String> rollback(@PathVariable String version) {
        try (Connection conn = dataSource().getConnection()) {
            FlyDB flyDB = new FlyDB(conn);
            Migration migration = new Migration(conn, scriptsPath);
            String currentVersion = flyDB.getCurrentVersion();
            
            if (Integer.parseInt(version) >= Integer.parseInt(currentVersion)) {
                return ResponseEntity.badRequest().body("回退版本号必须小于当前版本");
            }
            
            String rollbackScript = migration.loadRollbackScript(currentVersion);
            if (rollbackScript == null) {
                return ResponseEntity.badRequest().body("未找到版本 " + currentVersion + " 的回退脚本");
            }
            
            MigrationScript script = new MigrationScript(version, "R" + currentVersion + "__rollback.sql", rollbackScript);
            migration.executeMigration(script);
            
            return ResponseEntity.ok("回退完成，当前版本: " + flyDB.getCurrentVersion());
        } catch (SQLException | IOException e) {
            return ResponseEntity.internalServerError().body("回退失败: " + e.getMessage());
        }
    }
}