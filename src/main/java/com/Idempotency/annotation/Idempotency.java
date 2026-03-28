package com.Idempotency.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotency {

    /**
     * 幂等操作唯一标识（可支持 SpEL）。
     *
     * @return 幂等 key
     */
    String key() default "";

    /**
     * 过期时间，避免幂等 key 长时间占用。
     *
     * @return 过期时长
     */
    long expireTime() default 1;

    /**
     * 过期时间单位。
     *
     * @return 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 命中幂等限制时的提示信息。
     *
     * @return 提示信息
     */
    String message() default "重复操作，请稍后再试";

    /**
     * 业务执行结束后是否删除 key。
     * false: 直到过期自动释放；true: 执行后立即释放。
     *
     * @return 是否删除 key
     */
    boolean delKey() default false;
}
