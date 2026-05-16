package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    //线程池，用来开启线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR=java.util.concurrent.Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //1.将任意Java对象转为JSON字符串并存储到String类型的key中，并可以设置过期时间
    public void set(String key, Object value, long time, TimeUnit Unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, Unit);
    }

    //2.将任意Java对象转为JSON字符串并存储到String类型的key中，并可以设置逻辑过期时间，用于解决缓存击穿
    public void setWithLogicalExpire(String key, Object value, long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //3.根据指定的key查询缓存，并反序列化为指定类型，用缓存空值的方式解决缓存穿透问题
    public <R,ID> R queryWithPathThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        String key=keyPrefix+id;

        //1.从缓存（Redis）中查询数据
        String json=stringRedisTemplate.opsForValue().get(key);

        //2.如果缓存命中直接返回
        if(StrUtil.isNotEmpty(json)){
            return JSONUtil.toBean(json,type);
        }
        //判断命中的是否是空值
        if(json != null){
            //返回错误信息
            return null;
        }

        //3.如果缓存中没有，到数据库查询数据
        R r=dbFallback.apply(id);

        //4.如果数据库没有，把空值写入到Redis中 并设置短的过期时间
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //5.如果数据库有，将数据添加写入Redis中
        this.set(key,r,time,unit);

        //6.返回结果
        return r;
    }

    //4.根据指定的key查询缓存，并反序列化为指定类型，用逻辑过期的方式解决缓存击穿问题

    //获取互斥锁
    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


//逻辑过期解决缓存击穿 【教程原版，无任何修改】
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit)   {
        String key=keyPrefix+id;

        //1.从缓存（Redis）中查询数据
        String json=stringRedisTemplate.opsForValue().get(key);

        //2.如果缓存未命中直接返回
        if(StrUtil.isBlank(json)){
            return null;
        }

        //3.命中，需要先把Json反序列化为对象
        RedisData redisData=JSONUtil.toBean(json,RedisData.class);
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expiretime =redisData.getExpireTime();

        //4.判断是否过期
        if(expiretime.isAfter(LocalDateTime.now())){
            //4.1未过期，直接返回店铺信息
            return r;
        }

        //4.2已过期，需要缓存重建
        //5.缓存重建
        //5.1 拿互斥锁
        String lockKey="lock:shop:"+id;
        boolean islock=tryLock(lockKey);

        //5.2 判断是否拿到锁
        //5.3 成功则开启独立线程，实现缓存重建
        if(islock){
            //用线程池
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                //重建缓存
                try {
                    //查询数据库
                    R r1=dbFallback.apply(id);
                    //写入Reids
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }

        //5.4 失败直接返回过期的商铺信息
        return r;
    }




}
