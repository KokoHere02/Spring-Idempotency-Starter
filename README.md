# Spring Idempotency Starter

基于 `Spring AOP + Redisson` 的幂等防重复提交 Starter。

## 功能

- `@Idempotency` 注解防重复提交
- 支持固定 key / SpEL key
- 支持 TTL 自动过期
- 支持 `delKey=true` 执行后立即释放 key


## 配置

```yaml
idempotency:
  enabled: true
  key-prefix: idp:
```

## 用法

```java
@Idempotency(
    key = "'pay:' + #requestId",
    expireTime = 10,
    timeUnit = TimeUnit.SECONDS,
    message = "请勿重复提交"
)
public String pay(String requestId) {
    return "OK";
}
```

## 注解参数

- `key`：幂等 key（支持 SpEL）
- `expireTime`：过期时长
- `timeUnit`：过期单位
- `message`：重复提交提示
- `delKey`：执行后是否删除 key（默认 `false`）

## 说明

- 命中幂等时抛出 `IdempotencyException`
- `key` 为空时自动生成 key（方法 + 请求 + 参数哈希）
- 仅在存在 `RedissonClient` Bean 且 `idempotency.enabled=true` 时生效
