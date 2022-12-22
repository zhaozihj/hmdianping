package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    //把RedissonClient对象放到ioc容器中
     @Bean
    public RedissonClient redissonClient(){

         //配置
         Config config = new Config();
         config.useSingleServer().setAddress("redis://39.105.5.187:6379").setPassword("zhao1129");
         //创建RedissonClient对象
         return Redisson.create(config);
    }


}
