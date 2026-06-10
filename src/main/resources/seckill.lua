-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId
-- 2.3.活动开始时间key
local beginKey = 'seckill:begin:' .. voucherId
-- 2.4.活动结束时间key
local endKey = 'seckill:end:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否已预热 get stockKey
local stock = redis.call('get', stockKey)
local beginTime = redis.call('get', beginKey)
local endTime = redis.call('get', endKey)
if(stock == false or beginTime == false or endTime == false) then
    -- 3.2.活动不存在或未完整预热，返回3
    return 3
end
local now = tonumber(redis.call('time')[1])
if(tonumber(beginTime) > now) then
    -- 3.3.活动尚未开始，返回4
    return 4
end
if(tonumber(endTime) <= now) then
    -- 3.4.活动已结束，返回5
    return 5
end
-- 3.2.判断库存是否充足
if(tonumber(stock) <= 0) then
    -- 3.5.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.6.存在，说明是重复下单，返回2
    return 2
end
-- 3.7.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.8.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.9.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
