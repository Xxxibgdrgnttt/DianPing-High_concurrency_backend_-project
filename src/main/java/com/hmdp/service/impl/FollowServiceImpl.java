package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    UserServiceImpl userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollowed) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();

        //2.判断是关注还是取关
        //关注，新增数据
        if (isFollowed) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess=save(follow);
            if(isSuccess){ stringRedisTemplate.opsForSet().add("follow:"+userId, followUserId.toString());}

        } else {//取关，删除数据
            boolean isSuccess=remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if(isSuccess){ stringRedisTemplate.opsForSet().remove("follow:"+userId, followUserId.toString());}
        }
        return Result.ok();

    }


    @Override
    public Result isFollowed(Long followUserId) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();

        //2.查询是否关注
        Integer count=query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        //3.判断
        return Result.ok(count>0);
    }

    @Override
    public Result followCommon(Long followUserId) {

        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2. 求交集
        String key2 = "follows:" + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        //3，把交集解析为用户id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.根据用户id集合查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //5.返回
        return Result.ok(users);
    }
}
