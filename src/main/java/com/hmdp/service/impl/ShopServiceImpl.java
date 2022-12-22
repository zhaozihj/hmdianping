package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    //这个方法就是调用封装好的api，解决缓存击穿和缓存穿透问题的查询方法的api
    @Override
    public Result queryById(Long id) {

    //解决缓存穿透的封装
    //Shop shop=queryWithPassThrough(id);

      //互斥锁解决缓存击穿
       // Shop shop=queryWithMutext(id);
        Shop shop=queryWithPassThrough(id);
        if(shop==null){
            return Result.fail("店铺不存在!");
        }

        //返回
        return Result.ok(shop);

    }



    //定义一个10个线程的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);


    //这个是缓存穿透的解决的方法的代码，逻辑过期方法
    public Shop queryWithLogical(Long id) {
        //1，查询redis数据库
        String key=CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.未命中缓存或者返回是空字符串直接返回空
        if(StrUtil.isBlank(shopJson)){
            return null;
        }


        //3.命中了缓存，把字符串转换为RedisData对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);

        //4.获取shop对象
        //获取的data是Object类型，可以转为JSONObject类型作为第一个参数
        Shop shop1 = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //取出过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
                      //未过期
            return shop1;
        }

        //6.过期了，就要去重建缓存
        String lockkey= LOCK_SHOP_KEY +id;
        boolean trylock = trylock(lockkey);

        //7.判断是否拿到锁
        if(trylock){
            //8.拿到锁了
            //开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{

                try {
                    //重建缓存
                    //正常是30分钟，但是为了测试这里先写30秒
                    saveShop2Redis(id, 30L);
                }
                catch (Exception e){
                    throw new RuntimeException(e);
                }
                finally {
                    //释放锁
                    unlock(lockkey);
                }
            });
        }
        //没拿到锁

        //返回过期商铺信息
        return shop1;



    }

    public  void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);

        //模拟重建缓存延迟
        Thread.sleep(200);

        //封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);

        //这里是设置逻辑过期时间，是当前时间再加上参数的秒数
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));


    }





    //互斥锁解决缓存击穿问题
    public Shop queryWithMutext(Long id) {

        Shop shop;

            //查询redis数据库
            String key = CACHE_SHOP_KEY + id;
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                //如果在redis中查询到数据
                 shop = JSONUtil.toBean(shopJson, Shop.class);
                //直接返回
                return shop;
            }

            //命中的是空字符串,因为剩下的不是null就是空字符串
            if (shopJson != null) {

                return null;
            }


            //redis没有命中的时候，先获取锁
            String lockkey = "lock:shop:" + id;
            boolean trylock = trylock(lockkey);
try{
            //没获取到锁,则休眠重试
            if (!trylock) {
                Thread.sleep(10);
                queryWithMutext(id);
            }


            //获取到锁了，根据id查询数据库

            //模拟重建的延时
           Thread.sleep(200);


            //对缓存二次检测

            String shopJson1 = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson1)) {
                //如果在redis中查询到数据
                shop = JSONUtil.toBean(shopJson1, Shop.class);
                //直接返回
                return shop;
            }

            //命中的是空字符串,因为剩下的不是null就是空字符串
            if (shopJson1 != null) {

                return null;
            }


            //如果redis中没有查到，去数据库中查询
            shop = this.getById(id);
            //没有查到
            if (shop == null) {
                //将空值写入redis,并且空值的过期时间设置应该短一点
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }

            //java对象转json字符串
            String jsonStr = JSONUtil.toJsonStr(shop);

            //如果查询到，先保存到缓存中
            //添加过期时间，兜底操作,第三个参数是过期时间
            stringRedisTemplate.opsForValue().set(key, jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        }
        catch (Exception e){
            throw new RuntimeException();
        }
        finally {
            unlock(lockkey);
        }
        //返回
        return shop;

    }



    //获取锁代码
    private boolean trylock(String key){
        //setifpresent就是setnx
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        //直接返回flag它会进行拆箱，因为boolean和Boolean的关系，它有可能会出现空指针情况
        return BooleanUtil.isTrue(flag);
    }


    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }






    //这个是缓存穿透的解决的方法的代码，互斥锁方法
    public Shop queryWithPassThrough(Long id) {
        //查询redis数据库
        String key=CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            //如果在redis中查询到数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            //直接返回
            return shop;
        }

        //命中的是空字符串,因为剩下的不是null就是空字符串
        if(shopJson!=null){
            return null;
        }



        //如果redis中没有查到，去数据库中查询
        Shop shop = this.getById(id);
        //没有查到
        if(shop==null){
            //将空值写入redis,并且空值的过期时间设置应该短一点
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);

            return null;
        }

        //java对象转json字符串
        String jsonStr = JSONUtil.toJsonStr(shop);

        //如果查询到，先保存到缓存中
        //添加过期时间，兜底操作,第三个参数是过期时间
        stringRedisTemplate.opsForValue().set(key,jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //返回
        return shop;

    }

    @Override
    public Result update(Shop shop) {

        Long id = shop.getId();

        if(id==null){
            return Result.fail("商品店铺不能没有id");
        }
        //前面讨论过是先更新数据库再删除缓存
        //更新数据库
        this.updateById(shop);

        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }





}
