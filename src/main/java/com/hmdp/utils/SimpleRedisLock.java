package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock  {

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEY_PREFIX="lock";



    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }


    //线程的标识前缀
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    @Override
    public boolean tryLock(long timeoutSect) {

        //key
        String key=KEY_PREFIX+name;

        //value
        String threadId=ID_PREFIX+Thread.currentThread().getId();

        //获取锁,这个只有key不存在的时候才能成功
        Boolean success=stringRedisTemplate.opsForValue().setIfAbsent(key,threadId,timeoutSect, TimeUnit.SECONDS);

        //直接返回success会有一个自动拆箱过程
        //所以通过这种方式
        return Boolean.TRUE.equals(success);
    }


//初始化lua脚本，static块和static属性类加载的时候就赋值调用了，且staic块中的内容只在类加载的时候执行一次，而普通初始化块中的内容每次创建对象都会执行
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        //指定lua脚本的位置，ClassPathResource里的参数就是在resources文件下找
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //配返回值
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public void unlock(){

        //传key和arvg的参数
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                //这个参数要求是集合，这个方法能生成简单集合
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId());

//调用lua脚本
    }


  /*  @Override
    public void unlock() {

        //获取线程标识
        String threadId=ID_PREFIX+Thread.currentThread().getId();

       String id= stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);

       if(threadId.equals(id)){
           //释放锁
           //删除key
           stringRedisTemplate.delete(KEY_PREFIX+name);

       }

    }*/
}
