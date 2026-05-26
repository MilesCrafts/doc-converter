package com.office.ai.service;

import com.office.ai.entity.TaskInfo;

import java.util.Map;

public interface TaskService {
    /**
     * 创建并提交任务
     */
    String createTask(String fileId, String taskType, Long userId, String guestId, Map<String, Object> options);

    /**
     * 异步执行任务
     */
    void executeTask(String taskId);

    /**
     * 查询任务详情
     */
    TaskInfo getTask(String taskId);
}
