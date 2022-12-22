package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;

    @Autowired
    private IBlogService blogService;

    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    //关注和取关
    @PutMapping("/{followUserId}/{isFollow}")
    public Result follow(@PathVariable Long followUserId, @PathVariable("isFollow") boolean follow){

       return  followService.follow(followUserId,follow);

    }

    //判断是否关注了
    @GetMapping("/or/not/{followUserId}")
    public Result follow(@PathVariable Long followUserId){

        return followService.isFllow(followUserId);

    }

    // UserController 根据id查询用户
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    // BlogController  根据id查询博主的探店笔记
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    //查询共同关注
    @GetMapping("/common/{followUserId}")
    public Result followCommons(@PathVariable long followUserId){
        String key1="follows:"+followUserId;
        //用户id
        Long id = UserHolder.getUser().getId();
        String key2="follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect==null||intersect.isEmpty()){
            //无交集
            return Result.ok(Collections.emptyList());
        }

        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> id1 = userService.query().in("id", collect).list();
        List<UserDTO> collect1 = id1.stream().map(s -> {

            return BeanUtil.copyProperties(s,UserDTO.class);

        }).collect(Collectors.toList());
        return Result.ok(collect1);

    }


}
