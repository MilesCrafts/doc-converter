package com.office.ai.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.office.ai.common.JwtUtil;
import com.office.ai.common.Result;
import com.office.ai.entity.FileInfo;
import com.office.ai.entity.TaskInfo;
import com.office.ai.mapper.FileInfoMapper;
import com.office.ai.mapper.TaskInfoMapper;
import com.office.ai.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskInfoMapper taskInfoMapper;
    private final FileInfoMapper fileInfoMapper;

    @PostMapping("/create")
    public Result<Map<String, String>> createTask(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        String fileId = asString(params.get("fileId"));
        String taskType = asString(params.get("taskType"));

        if (StrUtil.isBlank(taskType)) {
            return Result.error("PARAM_ERROR", "参数不完整");
        }

        Long userId = getUserIdFromRequest(request);
        String guestId = request.getHeader("X-Guest-Id");

        if (requiresFileId(taskType) && StrUtil.isBlank(fileId)) {
            return Result.error("PARAM_ERROR", "参数不完整");
        }

        String taskId = taskService.createTask(fileId, taskType, userId, guestId, params);
        
        taskService.executeTask(taskId);

        Map<String, String> data = new HashMap<>();
        data.put("taskId", taskId);
        return Result.success(data);
    }

    @GetMapping("/status/{taskId}")
    public Result<TaskInfo> getTaskStatus(@PathVariable String taskId) {
        TaskInfo task = taskService.getTask(taskId);
        if (task == null) {
            return Result.error("NOT_FOUND", "任务不存在");
        }
        return Result.success(task);
    }

    @PostMapping("/sync")
    public Result<String> syncRecords(@RequestBody List<String> taskIds, HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null) {
            return Result.error("UNAUTHORIZED", "未登录");
        }
        
        if (taskIds != null && !taskIds.isEmpty()) {
            LambdaUpdateWrapper<TaskInfo> updateWrapper = new LambdaUpdateWrapper<TaskInfo>()
                    .in(TaskInfo::getTaskId, taskIds)
                    .isNull(TaskInfo::getUserId)
                    .set(TaskInfo::getUserId, userId);
            taskInfoMapper.update(null, updateWrapper);
        }
        return Result.success("同步成功");
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> getHistoryList(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null) {
            return Result.error("UNAUTHORIZED", "未登录");
        }

        List<TaskInfo> tasks = taskInfoMapper.selectList(new LambdaQueryWrapper<TaskInfo>()
                .eq(TaskInfo::getUserId, userId)
                .orderByDesc(TaskInfo::getCreateTime));

        // 定义日期格式化器
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskInfo task : tasks) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", task.getTaskId());
            map.put("toolName", getToolName(task.getTaskType()));
            map.put("status", task.getStatus());
            map.put("resultUrl", task.getResultUrl());
            
            // 格式化时间，避免前端解析 LocalDateTime 变成 null
            if (task.getCreateTime() != null) {
                map.put("time", task.getCreateTime().format(formatter));
            } else {
                map.put("time", java.time.LocalDateTime.now().format(formatter)); // 对于以前缺失时间的旧数据，给个默认值防止前端显示 null
            }
            
            FileInfo fileInfo = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, task.getFileId()));
            if (fileInfo != null) {
                map.put("name", fileInfo.getFileName());
            } else {
                map.put("name", "未知文件");
            }
            result.add(map);
        }
        return Result.success(result);
    }

    private String getToolName(String type) {
        switch (type) {
            case "pdf2word": return "PDF 转 Word";
            case "word2pdf": return "Word 转 PDF";
            case "excel2pdf": return "Excel 转 PDF";
            case "img2pdf": return "图片 转 PDF";
            case "pdfWatermark": return "PDF 加水印";
            case "pdfMerge": return "PDF 合并";
            case "pdfSplit": return "PDF 拆分";
            case "pdfCompress": return "PDF 压缩";
            case "aiSummary": return "AI 总结 PDF";
            case "aiPoints": return "AI 提取重点";
            case "aiMarkdown": return "AI 转 Markdown";
            default: return type;
        }
    }

    private boolean requiresFileId(String taskType) {
        return !"pdfMerge".equals(taskType) && !"pdf_merge".equals(taskType);
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StrUtil.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return JwtUtil.getUserId(token);
        }
        return null;
    }
}
