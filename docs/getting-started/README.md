# 数据库上手指南

每个数据库页面只记录该 JDBC 家族的连接入口、驱动边界、权限和已知限制。Flydb 不捆绑任何厂商驱动；请按厂商许可和内部制品库规定获取驱动。

首次接入信创、行业或新型 JDBC 数据库，请先阅读[JDBC 数据库快速接入](jdbc-integration.md)，再按下表选择具体家族页面。该指南明确了驱动 JAR、`--driver`、`--database-type`、Spring Boot 和自定义 `DatabaseType` SPI 的最短路径。

需要同时管理多个数据库家族、多套测试与生产环境时，阅读[多数据库多环境自动化](multi-environment.md)；把 Flydb 接入 GitHub Actions 或 Jenkins 时，阅读[CI 集成指南](ci-integration.md)。

| 数据库 | 方言标识 | 当前证据 |
|---|---|---|
| [MySQL 8](mysql.md) | `mysql` | 本地 MySQL 8 容器契约、CLI 与 Spring Boot 端到端 |
| [PostgreSQL](postgresql.md) | `postgresql` | PostgreSQL 家族契约；CI 矩阵入口 |
| [Oracle](oracle.md) | `oracle` | 原生 Oracle 家族方言；授权真实实例已通过 validate、clean、migrate 端到端验证 |
| [TiDB](tidb.md) | `tidb` | MySQL 家族兼容契约；真实 TiDB 待显式环境 |
| [OceanBase-MySQL](oceanbase-mysql.md) | `oceanbase` | MySQL 家族兼容契约与探测代理；真实租户待显式环境 |
| [openGauss](opengauss.md) | `opengauss` | PostgreSQL 家族兼容契约；真实实例待显式环境 |
| [KingbaseES](kingbasees.md) | `kingbasees` | PostgreSQL 家族兼容契约；真实实例需授权环境 |
| [达梦 DM8](dm8.md) | `dm` | 方言/元数据契约；真实实例需授权环境 |
| [OceanBase-Oracle](oceanbase-oracle.md) | `oceanbase` | Oracle 家族方言；授权真实 Oracle 租户已通过端到端验证 |

所有页面都假设已完成 [CLI 五分钟上手](../../README.md#五分钟上手)。
