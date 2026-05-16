package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.AbstractDb;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogByUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void queryBlogByUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog=getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        
        //2.查询blog有关的用户
        queryBlogByUser(blog);

        //3.查询blog是否被点赞
        isBlogLiked(blog);

        //返回blog
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1.获取登录用户=
        UserDTO user=UserHolder.getUser();
        //用户没登陆直接返回
        if(user==null){
            return;
        }
        Long userId = UserHolder.getUser().getId();

        //2.判断当前用户是否点过赞
        String key="blog:liked:"+blog.getId();
        Double isMember=stringRedisTemplate.opsForZSet().score(key,userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember!=null));

    }

    //点赞功能
    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户=
        Long userId = UserHolder.getUser().getId();

        //2.判断当前用户是否点过赞
        String key="blog:liked:"+id;
        Double isMember=stringRedisTemplate.opsForZSet().score(key,userId.toString());
        if(isMember==null){
            //3。如果未点赞，可以点赞
            //3.1更新数据库中点赞字段+1
            boolean isSuccess = update().setSql("liked = liked +1").eq("id", id).update();

            //3.2保存用户到Redis的set集合
            stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
        }


        //4.如果点过赞，取消点赞
        else{
            //4.1更新数据库点赞字段-1
            boolean isSuccess = update().setSql("liked = liked -1").eq("id", id).update();

            //4.2把用户从Redis集合移除
            stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }

        //5.返回结果
        return Result.ok();
    }

    //查询点赞排行榜
    @Override
    public Result queryBlogLikes(Long id) {
        String key=BLOG_LIKED_KEY +id;

        //1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //2.解析出用户id
        List<Long> ids=top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr= StrUtil.join(",",ids);

        //3.根据用户id查询出用户列表
        List<UserDTO> userDtos=userService.query().in("id",ids).last("ORDER BY FIELD(id,"+idStr+")")
                .list()
                .stream()
                .map(user-> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());

        //4.返回
         return Result.ok(userDtos);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());

        // 2.保存探店博文
        boolean isSuccess=save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败");
        }

        // 3.查询笔记作者的所有粉丝 select* from tb_follow where follow_user_id=?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        //4.推送笔记id给所有粉丝
        for( Follow follow:follows){
            //4.1获取粉丝id
            Long userId = follow.getUserId();
            //4.2推送
            String key="feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        //2.查询收件箱 ZREVRANGEBYSCORE key Max Min WITHSCORES LIMIT offset count
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate
                .opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        //3.非空判断
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //4.解析数据：blogId和minTime(时间戳)，offset
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1;
        for(ZSetOperations.TypedTuple<String> tuple:typedTuples){
            //4.1获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //4.2获取分数（时间戳）
            long time=tuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else{
                minTime=time;
                os=1;
            }
        }

        //5.根据id查询blog
        String idstr=StrUtil.join(",",ids);
        List<Blog> blogs=query().in(
                "id",ids).last("ORDER BY FIELD(id,"+idstr+")").list();
        for( Blog blog:blogs){
            //5.1.查询blog有关的用户
            queryBlogByUser(blog);

            //5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        //6.封装并返回
        ScrollResult r=new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);

        return Result.ok(r);
    }
}
