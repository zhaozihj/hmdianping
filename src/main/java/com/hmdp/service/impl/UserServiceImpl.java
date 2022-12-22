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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
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
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /*
    发送验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {

        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3.符合，生成验证码
        String code= RandomUtil.randomNumbers(6);

        //4.保存验证码到redis
        //第三个参数是key过期时间，第四个参数是时间单位
        //这个LOGIN_CODE_KEY就是一个key的前缀，为了方便封装起来
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY +phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);


        //5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        //6.返回ok
        return Result.ok();
    }

    //这个请求也要对手机号进行校验
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.校验验证码
        //从redis中获取验证码
        Object cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.toString().equals(code)){
            //3.不一致，报错
            return Result.fail("验证码错误");
        }
        //一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();


        //5.判断用户是否存在
        if(user == null){
            //不存在，则创建
            user =  createUserWithPhone(phone);
        }

        UserDTO userDTO=new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        //7.保存用户信息到Redis中
        //7.1随机生成token，作为登录令牌
        //这中uuid中间是没有横杠的
        String token= UUID.randomUUID().toString(true);
        //7.2 将User对象转为Hash存储
        //把User转为map之后它有个属性是long类型，存储的时候，只能是String类型，转换不过来会报错
        //自定义转map，第三个选项是自定义要求，把属性全部改为String类型
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue)->fieldValue.toString()
        ));

        //7.3存储用户信息到redis
        String tokenKey=LOGIN_USER_KEY+token;
        //putall是一次设置多个filed，value，第二个参数用map
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置token有效期,这个有效期要模仿session，session是如果你一直访问它的有效期会一直不变，但如果你不访问的话，30分钟后过期
        //redis也要实现这种功能，而不是不管访不访问30分钟后都删除key,所以在拦截其中要刷新一次key的有效期
        //第二个参数是过期时间，第三个参数是单位
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //把token返回给前端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone){
        //创建用户
        User user=new User();
        //设置手机号
        user.setPhone(phone);
        //设置昵称
        //USER_NICK_NAME_PREFIX其实是SystemConstants中的一个静态属性
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));

        save(user);
        return user;
    }

}
