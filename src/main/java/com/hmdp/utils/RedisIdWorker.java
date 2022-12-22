package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        //ZoneOffset.UTC代表时区
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //BEGIN-TIMESTAMP也是一个时间的时间戳
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        //这里是每一天的订单通过一个key来存储数量
        //key中有yyyy:MM:dd方便统计年月日分别的总订单量
        //increment返回值是从1一直按照步长增加，也就是value的值
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        //时间戳向左移32位，COUNT_BITS就是32，左移32位之后全是零，然后或count，拼接的值就是count的值
        return timestamp << COUNT_BITS | count;
    }


}


