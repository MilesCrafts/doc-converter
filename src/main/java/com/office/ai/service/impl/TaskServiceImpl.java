package com.office.ai.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.office.ai.entity.FileInfo;
import com.office.ai.entity.TaskInfo;
import com.office.ai.mapper.FileInfoMapper;
import com.office.ai.mapper.TaskInfoMapper;
import com.office.ai.service.ConvertService;
import com.office.ai.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private static final int MAX_ERROR_MSG_LENGTH = 500;

    private final TaskInfoMapper taskInfoMapper;
    private final FileInfoMapper fileInfoMapper;
    private final ConvertService convertService;

    @Value("${office-ai.upload.path}")
    private String uploadPath;
    
    private final Map<String, String> watermarkTextByTaskId = new ConcurrentHashMap<>();
    private final Map<String, MergeOptions> mergeOptionsByTaskId = new ConcurrentHashMap<>();
    private final Map<String, SplitOptions> splitOptionsByTaskId = new ConcurrentHashMap<>();
    private final Map<String, String> compressLevelByTaskId = new ConcurrentHashMap<>();

    @Override
    public String createTask(String fileId, String taskType, Long userId, String guestId, Map<String, Object> options) {
        String taskId = IdUtil.fastSimpleUUID();

        String normalizedTaskType = normalizeTaskType(taskType);
        String resolvedFileId = fileId;
        if (cn.hutool.core.util.StrUtil.isBlank(resolvedFileId) && isPdfMergeTask(normalizedTaskType)) {
            List<String> fileIds = asStringList(options == null ? null : options.get("fileIds"));
            if (fileIds != null && !fileIds.isEmpty()) {
                resolvedFileId = fileIds.get(0);
            }
        }
        
        TaskInfo task = new TaskInfo();
        task.setTaskId(taskId);
        task.setFileId(resolvedFileId);
        task.setTaskType(normalizedTaskType);
        task.setUserId(userId);
        task.setGuestId(guestId);
        task.setStatus("pending");
        task.setProgress(0);
        
        taskInfoMapper.insert(task);
        
        if (options != null) {
            String watermarkText = asString(options.get("watermarkText"));
            if (cn.hutool.core.util.StrUtil.isNotBlank(watermarkText)) {
                watermarkTextByTaskId.put(taskId, watermarkText);
            }

            if (isPdfMergeTask(normalizedTaskType)) {
                List<String> fileIds = asStringList(options.get("fileIds"));
                List<String> pageSpecs = asStringList(options.get("pageSpecs"));
                mergeOptionsByTaskId.put(taskId, new MergeOptions(fileIds, pageSpecs));
            } else if (isPdfSplitTask(normalizedTaskType)) {
                String mode = asString(options.get("splitMode"));
                String ranges = asString(options.get("splitRanges"));
                splitOptionsByTaskId.put(taskId, new SplitOptions(mode, ranges));
            } else if (isPdfCompressTask(normalizedTaskType)) {
                String level = asString(options.get("compressLevel"));
                compressLevelByTaskId.put(taskId, level);
            }
        }
        return taskId;
    }

    @Async
    @Override
    public void executeTask(String taskId) {
        log.info("异步执行任务: {}", taskId);
        
        TaskInfo task = taskInfoMapper.selectOne(new LambdaQueryWrapper<TaskInfo>().eq(TaskInfo::getTaskId, taskId));
        if (task == null) return;

        try {
            // 1. 更新状态为处理中
            task.setStatus("processing");
            task.setProgress(10);
            taskInfoMapper.updateById(task);

            String taskType = normalizeTaskType(task.getTaskType());
            String outputDir = uploadPath + "output/" + taskId;

            task.setProgress(25);
            taskInfoMapper.updateById(task);

            File resultFile;
            if (isPdfMergeTask(taskType)) {
                MergeOptions options = mergeOptionsByTaskId.get(taskId);
                if (options == null || options.fileIds == null || options.fileIds.size() < 2) {
                    throw new RuntimeException("合并需要至少 2 个 PDF");
                }
                resultFile = mergePdfFiles(options.fileIds, options.pageSpecs, outputDir);
            } else {
                FileInfo fileInfo = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, task.getFileId()));
                if (fileInfo == null) {
                    throw new RuntimeException("源文件不存在");
                }
                if (!isPdfFile(fileInfo) && (isPdfWatermarkTask(taskType) || isPdfSplitTask(taskType) || isPdfCompressTask(taskType))) {
                    throw new RuntimeException("仅支持 PDF 文件");
                }

                File sourceFile = new File(fileInfo.getStoragePath());
                String targetExt = getTargetExt(taskType);

                task.setProgress(35);
                taskInfoMapper.updateById(task);

                if (isPdfWatermarkTask(taskType)) {
                    String watermarkText = watermarkTextByTaskId.get(taskId);
                    if (cn.hutool.core.util.StrUtil.isBlank(watermarkText)) {
                        throw new RuntimeException("水印内容不能为空");
                    }
                    resultFile = addPdfWatermark(sourceFile, watermarkText, outputDir);
                } else if (isPdfSplitTask(taskType)) {
                    SplitOptions options = splitOptionsByTaskId.get(taskId);
                    resultFile = splitPdfFile(sourceFile, options, outputDir);
                } else if (isPdfCompressTask(taskType)) {
                    String level = compressLevelByTaskId.get(taskId);
                    resultFile = compressPdfFile(sourceFile, level, outputDir);
                } else {
                    resultFile = convertService.convert(sourceFile, task.getFileId(), targetExt, outputDir);
                }
            }

            // 4. 更新成功状态
            task.setStatus("success");
            task.setProgress(100);
            task.setResultUrl("/api/v1/file/download/" + taskId); // 暂定下载路径
            taskInfoMapper.updateById(task);
            
        } catch (Exception e) {
            log.error("任务执行失败", e);
            task.setStatus("failed");
            task.setErrorMsg(truncateErrorMsg(e.getMessage()));
            taskInfoMapper.updateById(task);
        } finally {
            watermarkTextByTaskId.remove(taskId);
            mergeOptionsByTaskId.remove(taskId);
            splitOptionsByTaskId.remove(taskId);
            compressLevelByTaskId.remove(taskId);
        }
    }

    @Override
    public TaskInfo getTask(String taskId) {
        return taskInfoMapper.selectOne(new LambdaQueryWrapper<TaskInfo>().eq(TaskInfo::getTaskId, taskId));
    }

    private String normalizeTaskType(String taskType) {
        if (taskType == null) return null;
        if ("pdf_watermark".equals(taskType)) return "pdfWatermark";
        if ("pdf_merge".equals(taskType)) return "pdfMerge";
        if ("pdf_split".equals(taskType)) return "pdfSplit";
        if ("pdf_compress".equals(taskType)) return "pdfCompress";
        if ("pdf_to_word".equals(taskType)) return "pdf2word";
        if ("word_to_pdf".equals(taskType)) return "word2pdf";
        if ("excel_to_pdf".equals(taskType)) return "excel2pdf";
        if ("img_to_pdf".equals(taskType)) return "img2pdf";
        return taskType;
    }

    private boolean isPdfMergeTask(String taskType) {
        return "pdfMerge".equals(taskType) || "pdf_merge".equals(taskType);
    }

    private boolean isPdfSplitTask(String taskType) {
        return "pdfSplit".equals(taskType) || "pdf_split".equals(taskType);
    }

    private boolean isPdfCompressTask(String taskType) {
        return "pdfCompress".equals(taskType) || "pdf_compress".equals(taskType);
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private List<String> asStringList(Object v) {
        if (v == null) return null;
        if (v instanceof List) {
            List<?> raw = (List<?>) v;
            List<String> out = new ArrayList<>();
            for (Object item : raw) {
                if (item == null) continue;
                out.add(String.valueOf(item));
            }
            return out;
        }
        String s = String.valueOf(v);
        if (cn.hutool.core.util.StrUtil.isBlank(s)) return null;
        String[] parts = s.split("[,\\s]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (cn.hutool.core.util.StrUtil.isBlank(p)) continue;
            out.add(p.trim());
        }
        return out;
    }

    private String getTargetExt(String taskType) {
        switch (taskType) {
            case "pdf2word":
            case "pdf_to_word": return "docx";
            case "word2pdf":
            case "word_to_pdf":
            case "excel2pdf":
            case "excel_to_pdf":
            case "img2pdf":
            case "img_to_pdf":
            case "pdfWatermark":
            case "pdfMerge":
            case "pdfSplit":
            case "pdfCompress": return "pdf";
            case "aiSummary":
            case "aiPoints":
            case "aiMarkdown": return "txt"; // AI 任务暂时输出为 txt 或直接处理内容
            default: throw new IllegalArgumentException("不支持的任务类型: " + taskType);
        }
    }

    private String truncateErrorMsg(String message) {
        if (message == null) return null;
        if (message.length() <= MAX_ERROR_MSG_LENGTH) return message;
        return message.substring(0, MAX_ERROR_MSG_LENGTH);
    }

    private boolean isPdfWatermarkTask(String taskType) {
        return "pdfWatermark".equals(taskType) || "pdf_watermark".equals(taskType);
    }

    private boolean isPdfFile(FileInfo fileInfo) {
        if (fileInfo == null) return false;
        String name = fileInfo.getFileName();
        if (name != null && name.toLowerCase().endsWith(".pdf")) return true;
        String ct = fileInfo.getFileType();
        return ct != null && ct.toLowerCase().contains("pdf");
    }

    private static class MergeOptions {
        private final List<String> fileIds;
        private final List<String> pageSpecs;

        private MergeOptions(List<String> fileIds, List<String> pageSpecs) {
            this.fileIds = fileIds;
            this.pageSpecs = pageSpecs;
        }
    }

    private static class SplitOptions {
        private final String mode;
        private final String ranges;

        private SplitOptions(String mode, String ranges) {
            this.mode = mode;
            this.ranges = ranges;
        }
    }

    private File ensureOutputDir(String outputDir) {
        File outDir = new File(outputDir);
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new RuntimeException("创建输出目录失败");
            }
        }
        return outDir;
    }

    private File mergePdfFiles(List<String> fileIds, List<String> pageSpecs, String outputDir) throws Exception {
        File outDir = ensureOutputDir(outputDir);
        File outFile = new File(outDir, "merged.pdf");

        com.itextpdf.text.Document document = null;
        com.itextpdf.text.pdf.PdfCopy copy = null;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(outFile);
            document = new com.itextpdf.text.Document();
            copy = new com.itextpdf.text.pdf.PdfCopy(document, fos);
            document.open();

            for (int idx = 0; idx < fileIds.size(); idx++) {
                String fileId = fileIds.get(idx);
                if (cn.hutool.core.util.StrUtil.isBlank(fileId)) continue;
                FileInfo fi = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
                if (fi == null) {
                    throw new RuntimeException("找不到文件: " + fileId);
                }
                if (!isPdfFile(fi)) {
                    throw new RuntimeException("仅支持合并 PDF 文件");
                }

                com.itextpdf.text.pdf.PdfReader reader = null;
                try {
                    reader = new com.itextpdf.text.pdf.PdfReader(fi.getStoragePath());
                    int totalPages = reader.getNumberOfPages();
                    String spec = pageSpecs != null && idx < pageSpecs.size() ? pageSpecs.get(idx) : null;
                    List<Integer> pages = parsePageSpec(spec, totalPages);
                    for (Integer p : pages) {
                        copy.addPage(copy.getImportedPage(reader, p));
                    }
                } finally {
                    if (reader != null) {
                        try { reader.close(); } catch (Exception ignored) {}
                    }
                }
            }
        } finally {
            if (document != null) {
                try { document.close(); } catch (Exception ignored) {}
            }
            if (copy != null) {
                try { copy.close(); } catch (Exception ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
        return outFile;
    }

    private File splitPdfFile(File sourcePdf, SplitOptions options, String outputDir) throws Exception {
        File outDir = ensureOutputDir(outputDir);
        String baseName = cn.hutool.core.io.FileUtil.mainName(sourcePdf.getName());
        String mode = options == null ? null : options.mode;

        if (cn.hutool.core.util.StrUtil.isNotBlank(mode) && ("each".equalsIgnoreCase(mode) || "eachPage".equalsIgnoreCase(mode) || "split".equalsIgnoreCase(mode))) {
            com.itextpdf.text.pdf.PdfReader reader = null;
            List<File> pageFiles = new ArrayList<>();
            try {
                reader = new com.itextpdf.text.pdf.PdfReader(sourcePdf.getAbsolutePath());
                int totalPages = reader.getNumberOfPages();
                for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                    File pageFile = new File(outDir, baseName + "_p" + pageNum + ".pdf");
                    com.itextpdf.text.Document document = null;
                    com.itextpdf.text.pdf.PdfCopy copy = null;
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(pageFile);
                        document = new com.itextpdf.text.Document();
                        copy = new com.itextpdf.text.pdf.PdfCopy(document, fos);
                        document.open();
                        copy.addPage(copy.getImportedPage(reader, pageNum));
                    } finally {
                        if (document != null) {
                            try { document.close(); } catch (Exception ignored) {}
                        }
                        if (copy != null) {
                            try { copy.close(); } catch (Exception ignored) {}
                        }
                        if (fos != null) {
                            try { fos.close(); } catch (Exception ignored) {}
                        }
                    }
                    pageFiles.add(pageFile);
                }
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Exception ignored) {}
                }
            }

            File zipFile = new File(outDir, baseName + "_pages.zip");
            zipFiles(pageFiles, zipFile);
            for (File f : pageFiles) {
                try { f.delete(); } catch (Exception ignored) {}
            }
            return zipFile;
        }

        String ranges = options == null ? null : options.ranges;
        if (cn.hutool.core.util.StrUtil.isBlank(ranges)) {
            throw new RuntimeException("请输入页码范围");
        }

        com.itextpdf.text.pdf.PdfReader reader = null;
        com.itextpdf.text.Document document = null;
        com.itextpdf.text.pdf.PdfCopy copy = null;
        FileOutputStream fos = null;
        try {
            reader = new com.itextpdf.text.pdf.PdfReader(sourcePdf.getAbsolutePath());
            int totalPages = reader.getNumberOfPages();
            List<Integer> pages = parsePageSpec(ranges, totalPages);

            File outFile = new File(outDir, baseName + "_extract.pdf");
            fos = new FileOutputStream(outFile);
            document = new com.itextpdf.text.Document();
            copy = new com.itextpdf.text.pdf.PdfCopy(document, fos);
            document.open();
            for (Integer p : pages) {
                copy.addPage(copy.getImportedPage(reader, p));
            }
            return outFile;
        } finally {
            if (document != null) {
                try { document.close(); } catch (Exception ignored) {}
            }
            if (copy != null) {
                try { copy.close(); } catch (Exception ignored) {}
            }
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }

    private File compressPdfFile(File sourcePdf, String level, String outputDir) throws Exception {
        File outDir = ensureOutputDir(outputDir);
        String baseName = cn.hutool.core.io.FileUtil.mainName(sourcePdf.getName());
        String normalized = cn.hutool.core.util.StrUtil.blankToDefault(level, "normal").toLowerCase();

        float jpegQuality = 0f;
        int maxDim = 0;
        if ("hd".equals(normalized) || "high".equals(normalized)) {
            jpegQuality = 0.82f;
            maxDim = 1800;
        } else if ("extreme".equals(normalized) || "limit".equals(normalized) || "max".equals(normalized)) {
            jpegQuality = 0.6f;
            maxDim = 1200;
        }

        File outFile = new File(outDir, baseName + "_compressed.pdf");
        com.itextpdf.text.pdf.PdfReader reader = null;
        com.itextpdf.text.pdf.PdfStamper stamper = null;
        FileOutputStream fos = null;
        try {
            reader = new com.itextpdf.text.pdf.PdfReader(sourcePdf.getAbsolutePath());
            try { reader.removeUnusedObjects(); } catch (Exception ignored) {}

            fos = new FileOutputStream(outFile);
            stamper = new com.itextpdf.text.pdf.PdfStamper(reader, fos);
            stamper.setFullCompression();
            stamper.getWriter().setCompressionLevel(9);

            if (jpegQuality > 0f && maxDim > 0) {
                recompressImages(reader, stamper, jpegQuality, maxDim);
            }
        } finally {
            if (stamper != null) {
                try { stamper.close(); } catch (Exception ignored) {}
            }
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
        return outFile;
    }

    private void zipFiles(List<File> files, File zipFile) throws Exception {
        ZipOutputStream zos = null;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(zipFile);
            zos = new ZipOutputStream(fos);
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                ZipEntry entry = new ZipEntry(f.getName());
                zos.putNextEntry(entry);
                try (InputStream in = cn.hutool.core.io.FileUtil.getInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) > 0) {
                        zos.write(buf, 0, read);
                    }
                }
                zos.closeEntry();
            }
        } finally {
            if (zos != null) {
                try { zos.close(); } catch (Exception ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }

    private List<Integer> parsePageSpec(String spec, int totalPages) {
        List<Integer> pages = new ArrayList<>();
        if (cn.hutool.core.util.StrUtil.isBlank(spec)) {
            for (int i = 1; i <= totalPages; i++) pages.add(i);
            return pages;
        }

        String normalized = spec.replace("，", ",").replace("；", ",").replace(";", ",").trim();
        String[] parts = normalized.split(",");
        for (String raw : parts) {
            if (cn.hutool.core.util.StrUtil.isBlank(raw)) continue;
            String token = raw.trim().replaceAll("\\s+", "");
            int dash = token.indexOf('-');
            if (dash < 0) {
                int p = parsePageNumber(token);
                if (p < 1 || p > totalPages) throw new RuntimeException("页码超出范围: " + token);
                pages.add(p);
            } else {
                String a = token.substring(0, dash);
                String b = token.substring(dash + 1);
                int start = cn.hutool.core.util.StrUtil.isBlank(a) ? 1 : parsePageNumber(a);
                int end = cn.hutool.core.util.StrUtil.isBlank(b) ? totalPages : parsePageNumber(b);
                if (start < 1 || end < 1 || start > totalPages || end > totalPages) throw new RuntimeException("页码超出范围: " + token);
                if (start > end) throw new RuntimeException("页码范围不合法: " + token);
                for (int p = start; p <= end; p++) pages.add(p);
            }
        }

        if (pages.isEmpty()) {
            throw new RuntimeException("页码范围为空");
        }
        return pages;
    }

    private int parsePageNumber(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            throw new RuntimeException("页码格式错误: " + s);
        }
    }

    private void recompressImages(com.itextpdf.text.pdf.PdfReader reader, com.itextpdf.text.pdf.PdfStamper stamper, float jpegQuality, int maxDim) throws Exception {
        Set<Integer> visited = new java.util.HashSet<>();
        int pages = reader.getNumberOfPages();
        for (int i = 1; i <= pages; i++) {
            com.itextpdf.text.pdf.PdfDictionary page = reader.getPageN(i);
            com.itextpdf.text.pdf.PdfDictionary res = page == null ? null : page.getAsDict(com.itextpdf.text.pdf.PdfName.RESOURCES);
            recompressImagesInResources(res, stamper, jpegQuality, maxDim, visited);
        }
    }

    private void recompressImagesInResources(com.itextpdf.text.pdf.PdfDictionary resources, com.itextpdf.text.pdf.PdfStamper stamper, float jpegQuality, int maxDim, Set<Integer> visited) throws Exception {
        if (resources == null) return;
        com.itextpdf.text.pdf.PdfDictionary xobj = resources.getAsDict(com.itextpdf.text.pdf.PdfName.XOBJECT);
        if (xobj == null) return;

        for (com.itextpdf.text.pdf.PdfName name : xobj.getKeys()) {
            com.itextpdf.text.pdf.PdfObject direct = xobj.getDirectObject(name);
            if (direct == null) continue;

            if (direct.isIndirect()) {
                int num = ((com.itextpdf.text.pdf.PdfIndirectReference) direct).getNumber();
                if (!visited.add(num)) continue;
            }

            if (direct instanceof com.itextpdf.text.pdf.PRStream) {
                com.itextpdf.text.pdf.PRStream stream = (com.itextpdf.text.pdf.PRStream) direct;
                com.itextpdf.text.pdf.PdfName subtype = stream.getAsName(com.itextpdf.text.pdf.PdfName.SUBTYPE);
                if (com.itextpdf.text.pdf.PdfName.FORM.equals(subtype)) {
                    recompressImagesInResources(stream.getAsDict(com.itextpdf.text.pdf.PdfName.RESOURCES), stamper, jpegQuality, maxDim, visited);
                } else if (com.itextpdf.text.pdf.PdfName.IMAGE.equals(subtype)) {
                    try {
                        com.itextpdf.text.pdf.parser.PdfImageObject image = new com.itextpdf.text.pdf.parser.PdfImageObject(stream);
                        BufferedImage buffered = image.getBufferedImage();
                        if (buffered == null) continue;

                        int w = buffered.getWidth();
                        int h = buffered.getHeight();
                        if (w <= 0 || h <= 0) continue;
                        if (w * h < 20_000) continue;

                        BufferedImage rgb = toRgbImage(buffered);
                        BufferedImage scaled = scaleDown(rgb, maxDim);
                        byte[] jpegBytes = encodeJpeg(scaled, jpegQuality);
                        if (jpegBytes == null || jpegBytes.length == 0) continue;

                        com.itextpdf.text.pdf.PdfStream newStream = new com.itextpdf.text.pdf.PdfStream(jpegBytes);
                        newStream.put(com.itextpdf.text.pdf.PdfName.TYPE, com.itextpdf.text.pdf.PdfName.XOBJECT);
                        newStream.put(com.itextpdf.text.pdf.PdfName.SUBTYPE, com.itextpdf.text.pdf.PdfName.IMAGE);
                        newStream.put(com.itextpdf.text.pdf.PdfName.WIDTH, new com.itextpdf.text.pdf.PdfNumber(scaled.getWidth()));
                        newStream.put(com.itextpdf.text.pdf.PdfName.HEIGHT, new com.itextpdf.text.pdf.PdfNumber(scaled.getHeight()));
                        newStream.put(com.itextpdf.text.pdf.PdfName.COLORSPACE, com.itextpdf.text.pdf.PdfName.DEVICERGB);
                        newStream.put(com.itextpdf.text.pdf.PdfName.BITSPERCOMPONENT, new com.itextpdf.text.pdf.PdfNumber(8));
                        newStream.put(com.itextpdf.text.pdf.PdfName.FILTER, com.itextpdf.text.pdf.PdfName.DCTDECODE);

                        com.itextpdf.text.pdf.PdfIndirectObject ref = stamper.getWriter().addToBody(newStream);
                        xobj.put(name, ref.getIndirectReference());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private BufferedImage toRgbImage(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setBackground(java.awt.Color.WHITE);
            g.clearRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private BufferedImage scaleDown(BufferedImage src, int maxDim) {
        if (maxDim <= 0) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxDim) return src;
        float ratio = (float) maxDim / (float) max;
        int newW = Math.max(1, Math.round(w * ratio));
        int newH = Math.max(1, Math.round(h * ratio));
        BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, newW, newH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private byte[] encodeJpeg(BufferedImage img, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageOutputStream ios = null;
        try {
            ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            if (ios != null) {
                try { ios.close(); } catch (Exception ignored) {}
            }
            try { writer.dispose(); } catch (Exception ignored) {}
        }
        return baos.toByteArray();
    }

    private com.itextpdf.text.pdf.BaseFont loadWatermarkBaseFont() throws Exception {
        try {
            return com.itextpdf.text.pdf.BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", com.itextpdf.text.pdf.BaseFont.NOT_EMBEDDED);
        } catch (Exception ignored) {
        }

        String sysProp = System.getProperty("watermark.font.path");
        String env = System.getenv("WATERMARK_FONT_PATH");

        String[] candidates = new String[] {
                sysProp,
                env,
                "C:\\Windows\\Fonts\\msyh.ttc,0",
                "C:\\Windows\\Fonts\\simsun.ttc,0",
                "C:\\Windows\\Fonts\\simhei.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
                "/usr/share/fonts/truetype/arphic/ukai.ttc",
                "/usr/share/fonts/truetype/arphic/uming.ttc"
        };

        for (String candidate : candidates) {
            if (cn.hutool.core.util.StrUtil.isBlank(candidate)) continue;
            String fontFile = candidate;
            int commaIdx = candidate.indexOf(',');
            if (commaIdx > 0) {
                fontFile = candidate.substring(0, commaIdx);
            }
            if (!new File(fontFile).exists()) continue;
            try {
                return com.itextpdf.text.pdf.BaseFont.createFont(candidate, com.itextpdf.text.pdf.BaseFont.IDENTITY_H, com.itextpdf.text.pdf.BaseFont.EMBEDDED);
            } catch (Exception ignored) {
            }
        }

        return com.itextpdf.text.pdf.BaseFont.createFont(com.itextpdf.text.pdf.BaseFont.HELVETICA, com.itextpdf.text.pdf.BaseFont.WINANSI, com.itextpdf.text.pdf.BaseFont.NOT_EMBEDDED);
    }

    private File addPdfWatermark(File sourcePdf, String watermarkText, String outputDir) throws Exception {
        String baseName = cn.hutool.core.io.FileUtil.mainName(sourcePdf.getName());
        File outDir = new File(outputDir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        File outFile = new File(outDir, baseName + ".pdf");
        
        com.itextpdf.text.pdf.PdfReader reader = null;
        com.itextpdf.text.pdf.PdfStamper stamper = null;
        FileOutputStream fos = null;
        try {
            reader = new com.itextpdf.text.pdf.PdfReader(sourcePdf.getAbsolutePath());
            fos = new FileOutputStream(outFile);
            stamper = new com.itextpdf.text.pdf.PdfStamper(reader, fos);

            com.itextpdf.text.pdf.BaseFont baseFont = loadWatermarkBaseFont();
            com.itextpdf.text.Font font = new com.itextpdf.text.Font(baseFont, 36, com.itextpdf.text.Font.NORMAL, com.itextpdf.text.BaseColor.LIGHT_GRAY);

            com.itextpdf.text.pdf.PdfGState gs = new com.itextpdf.text.pdf.PdfGState();
            gs.setFillOpacity(0.18f);

            int pages = reader.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                com.itextpdf.text.Rectangle pageSize = reader.getPageSizeWithRotation(i);
                float pageWidth = pageSize.getWidth();
                float pageHeight = pageSize.getHeight();

                com.itextpdf.text.pdf.PdfContentByte canvas = stamper.getOverContent(i);
                canvas.saveState();
                canvas.setGState(gs);

                float xStep = 220f;
                float yStep = 180f;
                float angle = 30f;

                for (float y = -pageHeight; y < pageHeight * 2; y += yStep) {
                    for (float x = -pageWidth; x < pageWidth * 2; x += xStep) {
                        com.itextpdf.text.pdf.ColumnText.showTextAligned(
                                canvas,
                                com.itextpdf.text.Element.ALIGN_CENTER,
                                new com.itextpdf.text.Phrase(watermarkText, font),
                                x,
                                y,
                                angle
                        );
                    }
                }

                canvas.restoreState();
            }
        } finally {
            if (stamper != null) {
                try { stamper.close(); } catch (Exception ignored) {}
            }
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
        return outFile;
    }
}
