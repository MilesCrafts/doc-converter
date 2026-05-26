package com.office.ai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("task_info")
public class TaskInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String taskId;
    
    private Long userId;
    
    private String guestId;
    
    private String fileId;
    
    private String taskType;
    
    private String status;
    
    private Integer progress;
    
    private String resultUrl;
    
    private String errorMsg;
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
