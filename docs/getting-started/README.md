# 数据库上手指南

每页只记录该 JDBC 家族的连接入口、驱动边界、权限和已知限制。Flydb 不捆绑任何厂商驱动；请按厂商许可和内部制品库规定获取驱动。

| 数据库 | 方言标识 | 当前证据 |
|---|---|---|
| [MySQL 8](mysql.md) | `mysql` | 本地 MySQL 8 容器契约、CLI 与 Spring Boot 端到端 |
| [PostgreSQL](postgresql.md) | `postgresql` | PostgreSQL 家族契约；CI 矩阵入口 |
| [Oracle](oracle.md) | `oracle` | 原生 Oracle 家族方言与授权实例契约入口；真实实例待显式环境 |
| [TiDB](tidb.md) | `tidb` | MySQL 家族兼容契约；真实 TiDB 待显式环境 |
| [OceanBase-MySQL](oceanbase-mysql.md) | `oceanbase` | MySQL 家族兼容契约与探测代理；真实租户待显式环境 |
| [openGauss](opengauss.md) | `opengauss` | PostgreSQL 家族兼容契约；真实实例待显式环境 |
| [KingbaseES](kingbasees.md) | `kingbasees` | PostgreSQL 家族兼容契约；真实实例需授权环境 |
| [达梦 DM8](dm8.md) | `dm` | 方言/元数据契约；真实实例需授权环境 |
| [OceanBase-Oracle](oceanbase-oracle.md) | `oceanbase` | 实验性；需要可创建 Oracle 租户的企业环境 |

所有页面都假设已完成 [CLI 五分钟上手](../../README.md#五分钟上手)。
