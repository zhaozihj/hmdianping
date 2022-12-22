package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

@Resource
private ISeckillVoucherService seckillVoucherService;

@Resource
private StringRedisTemplate stringRedisTemplate;

@Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;


    //初始化lua脚本，static块和static属性类加载的时候就赋值调用了，且staic块中的内容只在类加载的时候执行一次，而普通初始化块中的内容每次创建对象都会执行
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        //指定lua脚本的位置，ClassPathResource里的参数就是在resources文件下找
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //配返回值
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //专门处理下单操作数据库的线程,这里是获取一个单线程,这个线程是专门下单的
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        //让这个任务在类初始化完毕就会执行
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //这个任务应该在类初始化的时候都执行了，就是一个阻塞效果，一开始拿不到就阻塞，等到消息队列中有了东西，直接获取就可以
    private class VoucherOrderHandler implements Runnable{

        String queueName="stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            //第一个参数是组的名称，第二个是消费者名称  GROUP g1 c1
                            Consumer.from("g1", "c1"),
                            //读取的选项，先empty()创建一个空的，然后count说明读取一个，block指堵塞多久   就是COUNT 1 BLOCK
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //消息队列的名称和读取标识，就是STREAMS s1 >
                            //queueName      streams.order
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    //能得到这种map形式的
                    Map<Object, Object> value = record.getValue();
                    //map转成voucherOrder对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            //最后一个参数是读取标识是零
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    //最后一个参数是指消息队列中这条消息的id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pendding订单异常", e);
                    try{
                        Thread.sleep(20);
                    }catch(Exception ex){
                        ex.printStackTrace();
                    }
                }
            }
        }
    }




    public void handleVoucherOrder(VoucherOrder voucherOrder){

        //现在是多线程了从UserHolder里面是取不到userId了
        //1.获取用户id
        Long userId=voucherOrder.getUserId();

        //尝试获取锁
        //这个参数是存储的key的名称
        RLock anylock = redissonClient.getLock("lock:order:"+userId);
        boolean isLock = anylock.tryLock();


        //如果没有获取到锁，说明已经有自己的请求获取到锁了，直接返回错误信息即可
        if (!isLock) {
           log.error("不能重复下单");
        }
        try {


            //即使return后方法结束，finally还是可以执行完毕
           proxy.createVoucherOrder(voucherOrder);

        }finally{
            //执行完毕之后就会释放锁
            anylock.unlock();
        }

    }

    private IVoucherOrderService proxy;


    //基于消息队列
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //获取用户id
        Long userId = UserHolder.getUser().getId();

        //获取订单id
        Long orderId = redisIdWorker.nextId("order");


        //执行lua脚本
        Long result=stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                //传了一个空集合
                Collections.emptyList(),
                voucherId.toString(),userId.toString(),orderId.toString()
        );
        //2.判断结果是否为0
        //2.1不为零，没有购买资格
        int result1 = result.intValue();
        if(result1!=0){
            return Result.fail(result1==1?"库存不足":"不能重复下单");
        }


        //获取代理对象
        //获取代理对象是基于ThreadLocal的，在handleVoucherOrder方法是在子线程中执行，子线程无法操作threadLocal所以放到父线程中来获取，子线程中使用即可
        proxy = (IVoucherOrderService) AopContext.currentProxy();


        //把订单id返回给客户
        return Result.ok(orderId);


    }






    //基于阻塞队列
   /* @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //获取用户id
        Long userId = UserHolder.getUser().getId();

        //执行lua脚本
        Long result=stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                //传了一个空集合
                Collections.emptyList(),
                voucherId.toString(),userId.toString()
        );
        //2.判断结果是否为0
        //2.1不为零，没有购买资格
        int result1 = result.intValue();
        if(result1!=0){
            return Result.fail(result1==1?"库存不足":"不能重复下单");
        }

        //2.2为零，有购买资格,把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);// 7.3.代金券id
        //代金卷id
        voucherOrder.setVoucherId(voucherId);
        //把封装好的订单信息放到阻塞队列中
        orderTasks.add(voucherOrder);

        //获取代理对象
        //获取代理对象是基于ThreadLocal的，在handleVoucherOrder方法是在子线程中执行，子线程无法操作threadLocal所以放到父线程中来获取，子线程中使用即可
        proxy = (IVoucherOrderService) AopContext.currentProxy();


        //把订单id返回给客户
        return Result.ok(orderId);


    }*/

    //其实在这个方法中我们不必判断一人一单的操作，因为redis中已经弄好了，但保险起见还是搞上了
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = voucherOrder.getUserId();
        //优惠卷id
        Long voucherId=voucherOrder.getVoucherId();
        //订单id
        Long orderId=voucherOrder.getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
           log.error("用户已经购买过了");
        } else {//6，扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                //扣减库存
                log.error("库存不足");
            } else {
                //7.保存订单
                save(voucherOrder);
            }
        }

    }

   /* public Result seckillVoucher(Long voucherId) throws InterruptedException {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();

        //同一个用户需要加锁，就是同一个用户对于这个买票要上锁，不同用户就不用上锁
        //获取锁对象
        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);

        //尝试获取锁
        //这个参数是存储的key的名称
        RLock anylock = redissonClient.getLock("lock:order:"+userId);
        boolean isLock = anylock.tryLock();


        //如果没有获取到锁，说明已经有自己的请求获取到锁了，直接返回错误信息即可
        if (!isLock) {
            return Result.ok("不能重复下单");
        }
        try {

            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //即使return后方法结束，finally还是可以执行完毕
            return proxy.createVoucherOrder(voucherId);

        }finally{
            //执行完毕之后就会释放锁
            anylock.unlock();
        }


    }*/
    /*@Transactional
    public   Result createVoucherOrder(Long voucherId) {
        Result result;

        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            result = Result.fail("用户已经购买过一次！");
        } else {//6，扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                //扣减库存
                result = Result.fail("库存不足！");
            } else {//7.创建订单
                VoucherOrder voucherOrder = new VoucherOrder();// 7.1.订单id
                long orderId = redisIdWorker.nextId("order");
                voucherOrder.setId(orderId);
                voucherOrder.setUserId(userId);// 7.3.代金券id
                voucherOrder.setVoucherId(voucherId);
                save(voucherOrder);
                result = Result.ok(orderId);
            }
        }

        return result;
    }*/


}
