package com.y2a.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Y2A-Auto Java 后端启动类。 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.y2a.server.repository")
public class Y2aApplication {
    public static void main(String[] args) {
        SpringApplication.run(Y2aApplication.class, args);
    }
}
