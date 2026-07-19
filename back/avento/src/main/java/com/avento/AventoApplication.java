package com.avento;

import com.avento.config.RedisExecutionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(RedisExecutionProperties.class)
@EnableScheduling
public class AventoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AventoApplication.class, args);
    }
}
