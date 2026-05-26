package com.office.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.text.Document;
import com.itextpdf.text.Image;
import com.itextpdf.text.pdf.PdfWriter;
import com.office.ai.service.ConvertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ConvertServiceImpl implements ConvertService {

    private static final int HTTP_TIMEOUT = 120000;

    @org.springframework.beans.factory.annotation.Value("${office-ai.onlyoffice.base-url}")
    private String onlyOfficeBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${office-ai.public-base-url}")
    private String publicBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${office-ai.jwt.secret:}")
    private String jwtSecret;

    @org.springframework.beans.factory.annotation.Value("${office-ai.libreoffice-path:}")
    private String libreofficePath;

    @Override
    public File convert(File sourceFile, String sourceFileId, String targetExt, String outputDir) throws Exception {
        File outDir = new File(outputDir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        String sourceExt = getFileExt(sourceFile.getName());
        String outputExt = targetExt.toLowerCase(Locale.ROOT);
        boolean isImage = isImageExt(sourceExt);

        // 如果是图片转 PDF，直接使用本地 iText 库转换，绕过 OnlyOffice 的兼容性问题
        if (isImage && "pdf".equals(outputExt)) {
            return convertImageToPdfLocal(sourceFile, outputDir, outputExt);
        }

        // 规范化扩展名
        if ("jpeg".equals(sourceExt)) {
            sourceExt = "jpg";
        }

        // Office 转换默认统一走 OnlyOffice（本地可不安装 LibreOffice）

        String key = UUID.randomUUID().toString().replace("-", "");
        String sourceUrl = normalizeBaseUrl(publicBaseUrl) + "/api/v1/file/raw/" + sourceFileId;
        
        // OnlyOffice 对大多数格式，如果 URL 不带正确的后缀，可能会导致探测失败或返回 -7
        if (StrUtil.isNotBlank(sourceExt)) {
            // 确保后缀与 filetype 严格一致 (特别是 jpeg 被转为了 jpg 的情况)
            sourceUrl = sourceUrl + "." + sourceExt;
        }

        Map<String, Object> body = new HashMap<>();
        
        // 其他文档保持异步。
        body.put("async", true); 
        body.put("filetype", sourceExt);
        body.put("outputtype", outputExt);
        body.put("key", key);
        body.put("title", sourceFile.getName());
        body.put("url", sourceUrl); // 确保修改后的 url 放入了 body 中

        if ("pdf".equals(sourceExt) && "docx".equals(outputExt)) {
            // ... (PDF to Word logic remains)
            Map<String, Object> documentRenderer = new HashMap<>();
            documentRenderer.put("textAssociation", "plainParagraph");
            documentRenderer.put("recalculateImage", true);
            documentRenderer.put("imageQuality", 100);
            body.put("documentRenderer", documentRenderer);
        } else if (("xlsx".equals(sourceExt) || "xls".equals(sourceExt) || "csv".equals(sourceExt)) && "pdf".equals(outputExt)) {
            // ... (Excel to PDF logic remains)
            Map<String, Object> spreadsheetLayout = new HashMap<>();
            spreadsheetLayout.put("gridLines", true);
            spreadsheetLayout.put("ignorePrintArea", true);
            spreadsheetLayout.put("orientation", "landscape");
            spreadsheetLayout.put("fitToHeight", 0);
            spreadsheetLayout.put("fitToWidth", 1);
            body.put("spreadsheetLayout", spreadsheetLayout);
        }

        ObjectMapper mapper = new ObjectMapper();
        
        // 支持 ONLYOFFICE JWT 校验
        if (StrUtil.isNotBlank(jwtSecret)) {
            Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
            String token = JWT.create().withPayload(body).sign(algorithm);
            body.put("token", token);
        }

        String requestJson = mapper.writeValueAsString(body);
        Map<String, Object> logBody = new HashMap<>(body);
        if (logBody.containsKey("token")) {
            logBody.put("token", "***");
        }
        String logJson = mapper.writeValueAsString(logBody);
        log.info("开始 OnlyOffice 转换请求: {}, sourceUrl: {}", logJson, sourceUrl);

        JsonNode response = postConvert(mapper, requestJson, key);

        int pollCount = 0;
        while (!response.path("endConvert").asBoolean(false) && pollCount < 300) {
            int error = response.path("error").asInt(0);
            if (error != 0) {
                throw new RuntimeException("OnlyOffice 转换失败，error=" + error + "(" + describeOnlyOfficeError(error) + "), sourceUrl=" + sourceUrl + ", response=" + response.toString());
            }
            pollCount++;
            Thread.sleep(1000);
            response = postConvert(mapper, requestJson, key);
        }

        int finalError = response.path("error").asInt(0);
        if (finalError != 0) {
            log.error("OnlyOffice 转换最终失败: {}, 请求参数: {}, sourceUrl: {}", response.toString(), logJson, sourceUrl);
            throw new RuntimeException("OnlyOffice 转换失败，error=" + finalError + "(" + describeOnlyOfficeError(finalError) + "), sourceUrl=" + sourceUrl + ", response=" + response.toString());
        }

        if (!response.path("endConvert").asBoolean(false)) {
            throw new RuntimeException("OnlyOffice 转换超时, response=" + response.toString());
        }

        String fileUrl = response.path("fileUrl").asText(null);
        if (StrUtil.isBlank(fileUrl)) {
            throw new RuntimeException("OnlyOffice 未返回 fileUrl, response=" + response.toString());
        }

        byte[] convertedBytes = download(fileUrl);

        String baseName = stripExt(sourceFile.getName());
        Path outputPath = Paths.get(outputDir, baseName + "." + outputExt);
        Files.write(outputPath, convertedBytes);

        File resultFile = outputPath.toFile();
        if (!resultFile.isFile()) {
            throw new RuntimeException("找不到转换后的结果文件");
        }
        return resultFile;
    }

    private JsonNode postConvert(ObjectMapper mapper, String requestJson, String key) throws Exception {
        String base = normalizeBaseUrl(onlyOfficeBaseUrl);
        String url = base + "/converter?shardkey=" + key;

        HttpResponse resp = HttpRequest.post(url)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .body(requestJson)
                .execute();

        if (resp.getStatus() == 404) {
            String legacyUrl = base + "/ConvertService.ashx?shardkey=" + key;
            resp = HttpRequest.post(legacyUrl)
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(requestJson)
                    .execute();
        }

        if (resp.getStatus() < 200 || resp.getStatus() >= 300) {
            throw new RuntimeException("OnlyOffice 请求失败，status=" + resp.getStatus() + ", body=" + resp.body());
        }
        return mapper.readTree(resp.body());
    }

    private byte[] download(String url) throws Exception {
        HttpResponse resp = HttpRequest.get(url)
                .timeout(HTTP_TIMEOUT)
                .execute();
        if (resp.getStatus() < 200 || resp.getStatus() >= 300) {
            throw new RuntimeException("OnlyOffice 文件下载失败，status=" + resp.getStatus());
        }
        return resp.bodyBytes();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        if (baseUrl.endsWith("/")) return baseUrl.substring(0, baseUrl.length() - 1);
        return baseUrl;
    }

    private String describeOnlyOfficeError(int error) {
        switch (error) {
            case -4:
                return "下载源文件失败(请检查 office-ai.public-base-url 是否可被 OnlyOffice 访问，/api/v1/file/raw/{fileId} 是否能公网访问)";
            case -7:
                return "文件格式不支持或探测失败(请检查 url 后缀与 filetype 是否一致)";
            case -9:
                return "转换超时";
            default:
                return "未知错误";
        }
    }

    private boolean isImageExt(String ext) {
        if (StrUtil.isBlank(ext)) return false;
        String e = ext.toLowerCase(Locale.ROOT);
        return "jpg".equals(e) || "jpeg".equals(e) || "png".equals(e) || "gif".equals(e) || "bmp".equals(e);
    }

    private boolean isOfficeExt(String ext) {
        if (StrUtil.isBlank(ext)) return false;
        String e = ext.toLowerCase(Locale.ROOT);
        return "doc".equals(e) || "docx".equals(e) || "xls".equals(e) || "xlsx".equals(e) || "ppt".equals(e) || "pptx".equals(e) || "csv".equals(e) || "txt".equals(e);
    }

    private File convertOfficeToPdfLocal(File sourceFile, String outputDir, String outputExt) throws Exception {
        log.info("开始本地 LibreOffice 转 PDF: {}", sourceFile.getName());
        String baseName = stripExt(sourceFile.getName());
        
        ProcessBuilder pb = new ProcessBuilder(
                libreofficePath,
                "--headless",
                "--convert-to", "pdf",
                "--outdir", outputDir,
                sourceFile.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            log.error("LibreOffice 转换失败，exitCode={}", exitCode);
            throw new RuntimeException("LibreOffice 转换失败");
        }
        
        Path outputPath = Paths.get(outputDir, baseName + "." + outputExt);
        File resultFile = outputPath.toFile();
        if (!resultFile.isFile()) {
            throw new RuntimeException("找不到 LibreOffice 转换后的结果文件");
        }
        log.info("本地 LibreOffice 转 PDF 完成: {}", resultFile.getAbsolutePath());
        return resultFile;
    }

    private File convertImageToPdfLocal(File sourceFile, String outputDir, String outputExt) throws Exception {
        log.info("开始本地图片转 PDF: {}", sourceFile.getName());
        String baseName = stripExt(sourceFile.getName());
        Path outputPath = Paths.get(outputDir, baseName + "." + outputExt);
        File resultFile = outputPath.toFile();

        Document document = new Document();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(resultFile);
            PdfWriter.getInstance(document, fos);
            document.open();
            
            Image image = Image.getInstance(sourceFile.getAbsolutePath());
            // 根据图片大小自适应页面大小
            document.setPageSize(new com.itextpdf.text.Rectangle(image.getWidth(), image.getHeight()));
            document.newPage();
            
            image.setAbsolutePosition(0, 0);
            document.add(image);
            log.info("本地图片转 PDF 完成: {}", resultFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("本地图片转 PDF 失败", e);
            throw e;
        } finally {
            // Document.close() 会自动刷新并关闭底层的 OutputStream
            // 如果使用 try-with-resources 自动关闭 FileOutputStream，会导致 Document 再次 close 时抛出 Stream Closed 异常
            if (document != null && document.isOpen()) {
                try {
                    document.close();
                } catch (Exception e) {
                    log.warn("关闭 PDF Document 异常", e);
                }
            } else if (fos != null) {
                // 如果 document 没有成功 open，则手动关闭 fos
                try {
                    fos.close();
                } catch (Exception e) {
                    log.warn("关闭 FileOutputStream 异常", e);
                }
            }
        }
        return resultFile;
    }

    private String getFileExt(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) return "";
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExt(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0) return name;
        return name.substring(0, idx);
    }
}
