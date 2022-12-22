-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
--..是拼接字符串的意思
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
--这个key是用来存储买过这个优惠卷的用户id的
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
--用get得到的是字符串，tonumber是给他转成数字类型
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
--判断userId是否是这个set集合中的成员
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
--把自己的id保存在set集合中
redis.call('sadd', orderKey, userId)

--3.6发送消息到队列当中,XADD stream.orders * k1 v1 k2 v2
--这里用最后一个参数叫id是因为voucherorder类中那个属性叫id，以后方便用
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId);
return 0