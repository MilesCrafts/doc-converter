-- 创建数据库
CREATE DATABASE IF NOT EXISTS ai_tool DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ai_tool;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `openid` VARCHAR(100) NOT NULL COMMENT '微信openid',
  `nickname` VARCHAR(100) DEFAULT NULL COMMENT '昵称',
  `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 文件元数据表
CREATE TABLE IF NOT EXISTS `file_info` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `file_id` VARCHAR(100) NOT NULL COMMENT '外部UUID',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `file_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
  `file_size` BIGINT NOT NULL COMMENT '文件大小(Byte)',
  `file_type` VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',
  `storage_path` VARCHAR(500) NOT NULL COMMENT '存储路径',
  `expire_at` DATETIME DEFAULT NULL COMMENT '过期时间',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_id` (`file_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';

-- 统一任务表
CREATE TABLE IF NOT EXISTS `task_info` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` VARCHAR(100) NOT NULL COMMENT '外部UUID',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `guest_id` VARCHAR(100) DEFAULT NULL COMMENT '匿名ID',
  `file_id` VARCHAR(100) NOT NULL COMMENT '关联文件ID',
  `task_type` VARCHAR(50) NOT NULL COMMENT '任务类型(pdf_to_word等)',
  `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态(pending,processing,success,failed等)',
  `progress` INT DEFAULT 0 COMMENT '进度(0-100)',
  `result_url` VARCHAR(500) DEFAULT NULL COMMENT '结果文件URL',
  `error_msg` VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_guest_id` (`guest_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一任务表';
