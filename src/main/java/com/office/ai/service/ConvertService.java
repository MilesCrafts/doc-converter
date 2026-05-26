package com.office.ai.service;

import java.io.File;

public interface ConvertService {
    /**
     * 通用转换方法 (支持 PDF/Word/Excel/图片 互转)
     * @param sourceFile 源文件
     * @param targetExt 目标后缀 (如 docx, pdf)
     * @param outputDir 输出目录
     * @return 转换后的文件
     */
    File convert(File sourceFile, String sourceFileId, String targetExt, String outputDir) throws Exception;
}
