package com.tianji.learning.controller;

import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.service.impl.ISignRecordServiceImpl;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * ClassName: SignRecordsController
 * Package: com.tianji.learning.controller
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/12/1 3:23
 * @Version 1.0
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("sign-records")
@Api(tags = "签到相关接口")
public class SignRecordsController {

    private final ISignRecordServiceImpl signRecordService;
    @ApiOperation("签到")
    @PostMapping
    public SignResultVO addSignRecords () {
        return signRecordService.addSignRecords();

    }

    @ApiOperation("查询签到记录")
    @GetMapping
    public Byte[] queryMySignRecord () {
        return signRecordService.queryMySignRecord();

    }
}
