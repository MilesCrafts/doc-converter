package com.office.ai.task;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.office.ai.entity.FileInfo;
import com.office.ai.entity.TaskInfo;
import com.office.ai.mapper.FileInfoMapper;
import com.office.ai.mapper.TaskInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileCleanupTask {

    private final FileInfoMapper fileInfoMapper;
    private final TaskInfoMapper taskInfoMapper;

    @Value("${office-ai.upload.path}")
    private String uploadPath;

    /**
     * 每小时执行一次，清理过期文件
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanupExpiredFiles() {
        log.info("开始执行定时任务：清理过期文件");
        
        try {
            // 1. 清理过期源文件 (保留 1 天)
            LocalDateTime sourceExpireTime = LocalDateTime.now().minusDays(1);
            LambdaQueryWrapper<FileInfo> fileQuery = new LambdaQueryWrapper<FileInfo>()
                    .lt(FileInfo::getCreateTime, sourceExpireTime);
            List<FileInfo> expiredSourceFiles = fileInfoMapper.selectList(fileQuery);
            
            for (FileInfo fileInfo : expiredSourceFiles) {
                File sourceFile = new File(fileInfo.getStoragePath());
                if (sourceFile.exists()) {
                    FileUtil.del(sourceFile);
                    log.info("清理 1 天前的原文件: {}", sourceFile.getAbsolutePath());
                }
                fileInfoMapper.deleteById(fileInfo.getId());
            }

            // 2. 清理过期目标文件 (游客 3 天，登录用户 30 天)
            List<TaskInfo> allTasks = taskInfoMapper.selectList(null);
            LocalDateTime now = LocalDateTime.now();
            
            for (TaskInfo task : allTasks) {
                LocalDateTime taskCreateTime = task.getCreateTime();
                if (taskCreateTime == null) continue;

                boolean isExpired = false;
                if (task.getUserId() != null) {
                    // 登录用户保留 30 天
                    if (taskCreateTime.plusDays(30).isBefore(now)) {
                        isExpired = true;
                    }
                } else {
                    // 游客保留 3 天
                    if (taskCreateTime.plusDays(3).isBefore(now)) {
                        isExpired = true;
                    }
                }

                if (isExpired) {
                    File taskDir = new File(uploadPath + "output/" + task.getTaskId());
                    if (taskDir.exists()) {
                        FileUtil.del(taskDir);
                        log.info("清理过期转换结果文件夹: {}", taskDir.getAbsolutePath());
                    }
                    taskInfoMapper.deleteById(task.getId());
                }
            }
            
        } catch (Exception e) {
            log.error("执行文件清理任务异常", e);
        }
        log.info("定时文件清理任务执行完毕");
    }
}
