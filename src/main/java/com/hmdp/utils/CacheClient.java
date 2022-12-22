package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CacheClient {

    @Autowired
private StringRedisTemplate stringRedisTemplate;

    //这个第三个参数是过期时间，第四个是单位
    public void set(String key, Object value, Long time, TimeUnit unit){

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);


    }

    //保存但设置逻辑过期
    public void setWithLogical(String key,Object value,Long time,TimeUnit unit){
       //设置逻辑过期
        RedisData redisData=new RedisData();
        redisData.setData(value);
        //unit.toSeconds是把这个过期时间设置为秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));

    }




}
