package com.titheima;

import com.tianji.api.client.remark.RemarkClient;
import com.tianji.learning.LearningApplication;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.AutoConfigureDataJdbc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

/**
 * ClassName: RemarkClientFeignTest
 * Package: com.titheima
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/11/30 1:21
 * @Version 1.0
 */
@SpringBootTest(classes = LearningApplication.class)
public class RemarkClientFeignTest {
    @Autowired
    RemarkClient remarkClient;

    @Test
    public void test() {
        Set<Long> set = remarkClient.getLikesStatusByBizIds(Lists.list(16123L, 156L));
        System.out.println("set = " + set);
    }
}
