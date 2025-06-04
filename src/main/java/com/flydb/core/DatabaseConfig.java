package com.flydb.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据库配置管理类
 * 用于管理多个数据库连接信息，支持在配置文件中定义多个数据库连接
 * 并提供数据库连接的获取和切换功能
 * 
 * 配置加载优先级：
 * 1. 优先使用 application.properties 中的配置
 * 2. 根据 active-connection 参数选择对应的连接配置
 * 3. 只有当 db-connections.yml 中的并发执行参数为 true 时，才使用循环库的原逻辑
 */
//@Configuration
@Component
@ConfigurationProperties(prefix = "flydb.databases")
public class DatabaseConfig {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConfig.class);
    
    private Map<String, DatabaseConnection> connections = new HashMap<>();
    private String activeConnection = "development";
    private String activeProfile;
    private boolean enableConcurrent = false;
    private Map<String, Boolean> yamlConcurrentSettings = new HashMap<>();

    public DatabaseConfig() {
        // 默认使用开发环境配置
        this.activeProfile = System.getProperty("spring.profiles.active", "development");
    }
    
    /**
     * 初始化方法，在所有属性注入完成后执行
     * 确保 application.properties 中的配置已经被 Spring 注入到 connections 集合中
     */
    @PostConstruct
    public void init() {
        
        // 尝试从YAML文件加载配置 (db-connections.yml)
        loadConnectionsFromYaml();
        
        // 记录当前使用的配置信息
        logConfigurationInfo();
    }

    private void logConfigurationInfo() {
        logger.info("当前激活的数据库连接: {}", activeConnection);
        logger.info("当前激活的环境: {}", activeProfile);
        logger.info("是否启用并发执行: {}", enableConcurrent);
        
        DatabaseConnection conn = getActiveConnectionConfig();
        if (conn != null) {
            // 安全起见，不记录密码
            logger.info("使用的数据库URL: {}", conn.getUrl());
            logger.info("使用的数据库用户名: {}", conn.getUsername());
        } else {
            logger.warn("未找到有效的数据库连接配置");
        }
    }

    private void loadConnectionsFromYaml() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("db-connections.yml")) {
            if (input != null) {
                Yaml yaml = new Yaml();
                Map<String, Object> yamlData = yaml.load(input);
                
                if (yamlData != null) {
                    // 处理全局配置
                    if (yamlData.containsKey("global")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> globalConfig = (Map<String, Object>) yamlData.get("global");
                        if (globalConfig.containsKey("concurrent_execution")) {
                            Boolean concurrentExecution = (Boolean) globalConfig.get("concurrent_execution");
                            // 只有当YAML中明确设置为true时，才启用并发执行
                            if (Boolean.TRUE.equals(concurrentExecution)) {
                                this.enableConcurrent = true;
                                logger.info("从YAML加载全局并发执行设置: {}", this.enableConcurrent);
                            }
                        }
                        // 移除全局配置，以便后续处理
                        yamlData.remove("global");
                    }
                    
                    // 转换YAML数据为连接对象，并合并到已有的 connections 中
                        // db-connections.yml 中的配置会覆盖 application.yml 中的同名配置
                    for (Map.Entry<String, Object> entry : yamlData.entrySet()) {
                        String profile = entry.getKey();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> connData = (Map<String, Object>) entry.getValue();
                        
                        // 保存并发设置
                        if (connData.containsKey("concurrent")) {
                            yamlConcurrentSettings.put(profile, (Boolean) connData.get("concurrent"));
                        }
                        
                        // 从YAML加载或更新配置
                        DatabaseConnection conn = connections.getOrDefault(profile, new DatabaseConnection());
                        if (connData.containsKey("url")) conn.setUrl((String) connData.get("url"));
                        if (connData.containsKey("username")) conn.setUsername((String) connData.get("username"));
                        if (connData.containsKey("password")) conn.setPassword((String) connData.get("password"));
                        
                        connections.put(profile, conn);
                        logger.info("从 db-connections.yml 加载或更新数据库连接配置: {}", profile);
                    }
                }
            }
        } catch (Exception e) {
            // 如果YAML加载失败，记录错误但不中断应用
            logger.error("无法加载YAML数据库配置文件: {}", e.getMessage(), e);
        }
    }

    /**
     * 判断是否启用并发执行
     * 只有当全局配置和当前活动连接的配置都为true时，才返回true
     * 
     * @return 是否启用并发执行
     */
    public boolean isEnableConcurrent() {
        // 如果全局配置为false，直接返回false
        if (!enableConcurrent) {
            return false;
        }
        
        // 检查当前活动连接的并发设置
        String activeConnName = getActiveConnection();
        if (yamlConcurrentSettings.containsKey(activeConnName)) {
            Boolean connConcurrent = yamlConcurrentSettings.get(activeConnName);
            return Boolean.TRUE.equals(connConcurrent);
        }
        
        // 如果没有找到当前连接的并发设置，检查当前环境的并发设置
        if (yamlConcurrentSettings.containsKey(activeProfile)) {
            Boolean profileConcurrent = yamlConcurrentSettings.get(activeProfile);
            return Boolean.TRUE.equals(profileConcurrent);
        }
        
        // 默认返回false
        return false;
    }

    public void setEnableConcurrent(boolean enableConcurrent) {
        this.enableConcurrent = enableConcurrent;
    }

    public Map<String, DatabaseConnection> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, DatabaseConnection> connections) {
        this.connections = connections;
    }

    public String getActiveConnection() {
        return activeConnection;
    }

    public void setActiveConnection(String activeConnection) {
        this.activeConnection = activeConnection;
    }

    public String getActiveProfile() {
        return activeProfile;
    }

    public void setActiveProfile(String activeProfile) {
        this.activeProfile = activeProfile;
    }

    /**
     * 获取当前活动的数据库连接配置
     * 优先使用 application.properties 中通过 activeConnection 指定的连接
     * 如果找不到，则尝试使用当前激活的环境配置
     * 
     * @return 当前活动的数据库连接配置
     */
    public DatabaseConnection getActiveConnectionConfig() {
        // 优先使用 activeConnection 指定的连接
        DatabaseConnection conn = connections.get(activeConnection);
        if (conn != null) {
            logger.debug("使用 activeConnection[{}] 指定的数据库连接", activeConnection);
            return conn;
        }
        
        // 如果找不到，尝试使用 activeProfile
        conn = connections.get(activeProfile);
        if (conn != null) {
            logger.debug("使用 activeProfile[{}] 指定的数据库连接", activeProfile);
            return conn;
        }
        
        // 如果仍然找不到，记录警告
        logger.warn("未找到有效的数据库连接配置，activeConnection={}, activeProfile={}", 
                   activeConnection, activeProfile);
        return null;
    }

    /**
     * 获取当前活动的数据库连接
     * 为了兼容性保留此方法
     */
    public DatabaseConnection getCurrentConnection() {
        return getActiveConnectionConfig();
    }

    public static class DatabaseConnection {
        private String url;
        private String username;
        private String password;

        public DatabaseConnection() {}

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}