package com.chatbi;

import com.chatbi.config.ChatBiProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("com.chatbi.mapper")
@EnableConfigurationProperties(ChatBiProperties.class)
public class ChatBiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatBiApplication.class, args);
    }
}
