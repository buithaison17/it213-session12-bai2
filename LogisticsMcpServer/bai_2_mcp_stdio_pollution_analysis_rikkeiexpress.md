# BÁO CÁO KỸ THUẬT: DÒ LỖI & TỐI ƯU CẤU HÌNH — XỬ LÝ BẪY Ô NHIỄM STANDARD OUTPUT (STDIO POLLUTION)

**Dự án:** RikkeiExpress MCP Server Integration  
**Học phần:** Session 12 — Kiến trúc MCP (Model Context Protocol)  
**Bài tập:** Bài 2 — Dò Lỗi & Tối Ưu Cấu Hình: Xử Lý Bẫy Ô Nhiễm Standard Output (Stdio Pollution)  

---

## 1. PHÂN TÍCH NGUYÊN NHÂN KỸ THUẬT (ROOT CAUSE ANALYSIS)

### Cơ chế giao tiếp Stdio Transport trong Model Context Protocol
Khi tích hợp MCP Server chạy ở chế độ **Stdio Transport**, MCP Client (Claude Desktop / Cursor / AI Agent) khởi tạo tiến trình con (`spawn sub-process`) và thiết lập kênh truyền thông liên tiến trình (IPC - Inter-Process Communication) chuẩn:
* **`stdin` (Standard Input - File Descriptor 0):** Kênh để MCP Client đẩy dữ liệu yêu cầu JSON-RPC sang Server.
* **`stdout` (Standard Output - File Descriptor 1):** Kênh độc quyền để MCP Server trả về các phản hồi JSON-RPC cho Client.

Theo quy chuẩn kỹ thuật của MCP:
> Mọi ký tự xuất hiện trên luồng `stdout` **bắt buộc phải là payload JSON-RPC 2.0 hợp lệ**, kết thúc bằng ký tự xuống dòng (`
`).

### Nguyên nhân gây lỗi tê liệt giao thức
1. **Ô nhiễm luồng dữ liệu (Stdio Pollution):**  
   Khi Spring Boot khởi động mặc định, framework sẽ in chữ nghệ thuật ASCII Banner (`.   ____          _            __ _ _...`) cùng hàng loạt dòng log khởi tạo của hệ thống và Spring Beans ra trực tiếp `System.out` (`stdout`).
2. **Crash bộ phân tích cú pháp (JSON-RPC Parser Failure):**  
   MCP Client liên tục quét luồng `stdout` và kỳ vọng ký tự mở đầu của một đối tượng JSON (`{`). Khi gặp chuỗi text tự do từ Banner hoặc Log của Spring Boot:
   ```text
   [Error] Failed to parse JSON-RPC message from MCP Server.
   Unexpected token '  .   ____          _            __ _ _', line 1, column 3.
   ```
   Parser lập tức gặp ngoại lệ nghiêm trọng và ngắt kết nối (`Connection terminated unexpectedly`), khiến toàn bộ quá trình khởi tạo handshake thất bại hoàn toàn.

---

## 2. BẢN VÁ MÃ NGUỒN JAVA (`LogisticsMcpServerApplication.java`)

Thay thế lệnh gọi `SpringApplication.run(...)` tĩnh mặc định bằng việc khởi tạo đối tượng `SpringApplication` và vô hiệu hóa chế độ vẽ Banner qua `Banner.Mode.OFF`.

```java
package com.rikkei.mcp;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LogisticsMcpServerApplication {

    public static void main(String[] args) {
        // Khởi tạo instance SpringApplication tùy chỉnh
        SpringApplication app = new SpringApplication(LogisticsMcpServerApplication.class);
        
        // BẢN VÁ: Tắt hoàn toàn Banner ASCII khi ứng dụng khởi động
        app.setBannerMode(Banner.Mode.OFF);
        
        // Chạy ứng dụng Spring Boot
        app.run(args);
    }
}
```

---

## 3. BẢN VÁ CẤU HÌNH LOGBACK (`src/main/resources/logback-spring.xml`)

Tạo file cấu hình Logback chuyển hướng toàn bộ luồng xuất log (Console Appender) sang Standard Error (`System.err`) thay vì Standard Output (`System.out`).

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Định dạng log chuẩn cho hệ thống -->
    <property name="LOG_PATTERN" 
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n" />

    <!-- Cấu hình ConsoleAppender ghi dữ liệu vào Standard Error (System.err) -->
    <appender name="STDERR_APPENDER" class="ch.qos.logback.core.ConsoleAppender">
        <!-- ĐIỂM QUAN TRỌNG: Thiết lập target thành System.err -->
        <target>System.err</target>
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- Thiết lập cấp độ log cho các package -->
    <logger name="com.rikkei.mcp" level="DEBUG" />
    <logger name="org.springframework" level="INFO" />
    <logger name="io.modelcontextprotocol" level="DEBUG" />

    <!-- Root Logger đẩy toàn bộ log về STDERR_APPENDER -->
    <root level="INFO">
        <appender-ref ref="STDERR_APPENDER" />
    </root>
</configuration>
```

---

## 4. GIẢI THÍCH CƠ CHẾ KỸ THUẬT & TÍNH KHẢ DỤNG CỦA LOGS

### Tách biệt ranh giới ở tầng Hệ điều hành (OS File Descriptors)
Hệ điều hành phân tách luồng xuất chuẩn thành 2 kênh độc lập:
* **`stdout` (FD 1 - Standard Output):** Dành riêng cho dữ liệu kết quả nghiệp vụ hoặc giao thức dữ liệu máy đọc được (JSON-RPC).
* **`stderr` (FD 2 - Standard Error):** Dành riêng cho dữ liệu chẩn đoán, thông tin tiến trình, log hệ thống và thông báo lỗi.

```text
               ┌──────────────────────────────┐
               │    Claude Desktop / Client   │
               └──────────────┬───────────────┘
                              │ stdin (FD 0: Request JSON-RPC)
                              ▼
               ┌──────────────────────────────┐
               │ LogisticsMcpServer (Spring)  │
               └──────┬────────────────┬──────┘
  stdout (FD 1)       │                │       stderr (FD 2)
  (CHỈ JSON-RPC)      │                │       (Spring & App Logs)
                      ▼                ▼
         ┌──────────────────┐    ┌───────────────────────────┐
         │ JSON-RPC Parser  │    │ Claude Desktop Log Viewer │
         │ (Giao tiếp chuẩn)│    │ (Theo dõi & gỡ lỗi)       │
         └──────────────────┘    └───────────────────────────┘
```

### Tại sao vẫn theo dõi được log mà không làm hỏng JSON-RPC?
1. **Không can thiệp kênh truyền JSON-RPC:** Khi chuyển toàn bộ log sang `System.err`, luồng `System.out` đạt trạng thái tinh khiết 100% (chỉ chứa các block JSON do thư viện MCP sinh ra).
2. **Khả năng quan sát (Observability):** Hầu hết các MCP Client tiêu chuẩn (bao gồm Claude Desktop và Cursor) đều bắt luồng `stderr` của tiến trình con và ghi vào file log riêng biệt (ví dụ: `mcp-server-logistics.log` hoặc Developer Console của Claude Desktop). Kỹ sư hoàn toàn có thể mở log để xem SQL query, stack trace, và lifecycle sự kiện theo thời gian thực mà hệ thống vẫn chạy ổn định.
