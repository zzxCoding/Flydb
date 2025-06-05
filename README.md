# FlyDB 数据库迁移工具

FlyDB 是一个简单易用的数据库版本控制和迁移工具，帮助您轻松管理数据库架构的变更。

## 项目背景

随着国产数据库的快速发展和广泛应用，企业在数据库迁移方面面临着新的挑战。现有的数据库迁移工具（如Flyway）虽然功能强大，但在以下方面存在一些局限性：

1. **数据库适配**：对国产数据库的支持有限，需要额外的适配工作
2. **部署复杂**：依赖较多，部署和维护成本较高
3. **功能冗余**：包含许多企业不常用的功能，增加了学习成本

基于以上问题，我们开发了FlyDB，旨在提供一个更轻量级、更灵活的数据库迁移解决方案。

## 为什么选择FlyDB？

相比于Flyway等传统数据库迁移工具，FlyDB具有以下优势：

1. **更广泛的数据库支持**
   - 基于JDBC的通用实现，易于扩展支持新的数据库
   - 优先支持国产数据库，如达梦、人大金仓等
   - 提供统一的API接口，降低数据库切换成本

2. **更轻量级的设计**
   - 核心功能聚焦于版本控制和迁移管理
   - 最小化依赖，降低部署难度
   - 提供简单直观的命令行工具

3. **更灵活的版本管理**
   - 支持版本回退功能，方便故障处理
   - 提供清晰的迁移历史记录
   - 支持目标版本迁移，满足不同场景需求

4. **更友好的使用体验**
   - 简单的配置方式
   - 清晰的命名规范
   - 详细的操作文档

## 功能特点

- 数据库版本控制
- 自动化数据库迁移
- 支持目标版本迁移
- 迁移历史记录
- 简单的命令行界面
- 多数据库并发执行

## 环境要求

- Java 8 或更高版本
- MySQL 数据库（支持jdbc的数据库）
- curl（用于命令行操作）

## 快速开始

### 1. 配置数据库连接

#### 单数据库配置
编辑 `src/main/resources/application.properties` 文件：

```properties

# 迁移脚本路径配置
flydb.scripts.path=db/migration

# 服务器配置
server.port=8080
```

#### 多数据库配置
编辑 `src/main/resources/db-connections.yml` 文件：

```yaml
# 全局配置
global:
  concurrent_execution: true  # 是否启用多数据库并发执行
  max_concurrent_tasks: 5     # 最大并发任务数
  timeout: 3600              # 执行超时时间（秒）

# 开发环境
development:
  url: jdbc:mysql://localhost:3306/dev_db?useSSL=false&serverTimezone=UTC
  username: dev_user
  password: dev_password
  concurrent: true           # 是否参与并发执行

# 生产环境
production:
  url: jdbc:mysql://prod-server:3306/prod_db?useSSL=false&serverTimezone=UTC
  username: prod_user
  password: prod_password
  concurrent: false          # 生产环境默认不参与并发执行
```

### 2. 创建迁移脚本

在 `db/migration` 目录下创建 SQL 迁移脚本，遵循以下命名规范：

- 迁移脚本：`V{版本号}__{描述}.sql`
- 回退脚本：`R{版本号}__{描述}.sql`

示例：

```sql
-- V1__create_users_table.sql
CREATE TABLE users (
    id INT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- R1__drop_users_table.sql
DROP TABLE IF EXISTS users;

-- V2__add_user_status.sql
ALTER TABLE users
ADD COLUMN status VARCHAR(20) DEFAULT 'active';

-- R2__remove_user_status.sql
ALTER TABLE users
DROP COLUMN status;
```

### 3. 启动服务

```bash
# 使用 Maven 构建并运行
mvn spring-boot:run
```

### 4. API接口使用

#### 初始化数据库
```bash
curl -X POST http://localhost:8080/api/flydb/init
```

#### 查看当前版本
```bash
curl http://localhost:8080/api/flydb/version
```

#### 执行迁移
```bash
# 迁移到最新版本
curl -X POST http://localhost:8080/api/flydb/migrate

# 迁移到指定版本
curl -X POST "http://localhost:8080/api/flydb/migrate?targetVersion=2"
```

#### 版本回退
```bash
# 回退到指定版本
curl -X POST http://localhost:8080/api/flydb/rollback/1
```

## 项目架构

### 核心组件

1. **FlyDB**：核心类，负责数据库版本控制和迁移管理
   - 初始化版本控制表
   - 管理数据库版本
   - 执行版本回退

2. **Migration**：迁移脚本管理类
   - 加载迁移脚本
   - 执行迁移操作
   - 记录迁移历史

3. **DatabaseConfig**：数据库配置管理类
   - 支持多数据库配置
   - 动态切换数据库连接

### 版本控制表结构

```sql
CREATE TABLE flydb_schema_history (
    version_rank INT NOT NULL,
    installed_rank INT NOT NULL,
    version VARCHAR(50) NOT NULL,
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    execution_time INT NOT NULL,
    success BOOLEAN NOT NULL,
    PRIMARY KEY (version)
);
```

## 常见问题

### 1. 迁移失败如何处理？

当迁移失败时，FlyDB会自动回滚事务，确保数据库保持一致性。您可以：
1. 检查错误日志，了解失败原因
2. 修复迁移脚本中的问题
3. 重新执行迁移操作

### 2. 如何处理冲突的版本号？

FlyDB使用版本号作为主键，确保每个版本号都是唯一的。建议：
1. 在团队中统一版本号分配规则
2. 使用有意义的版本号命名（如日期+序号）
3. 在提交前进行版本号冲突检查

### 3. 并发执行失败如何处理？

当并发执行失败时：
1. 检查各个数据库的错误日志
2. 确认是否存在资源竞争问题
3. 考虑调整并发任务数或暂时关闭并发执行
4. 修复问题后重新执行迁移

## 贡献指南

我们欢迎任何形式的贡献，包括但不限于：

1. 提交问题和建议
2. 改进文档
3. 提交代码修复
4. 添加新功能

### 开发流程

1. Fork 项目
2. 创建特性分支
3. 提交变更
4. 推送到分支
5. 创建 Pull Request

## 许可证

本项目采用 MIT 许可证，详情请参见 LICENSE 文件。