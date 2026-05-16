package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
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
public class  ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //线程池，用来开启线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR=java.util.concurrent.Executors.newFixedThreadPool(10);

    //根据id查询商户信息
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        Shop shop = cacheClient
                .queryWithPathThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
       // Shop shop=queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop=cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

       // Shop shop=cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    //互斥锁解决缓存击穿
    private Shop queryWithMutex(Long id)   {
        String key=CACHE_SHOP_KEY+id;

        //1.从缓存（Redis）中查询数据
        String shopJson=stringRedisTemplate.opsForValue().get(key);

        //2.如果缓存命中直接返回
        if(StrUtil.isNotEmpty(shopJson)){
            Shop shop= JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        //3.判断命中的是否是空值
        if(shopJson != null){
            //返回错误信息
            return null;
        }

        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey="lock:shop"+id;
        Shop shop= null;
        try {
            boolean isLock=tryLock(lockKey);
            //4.2判断是否获取成功，这里为了代码简洁优雅，我们依旧选择反逻辑写
            if(!isLock){
                //4.3失败则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //5.成功，则到数据库查询数据
            shop = getById(id);

            //6.如果数据库没有，把空值写入到Redis中 并设置短的过期时间
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //7.如果数据库有，将数据添加写入Redis中
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //8.释放互斥锁
            unlock(lockKey);
        }

        //8.释放互斥锁
        unlock(lockKey);

        //9.返回结果
        return shop;
    }

    //逻辑过期解决缓存击穿
    private Shop queryWithLogicalExpire(Long id)   {
        String key=CACHE_SHOP_KEY+id;

        //1.从缓存（Redis）中查询数据
        String shopJson=stringRedisTemplate.opsForValue().get(key);

        //2.如果缓存未命中直接返回
        if(StrUtil.isEmpty(shopJson)){
            return null;
        }

        //3.命中，需要先把Json反序列化为对象
        RedisData redisData=JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expiretime=redisData.getExpireTime();

        //4.判断是否过期
        if(expiretime.isAfter(LocalDateTime.now())){
            //4.1未过期，直接返回店铺信息
            return shop;
        }

        //4.2已过期，需要缓存重建
        //5.缓存重建
        //5.1 拿互斥锁
        String lockKey="lock:shop"+id;
        boolean islock=tryLock(lockKey);

        //5.2 判断是否拿到锁
        //5.3 成功则开启独立线程，实现缓存重建
        if(islock){
            //用线程池
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                //重建缓存
                try {
                    this.saveShop(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }

        //5.4 失败直接返回过期的商铺信息
        return shop;
    }

    //根据id更新店铺信息
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        //1.提取出id
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }

        //2.将数据库中商铺信息修改
        updateById(shop);

        //3.删除对应缓存数据
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return null;
    }

    //获取互斥锁
    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop(Long id,Long expireSeconds){
            //1.查询店铺数据
            Shop shop = getById(id);

            //2.封装逻辑过期时间
            RedisData redisData=new RedisData();
            redisData.setData(shop);
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

            //3.写入Redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }


}
