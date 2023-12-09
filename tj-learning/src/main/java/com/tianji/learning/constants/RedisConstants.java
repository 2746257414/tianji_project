package com.tianji.learning.constants;

/**
 * ClassName: RedisConstants
 * Package: com.tianji.learning.constants
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/12/1 3:31
 * @Version 1.0
 */
public interface RedisConstants {
    // 签到记录的key前缀  sign:id:用户id:年月:
    String SIGN_RECORD_KEY_PREFIX = "sign:id:";

    //积分排行榜的Key的前缀， boards：202312
    String POINTS_BOARD_KEY_PREFIX = "boards:";
}
