package com.avento.config;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    private final AsyncTaskExecutor mvcAsyncExecutor;

    @Bean
    static AsyncTaskExecutor mvcAsyncExecutor(
            @Value("${avento.execution.mvc.core-pool-size:4}") int corePoolSize,
            @Value("${avento.execution.mvc.max-pool-size:16}") int maxPoolSize,
            @Value("${avento.execution.mvc.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("avento-mvc-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncExecutor);
        configurer.setDefaultTimeout(Duration.ofHours(1).toMillis());
    }
}
