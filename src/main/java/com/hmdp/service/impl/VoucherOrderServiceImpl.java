package com.hmdp.service.impl;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    private IVoucherOrderService proxy;

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //线程任务--获取队列里的订单信息并创建订单
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try{
                    //1.获取队列里的订单信息
                    VoucherOrder voucherOrder=orderTasks.take();
                    
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                }catch (Exception e){
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        proxy.createVoucherOrder(voucherOrder);
    }

    //提前设置好脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    @Override
    public Result seckillVoucher(Long voucherId)  {
        //获取用户
        Long userId=UserHolder.getUser().getId();

       //1.执行lua脚本
        Long result=stringRedisTemplate
                .execute(SECKILL_SCRIPT,
                        Collections.emptyList()
                        , voucherId.toString()
                        , userId.toString());

        //2.判断结果是否为0
        int r=result.intValue();


        if(r != 0) {
            //2.1不为0代表没有购买资格
            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单！"); //三目操作符
        }
        //2.2为0，有购买资格，把下单信息保存到阻塞队列
        long orderId=redisIdWorker.nextId("order");
        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);

        //获取代理对象
        proxy=(IVoucherOrderService) AopContext.currentProxy();

        //3.返回订单id
        return Result.ok(orderId);

    }


    @Transactional
    public void createVoucherOrder (VoucherOrder voucherOrder) {
        //5.一人一单
        Long userID =voucherOrder.getUserId();

        //5.1 查询订单
        int count = query().eq("user_id", userID).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2 判断是否存在
        if (count > 0) {
            //用户已经购买过了
            log.error("用户已经购买过了哈哈呵呵嘿嘿");
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            //扣减失败
            log.error("扣减库存失败了哈哈呵呵嘿嘿");
        }

        save(voucherOrder);


    }


}


//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //1.查询优惠券,以下大量用到mybatisplus
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//
//        //3.判断秒杀是否已经结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //已经结束
//            return Result.fail("秒杀已经结束！");
//        }
//
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("库存不足！");
//        }
//
//        Long userId = UserHolder.getUser() .getId();
//
//        //创建锁对象
//       // SimpleRedisLock lock=new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock lock =redissonClient.getLock("lock:order:"+userId);
//        //获取锁
//        //boolean isLock=lock.tryLock(120);
//        boolean isLock=lock.tryLock(1L, TimeUnit.SECONDS);
//        //判断是否获取锁成功
//        if (!isLock) {
//            //获取锁失败，返回错误信息
//            return Result.fail("不允许重复下单！");
//        }
//
//            try {
//                //获取代理对象
//                IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
//                return proxy.createVoucherOrder(voucherId);
//            } finally {
//                lock.unlock();
//            }
//
//
//        }

