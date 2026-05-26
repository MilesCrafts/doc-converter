package com.office.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.office.ai")
@org.mybatis.spring.annotation.MapperScan("com.office.ai.mapper")
public class AiToolApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiToolApplication.class, args);
    }
}
