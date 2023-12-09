package com.titheima;

import com.tianji.learning.LearningApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ClassName: redisBitMap
 * Package: com.titheima
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/12/1 3:08
 * @Version 1.0
 */
//@SpringBootTest(classes = LearningApplication.class)
public class redisBitMap {

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    public void test() {
//        redisTemplate.opsForValue().setBit();
    }
}
