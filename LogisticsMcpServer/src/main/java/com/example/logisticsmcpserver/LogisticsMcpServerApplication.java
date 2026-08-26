package com.example.logisticsmcpserver;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LogisticsMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(LogisticsMcpServerApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.run(args);
    }

}
