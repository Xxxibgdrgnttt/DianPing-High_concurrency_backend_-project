package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sentCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合直接返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3.符合生成验证码，保存验证码到Redis,并设置有效期哦
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //4.发送验证码并返回
        log.info("发送成功" + code);

        //5.返回结果
        return Result.ok("验证码发送成功，请注意查收");
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //1.校验手机号
        String phoneNum = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phoneNum)) {
            return Result.fail("手机号格式错误！");
        }

        //2.从redis获取验证码并检验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phoneNum);
        String code = loginForm.getCode();
        //不一致报错返回
        if (code == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误！");
        }

        //3.根据手机号查询用户,MybatisPlus的简便方法
        User user = query().eq("phone", phoneNum).one();

        //4.判断用户是否存在
        if (user == null) {
            //5.不存在，注册
            user = createNewUserWithPhone(phoneNum);
        }

        //6.保存用户信息到Redis中

        //6.1生成token作为登陆令牌
        String token = UUID.randomUUID().toString(true);

        //6.2将User转为UserDTO，再将UserDTO转为Hash
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((filedName, fieldValue) -> fieldValue.toString()));

        //6.3将token和User存进redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        //6.4别忘了设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);


        //7.返回结果
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime now = LocalDateTime.now();

        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        //6.返回结果
        return Result.ok();


    }

    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime now = LocalDateTime.now();

        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5. 获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        // 6. 循环遍历
        int count = 0;
        while (true) {
            // 6.1. 让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);

    }

    private User createNewUserWithPhone(String phoneNum) {
        //创建新用户对象，并给予一些初始信息
        User user=new User();
        user.setPhone(phoneNum);
        user.setPassword("123456");
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(6));

        //保存用户
        save(user);
        return user;
    }
}
