English | [中文](./README.md)

# FlyDB Database Migration Tool

FlyDB is a simple and easy-to-use database version control and migration tool that helps you easily manage changes to your database schema.

## Project Background

With the rapid development and widespread application of domestic databases, enterprises are facing new challenges in database migration. Existing database migration tools (such as Flyway), although powerful, have some limitations in the following aspects:

1.  **Database Adaptation**: Limited support for domestic databases, requiring additional adaptation work.
2.  **Complex Deployment**: Many dependencies, high deployment and maintenance costs.
3.  **Redundant Features**: Contains many features not commonly used by enterprises, increasing the learning curve.

Based on these issues, we developed FlyDB, aiming to provide a more lightweight and flexible database migration solution.

## Why Choose FlyDB?

Compared to traditional database migration tools like Flyway, FlyDB has the following advantages:

1.  **Broader Database Support**
    *   Generic implementation based on JDBC, easy to extend support for new databases.
    *   Prioritized support for domestic databases such as Dameng and KingbaseES.
    *   Provides a unified API interface, reducing database switching costs.

2.  **More Lightweight Design**
    *   Core functions focus on version control and migration management.
    *   Minimized dependencies, reducing deployment difficulty.
    *   Provides a simple and intuitive command-line tool.

3.  **More Flexible Version Management**
    *   Supports version rollback功能, facilitating troubleshooting.
    *   Provides clear migration history records.
    *   Supports target version migration to meet different scenario requirements.

4.  **More Friendly User Experience**
    *   Simple configuration method.
    *   Clear naming conventions.
    *   Detailed operational documentation.

## Features

-   Database version control
-   Automated database migration
-   Support for target version migration
-   Migration history logging
-   Simple command-line interface
-   Concurrent execution for multiple databases

## Environment Requirements

-   Java 8 or higher
-   MySQL database (or any database supporting JDBC)
-   curl (for command-line operations)

## Quick Start

### 1. Configure Database Connection

#### Single Database Configuration
Edit the `src/main/resources/application.properties` file:

```properties
# Migration script path configuration
flydb.scripts.path=db/migration

# Server configuration
server.port=8080
```

#### Multiple Database Configuration
Edit the `src/main/resources/db-connections.yml` file:

```yaml
# Global configuration
global:
  concurrent_execution: true  # Whether to enable concurrent execution for multiple databases
  max_concurrent_tasks: 5     # Maximum number of concurrent tasks
  timeout: 3600              # Execution timeout (seconds)

# Development environment
development:
  url: jdbc:mysql://localhost:3306/dev_db?useSSL=false&serverTimezone=UTC
  username: dev_user
  password: dev_password
  concurrent: true           # Whether to participate in concurrent execution

# Production environment
production:
  url: jdbc:mysql://prod-server:3306/prod_db?useSSL=false&serverTimezone=UTC
  username: prod_user
  password: prod_password
  concurrent: false          # Production environment does not participate in concurrent execution by default
```

### 2. Create Migration Scripts

Create SQL migration scripts in the `db/migration` directory, following this naming convention:

-   Migration script: `V{version_number}__{description}.sql`
-   Rollback script: `R{version_number}__{description}.sql`

Example:

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

### 3. Start the Service

```bash
# Build and run using Maven
mvn spring-boot:run
```

### 4. API Usage

#### Initialize Database
```bash
curl -X POST http://localhost:8080/api/flydb/init
```

#### Check Current Version
```bash
curl http://localhost:8080/api/flydb/version
```

#### Execute Migration
```bash
# Migrate to the latest version
curl -X POST http://localhost:8080/api/flydb/migrate

# Migrate to a specific version
curl -X POST "http://localhost:8080/api/flydb/migrate?targetVersion=2"
```

#### Version Rollback
```bash
# Rollback to a specific version
curl -X POST http://localhost:8080/api/flydb/rollback/1
```

## Project Architecture

### Core Components

1.  **FlyDB**: Core class, responsible for database version control and migration management.
    *   Initializes the version control table.
    *   Manages database versions.
    *   Executes version rollbacks.

2.  **Migration**: Migration script management class.
    *   Loads migration scripts.
    *   Executes migration operations.
    *   Records migration history.

3.  **DatabaseConfig**: Database configuration management class.
    *   Supports multiple database configurations.
    *   Dynamically switches database connections.

### Version Control Table Structure

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

## FAQ

### 1. How to handle migration failures?

When a migration fails, FlyDB will automatically roll back the transaction to ensure database consistency. You can:
* Check the error messages in the logs to identify the cause of the failure.
* Correct the problematic migration script.
* Manually fix the database state if necessary.
* Rerun the migration.

### 2. How to add support for a new database?

FlyDB is designed based on JDBC, making it relatively easy to extend support for new databases:
* Implement the `java.sql.Driver` interface for the new database if a JDBC driver is not readily available.
* Ensure the migration scripts use SQL syntax compatible with the new database.
* Update the database connection configuration in `application.properties` or `db-connections.yml`.

### 3. Can I use FlyDB with NoSQL databases?

FlyDB is primarily designed for relational databases that support SQL and JDBC. For NoSQL databases, you might need to consider other version control tools 금액specific to that NoSQL technology.

## Contribution Guide

We welcome contributions to FlyDB! If you'd like to contribute, please follow these steps:

1.  Fork the repository.
2.  Create a new branch for your feature or bug fix: `git checkout -b feature/your-feature-name` or `git checkout -b bugfix/issue-number`.
3.  Make your changes and commit them with clear and concise messages.
4.  Ensure your code adheres to the project's coding standards.
5.  Write unit tests for your changes.
6.  Push your changes to your forked repository.
7.  Create a pull request to the main repository's `main` branch.

Please provide a detailed description of your changes in the pull request.

## License

FlyDB is released under the [MIT License](./LICENSE).