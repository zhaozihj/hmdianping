package com.hmdp.utils;

public interface ILock {

    //获取锁，参数是过期时间
    boolean tryLock(long timeoutSect);

    //释放锁
     void unlock();

}

