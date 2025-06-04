-- 添加用户状态字段
ALTER TABLE users
ADD COLUMN status VARCHAR(20) DEFAULT 'active';