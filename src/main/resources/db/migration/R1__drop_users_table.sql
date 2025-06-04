-- 删除用户表相关的索引
DROP INDEX IF EXISTS idx_users_email ON users;
DROP INDEX IF EXISTS idx_users_status ON users;

-- 删除用户表
DROP TABLE IF EXISTS users;