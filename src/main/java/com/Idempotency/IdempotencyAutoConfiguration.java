package com.Idempotency;

import com.Idempotency.aspect.IdempotencyAspect;
import com.Idempotency.properties.IdempotencyProperties;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@ConditionalOnClass(RedissonClient.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean(IdempotencyAspect.class)
    @ConditionalOnProperty(prefix = "idempotency", name = "enabled", havingValue = "true", matchIfMissing = true)
    public IdempotencyAspect idempotencyAspect(RedissonClient redissonClient, IdempotencyProperties properties) {
        return new IdempotencyAspect(redissonClient, properties);
    }
}