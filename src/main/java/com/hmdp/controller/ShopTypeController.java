package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public Result queryTypeList() {

        String key="shopTypeList";
        String list = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(list)){

            //hool工具中的转json字符串的方法只能转单个对象，不能转list对象
            //所以这里导入了阿里巴巴啊的fastjson包
            System.out.println(list);
            List<ShopType> parse =   (List<ShopType>)JSON.parse(list);

            return Result.ok(parse);
        }

        List<ShopType> typeList=typeService.queryforList();

        if(typeList==null){
            return Result.fail("查询失败");
        }
        String s = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(key,s);
        return Result.ok(typeList);
    }
}
