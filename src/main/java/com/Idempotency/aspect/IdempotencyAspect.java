package com.Idempotency.aspect;

import com.Idempotency.annotation.Idempotency;
import com.Idempotency.exception.IdempotencyException;
import com.Idempotency.properties.IdempotencyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

@Aspect
public class IdempotencyAspect {

    private static final String LOCK_KEY_ATTR = "IDEMPOTENCY_LOCK_KEY";
    private static final String LOCK_VALUE = "1";
    private static final String KEY_SEPARATOR = ":";
    private static final String METHOD_SEPARATOR = "#";
    private static final String EMPTY = "";
    private static final String SPEL_PREFIX = "#";
    private static final String SHA_256 = "SHA-256";
    private static final String SHA_256_NOT_AVAILABLE = "SHA-256 not available";

    private final RedissonClient redissonClient;
    private final IdempotencyProperties properties;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    public IdempotencyAspect(RedissonClient redissonClient, IdempotencyProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Before("@annotation(idempotency)")
    public void before(JoinPoint joinPoint, Idempotency idempotency) {
        ServletRequestAttributes requestAttributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }

        HttpServletRequest request = requestAttributes.getRequest();
        String finalKey = buildFinalKey(joinPoint, idempotency, request);

        long ttlMillis = Math.max(1L, idempotency.timeUnit().toMillis(idempotency.expireTime()));
        Duration ttl = Duration.ofMillis(ttlMillis);
        RBucket<String> bucket = redissonClient.getBucket(finalKey);
        boolean acquired = bucket.setIfAbsent(LOCK_VALUE, ttl);
        if (!acquired) {
            throw new IdempotencyException(idempotency.message());
        }

        request.setAttribute(LOCK_KEY_ATTR, finalKey);
    }

    @AfterReturning("@annotation(idempotency)")
    public void afterReturning(Idempotency idempotency) {
        releaseIfNeeded(idempotency);
    }

    @AfterThrowing("@annotation(idempotency)")
    public void afterThrowing(Idempotency idempotency) {
        releaseIfNeeded(idempotency);
    }

    private void releaseIfNeeded(Idempotency idempotency) {
        if (!idempotency.delKey()) {
            return;
        }
        ServletRequestAttributes requestAttributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (requestAttributes == null) {
            return;
        }
        Object keyObj = requestAttributes.getRequest().getAttribute(LOCK_KEY_ATTR);
        if (keyObj instanceof String key) {
            redissonClient.getBucket(key).delete();
        }
    }

    private String buildFinalKey(JoinPoint joinPoint, Idempotency idempotency, HttpServletRequest request) {
        String userKey = resolveUserKey(joinPoint, idempotency);
        if (StringUtils.hasText(userKey)) {
            return properties.getKeyPrefix() + userKey;
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodId = method.getDeclaringClass().getName() + METHOD_SEPARATOR + method.getName();
        String requestMeta = request.getMethod() + KEY_SEPARATOR + request.getRequestURI();
        String query = request.getQueryString() == null ? EMPTY : request.getQueryString();
        String argsHash = sha256Hex(Arrays.deepToString(joinPoint.getArgs()));

        return properties.getKeyPrefix() + methodId + KEY_SEPARATOR + requestMeta + KEY_SEPARATOR + query + KEY_SEPARATOR + argsHash;
    }

    private String resolveUserKey(JoinPoint joinPoint, Idempotency idempotency) {
        String key = idempotency.key();
        if (!StringUtils.hasText(key)) {
            return EMPTY;
        }
        key = key.trim();
        if (!key.contains(SPEL_PREFIX)) {
            return key;
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        StandardEvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
        }

        try {
            Expression expression = parser.parseExpression(key);
            Object value = expression.getValue(context);
            return value == null ? EMPTY : String.valueOf(value);
        } catch (ExpressionException ex) {
            return key;
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(SHA_256_NOT_AVAILABLE, e);
        }
    }
}