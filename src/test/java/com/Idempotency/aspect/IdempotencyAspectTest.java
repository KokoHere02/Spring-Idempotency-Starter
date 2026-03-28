package com.Idempotency.aspect;

import com.Idempotency.annotation.Idempotency;
import com.Idempotency.exception.IdempotencyException;
import com.Idempotency.properties.IdempotencyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@ContextConfiguration(classes = IdempotencyAspectTest.TestConfig.class)
class IdempotencyAspectTest {

    @Autowired
    private DemoService demoService;

    @Autowired
    private RedissonClient redissonClient;

    private final ConcurrentHashMap<String, Long> redisStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        redisStore.clear();
        mockRedissonClient();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/idempotency/test");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRejectDuplicateWhenUsingPlainKey() {
        assertEquals("OK", demoService.plainKey());
        assertThrows(IdempotencyException.class, demoService::plainKey);
    }

    @Test
    void shouldRejectDuplicateWhenUsingSpelKeyWithSameArg() {
        assertEquals("OK", demoService.spelKey("A001"));
        assertThrows(IdempotencyException.class, () -> demoService.spelKey("A001"));
    }

    @Test
    void shouldAllowDifferentSpelArgs() {
        assertEquals("OK", demoService.spelKey("A001"));
        assertEquals("OK", demoService.spelKey("A002"));
    }

    @Test
    void shouldRejectDuplicateWithFallbackKeyWhenArgsSame() {
        assertEquals("OK", demoService.fallbackKey("R01"));
        assertThrows(IdempotencyException.class, () -> demoService.fallbackKey("R01"));
    }

    @Test
    void shouldAllowRetryWhenDelKeyTrueAfterSuccess() {
        assertEquals("OK", demoService.delKeyOnSuccess("PAY-1"));
        assertEquals("OK", demoService.delKeyOnSuccess("PAY-1"));
    }

    @Test
    void shouldAllowRetryWhenDelKeyTrueAfterException() {
        RuntimeException first = assertThrows(RuntimeException.class, () -> demoService.delKeyOnError("ERR-1"));
        assertEquals("boom", first.getMessage());

        RuntimeException second = assertThrows(RuntimeException.class, () -> demoService.delKeyOnError("ERR-1"));
        assertEquals("boom", second.getMessage());
    }

    @Test
    void shouldExpireAfterTtl() throws InterruptedException {
        assertEquals("OK", demoService.ttlKey("TTL-1"));
        assertThrows(IdempotencyException.class, () -> demoService.ttlKey("TTL-1"));

        Thread.sleep(140L);
        assertEquals("OK", demoService.ttlKey("TTL-1"));
    }

    @Test
    void shouldFallbackToPlainKeyWhenSpelInvalid() {
        assertEquals("OK", demoService.invalidSpelKey());
        assertThrows(IdempotencyException.class, demoService::invalidSpelKey);
    }

    @Test
    void shouldKeepCustomMessage() {
        assertEquals("OK", demoService.customMessageKey());
        IdempotencyException ex = assertThrows(IdempotencyException.class, demoService::customMessageKey);
        assertEquals("Do not submit repeatedly", ex.getMessage());
    }

    private void mockRedissonClient() {
        when(redissonClient.getBucket(any(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            RBucket<String> bucket = Mockito.mock(RBucket.class);

            when(bucket.setIfAbsent(eq("1"), any(Duration.class))).thenAnswer(setInvocation -> {
                Duration ttl = setInvocation.getArgument(1);
                long now = System.currentTimeMillis();
                long expireAt = now + Math.max(1L, ttl.toMillis());
                Long oldExpireAt = redisStore.get(key);
                if (oldExpireAt == null || oldExpireAt <= now) {
                    redisStore.put(key, expireAt);
                    return true;
                }
                return false;
            });

            when(bucket.delete()).thenAnswer(deleteInvocation -> redisStore.remove(key) != null);
            return bucket;
        });
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {
        @Bean
        RedissonClient redissonClient() {
            return Mockito.mock(RedissonClient.class);
        }

        @Bean
        IdempotencyAspect idempotencyAspect(RedissonClient redissonClient) {
            IdempotencyProperties properties = new IdempotencyProperties();
            return new IdempotencyAspect(redissonClient, properties);
        }

        @Bean
        DemoService demoService() {
            return new DemoService();
        }
    }

    static class DemoService {

        @Idempotency(key = "fixed-key", expireTime = 5, timeUnit = TimeUnit.SECONDS)
        public String plainKey() {
            return "OK";
        }

        @Idempotency(key = "#p0", expireTime = 5, timeUnit = TimeUnit.SECONDS)
        public String spelKey(String requestId) {
            return "OK";
        }

        @Idempotency(expireTime = 5, timeUnit = TimeUnit.SECONDS)
        public String fallbackKey(String requestId) {
            return "OK";
        }

        @Idempotency(key = "'pay:' + #p0", expireTime = 5, timeUnit = TimeUnit.SECONDS, delKey = true)
        public String delKeyOnSuccess(String requestId) {
            return "OK";
        }

        @Idempotency(key = "'err:' + #p0", expireTime = 5, timeUnit = TimeUnit.SECONDS, delKey = true)
        public String delKeyOnError(String requestId) {
            throw new RuntimeException("boom");
        }

        @Idempotency(key = "'ttl:' + #p0", expireTime = 120, timeUnit = TimeUnit.MILLISECONDS)
        public String ttlKey(String requestId) {
            return "OK";
        }

        @Idempotency(key = "#{", expireTime = 5, timeUnit = TimeUnit.SECONDS)
        public String invalidSpelKey() {
            return "OK";
        }

        @Idempotency(key = "custom-msg", expireTime = 5, timeUnit = TimeUnit.SECONDS, message = "Do not submit repeatedly")
        public String customMessageKey() {
            return "OK";
        }
    }
}

