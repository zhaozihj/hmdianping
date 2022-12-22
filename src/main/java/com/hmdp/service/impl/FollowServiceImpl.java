package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    @Autowired
    private IFollowService followService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, boolean follow) {


        //用户id
        Long userId = UserHolder.getUser().getId();

        //这个是存储一个用户关注列表的key，以用户为单位，所以要有userId
        String key="follows:"+userId;

        // 1.判断是关注还是取关
        if(follow){
            Follow follow1=new Follow();
            follow1.setUserId(userId);
            follow1.setFollowUserId(followUserId);
            boolean save = followService.save(follow1);
            if(save){
                 stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }

        }
        else
        {
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if(remove){
                stringRedisTemplate.opsForSet().remove(key,followUserId);
            }

        }
        return Result.ok();
    }

    @Override
    public Result isFllow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        int count=query().eq("follow_user_id",followUserId).eq("user_id",userId).count();
        return Result.ok(count>0);
    }



}
