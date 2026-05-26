package com.office.ai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("file_info")
public class FileInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String fileId;
    
    private Long userId;
    
    private String fileName;
    
    private Long fileSize;
    
    private String fileType;
    
    private String storagePath;
    
    private LocalDateTime expireAt;
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
