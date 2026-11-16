package com.growercare.casesvc.adapters.out.cache;

import com.growercare.casesvc.application.port.PrincipalCacheEvictor;
import com.growercare.security.cache.CachingPrincipalResolver;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Deletes the key written by {@link CachingPrincipalResolver}. Redis errors propagate (retried by the consumer). */
@Component
public class RedisPrincipalCacheEvictor implements PrincipalCacheEvictor {

    private final StringRedisTemplate redis;

    public RedisPrincipalCacheEvictor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void evict(UUID subject) {
        redis.delete(CachingPrincipalResolver.cacheKey(subject));
    }
}
