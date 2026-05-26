package com.office.ai.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.office.ai.common.Result;
import com.office.ai.entity.FileInfo;
import com.office.ai.mapper.FileInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/file")
@RequiredArgsConstructor
public class FileController {

    private final FileInfoMapper fileInfoMapper;
    
    @Autowired
    private com.office.ai.mapper.TaskInfoMapper taskInfoMapper;

    @Value("${office-ai.upload.path}")
    private String uploadPath;

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error("FILE_EMPTY", "文件不能为空");
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String suffix = FileUtil.getSuffix(originalFilename);
            String fileId = IdUtil.fastSimpleUUID();
            String fileName = fileId + "." + suffix;
            
            // 1. 保存物理文件
            String todayPath = uploadPath + "source/";
            File dest = new File(todayPath + fileName);
            if (!dest.getParentFile().exists()) {
                dest.getParentFile().mkdirs();
            }
            file.transferTo(dest);

            // 2. 存入数据库
            FileInfo fileInfo = new FileInfo();
            fileInfo.setFileId(fileId);
            fileInfo.setFileName(originalFilename);
            fileInfo.setFileSize(file.getSize());
            fileInfo.setFileType(file.getContentType());
            fileInfo.setStoragePath(dest.getAbsolutePath());
            fileInfo.setExpireAt(LocalDateTime.now().plusDays(1)); // 1天后过期
            fileInfoMapper.insert(fileInfo);

            // 3. 返回信息
            Map<String, Object> data = new HashMap<>();
            data.put("fileId", fileId);
            data.put("fileName", originalFilename);
            data.put("url", "/api/v1/file/preview/" + fileId);
            
            return Result.success(data);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.error("UPLOAD_FAILED", "服务器上传失败");
        }
    }

    @GetMapping("/raw/{fileId}")
    public ResponseEntity<byte[]> raw(@PathVariable String fileId) throws Exception {
        // 处理可能带后缀的 fileId (如: xxx.jpg)
        String realFileId = fileId;
        if (fileId.contains(".")) {
            realFileId = fileId.substring(0, fileId.lastIndexOf('.'));
        }

        FileInfo fileInfo = fileInfoMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, realFileId));
        if (fileInfo == null) {
            return ResponseEntity.notFound().build();
        }

        File f = new File(fileInfo.getStoragePath());
        if (!f.isFile()) {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes;
        try (InputStream in = cn.hutool.core.io.FileUtil.getInputStream(f)) {
            bytes = cn.hutool.core.io.IoUtil.readBytes(in);
        }

        String filename = fileInfo.getFileName();
        String contentType = fileInfo.getFileType();
        
        // 自动校正图片类型的 Content-Type
        String suffix = cn.hutool.core.io.FileUtil.getSuffix(filename).toLowerCase();
        if (cn.hutool.core.util.StrUtil.isBlank(contentType) || contentType.equals(MediaType.APPLICATION_OCTET_STREAM_VALUE)) {
            if ("jpg".equals(suffix) || "jpeg".equals(suffix)) contentType = MediaType.IMAGE_JPEG_VALUE;
            else if ("png".equals(suffix)) contentType = MediaType.IMAGE_PNG_VALUE;
            else if ("gif".equals(suffix)) contentType = MediaType.IMAGE_GIF_VALUE;
            else if ("pdf".equals(suffix)) contentType = MediaType.APPLICATION_PDF_VALUE;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + java.net.URLEncoder.encode(filename, "UTF-8") + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    /**
     * 下载/预览任务转换结果文件
     */
    @GetMapping("/preview/{taskId}")
    public ResponseEntity<byte[]> previewResult(@PathVariable String taskId) throws Exception {
        return getFileResponseEntity(taskId, "inline");
    }

    @GetMapping("/download/{taskId}")
    public ResponseEntity<byte[]> downloadResult(@PathVariable String taskId) throws Exception {
        return getFileResponseEntity(taskId, "attachment");
    }

    private ResponseEntity<byte[]> getFileResponseEntity(String taskId, String dispositionType) throws Exception {
        com.office.ai.entity.TaskInfo task = taskInfoMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.office.ai.entity.TaskInfo>().eq(com.office.ai.entity.TaskInfo::getTaskId, taskId));
        if (task == null || !"success".equals(task.getStatus())) {
            return ResponseEntity.notFound().build();
        }

        String outputDir = uploadPath + "output/" + taskId;
        File dir = new File(outputDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return ResponseEntity.notFound().build();
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return ResponseEntity.notFound().build();
        }

        File resultFile = files[0];
        byte[] bytes;
        try (InputStream in = cn.hutool.core.io.FileUtil.getInputStream(resultFile)) {
            bytes = cn.hutool.core.io.IoUtil.readBytes(in);
        }

        String filename = resultFile.getName();
        String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        if (filename.endsWith(".pdf")) {
            contentType = MediaType.APPLICATION_PDF_VALUE;
        } else if (filename.endsWith(".docx")) {
            contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        } else if (filename.endsWith(".txt")) {
            contentType = MediaType.TEXT_PLAIN_VALUE;
        } else if (filename.endsWith(".zip")) {
            contentType = "application/zip";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, dispositionType + "; filename=\"" + java.net.URLEncoder.encode(filename, "UTF-8") + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }
}
