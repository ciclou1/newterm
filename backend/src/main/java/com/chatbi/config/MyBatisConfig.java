package com.chatbi.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import org.apache.ibatis.type.JdbcType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class MyBatisConfig {

    @Bean
    public ConfigurationCustomizer uuidTypeHandlerCustomizer() {
        return configuration -> {
            UuidTypeHandler handler = new UuidTypeHandler();
            configuration.getTypeHandlerRegistry().register(UUID.class, handler);
            configuration.getTypeHandlerRegistry().register(UUID.class, JdbcType.OTHER, handler);
            configuration.getTypeHandlerRegistry().register(UUID.class, JdbcType.VARCHAR, handler);
        };
    }
}
