package com.hmdp.ai.infrastructure.observability;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AiHealthConfiguration {
    @Bean public HealthIndicator businessRedisHealthIndicator(@Qualifier("businessRedissonClient") RedissonClient client){return redis(client,"businessRedis");}
    @Bean public HealthIndicator memoryRedisHealthIndicator(@Qualifier("memoryRedissonClient") RedissonClient client){return redis(client,"memoryRedis");}
    @Bean public HealthIndicator vectorRedisHealthIndicator(@Qualifier("vectorRedisConnectionFactory") LettuceConnectionFactory factory){return ()->{try(org.springframework.data.redis.connection.RedisConnection c=factory.getConnection()){String pong=c.ping();return Health.up().withDetail("service","redisStack").withDetail("ping",pong).build();}catch(Exception e){return down("redisStack",e);}};}
    @Bean public HealthIndicator mysqlHealthIndicator(JdbcTemplate jdbc){return ()->{try{Integer one=jdbc.queryForObject("SELECT 1",Integer.class);return Health.up().withDetail("service","mysql").withDetail("probe",one).build();}catch(Exception e){return down("mysql",e);}};}
    @Bean public HealthIndicator minioHealthIndicator(MinioClient client,@Value("${minio.bucket:hmdp-ai}") String bucket){return ()->{try{boolean exists=client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());return exists?Health.up().withDetail("service","minio").build():Health.down().withDetail("service","minio").withDetail("errorCode","BUCKET_NOT_FOUND").build();}catch(Exception e){return down("minio",e);}};}
    @Bean public HealthIndicator modelServiceHealthIndicator(RestTemplate rest,@Value("${langchain4j.open-ai.chat-model.base-url:}") String baseUrl,@Value("${langchain4j.open-ai.chat-model.api-key:}") String apiKey){return ()->{if(baseUrl==null||baseUrl.trim().isEmpty()||apiKey==null||apiKey.trim().isEmpty())return Health.unknown().withDetail("service","model").withDetail("errorCode","PROVIDER_NOT_CONFIGURED").build();try{HttpHeaders h=new HttpHeaders();h.setBearerAuth(apiKey);ResponseEntity<String> response=rest.exchange(baseUrl+"/models",HttpMethod.GET,new HttpEntity<>(h),String.class);return Health.up().withDetail("service","model").withDetail("status",response.getStatusCodeValue()).build();}catch(Exception e){return down("model",e);}};}
    private HealthIndicator redis(RedissonClient client,String name){return ()->{try{client.getBucket("hmdp:health:"+name).isExists();return Health.up().withDetail("service",name).build();}catch(Exception e){return down(name,e);}};}
    private static Health down(String service,Exception e){return Health.down().withDetail("service",service).withDetail("errorCode","DEPENDENCY_UNAVAILABLE").withDetail("errorType",e.getClass().getSimpleName()).build();}
}
