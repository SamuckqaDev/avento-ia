package com.avento;

import com.avento.config.RedisExecutionProperties;
import com.avento.config.VoiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.avento")
@EntityScan(basePackages = "com.avento")
@EnableJpaRepositories(basePackages = "com.avento")
@EnableConfigurationProperties({RedisExecutionProperties.class, VoiceProperties.class})
@EnableScheduling
public class AventoApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AventoApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
