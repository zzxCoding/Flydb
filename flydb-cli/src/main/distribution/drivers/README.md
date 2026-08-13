# JDBC 驱动目录

Flydb 发行包不捆绑 JDBC 驱动。请将目标数据库的 Java 8 兼容驱动 jar 直接放入本目录；小众数据库还可把实现 `DatabaseType` SPI 的方言 jar 放在这里。

| 数据库 | 驱动类 | 常见获取方式 |
|---|---|---|
| MySQL / TiDB | `com.mysql.cj.jdbc.Driver` | `com.mysql:mysql-connector-j` |
| PostgreSQL | `org.postgresql.Driver` | `org.postgresql:postgresql` |
| Oracle | `oracle.jdbc.OracleDriver` | Oracle 制品库中的 `ojdbc` |
| openGauss | `org.opengauss.Driver` | `org.opengauss:opengauss-jdbc` |
| KingbaseES | `com.kingbase8.Driver` | 人大金仓交付介质或企业制品库 |
| 达梦 DM8 | `dm.jdbc.driver.DmDriver` | 达梦安装介质或企业制品库 |
| OceanBase | `com.oceanbase.jdbc.Driver` | `com.oceanbase:oceanbase-client` |

厂商 URL 未被内置映射识别，或需要复用 MySQL/Oracle 家族时，同时指定 `--driver <驱动类>` 和 `--database-type <方言名>`；小众数据库还需把实现 `DatabaseType` SPI 的方言 jar 放在本目录。请遵守相应驱动的许可证和分发条款。
