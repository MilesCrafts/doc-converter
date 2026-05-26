# Doc Converter

基于 OnlyOffice 与 iText 的文档处理与格式转换服务。

## ✨ 功能特性

- Word 转 PDF
- PDF 转 Word
- Excel 转 PDF
- 图片转 PDF
- PDF 拆分
- PDF 压缩
- 文件上传与下载
- 异步任务处理

---

## 🛠 技术栈

### 后端

- Java 8
- Spring Boot
- MyBatis Plus
- MySQL

### 文档处理

- OnlyOffice
- iText
- LibreOffice

---

## 📂 项目结构

```text
src/main/java/com/office/ai

├── common        # 通用类
├── config        # 配置类
├── controller    # 接口层
├── entity        # 实体类
├── mapper        # 数据访问层
├── service       # 业务层
├── task          # 定时任务
```

---

## ⚙️ 配置说明

复制配置文件：

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
```

然后修改：

- 数据库配置
- JWT 配置
- OnlyOffice 地址
- 文件上传路径

---

## 🚀 启动项目

### 1. 安装依赖

- JDK 8+
- Maven
- MySQL
- OnlyOffice
- LibreOffice

---

### 2. 初始化数据库

执行：

```sql
src/main/resources/schema.sql
```

---

### 3. 启动项目

```bash
mvn spring-boot:run
```

---

## 📌 TODO

- [ ] 文件预览
- [ ] 用户权限管理
- [ ] Docker 部署
- [ ] GitHub Actions 自动部署
- [ ] MinIO 文件存储

---

## 📄 License

MIT License
