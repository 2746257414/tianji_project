package com.tianji.learning.service;

import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ClassName: ISignrecordService
 * Package: com.tianji.learning.service
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/12/1 3:25
 * @Version 1.0
 */

public interface ISignRecordService {

    SignResultVO addSignRecords();

    Byte[] queryMySignRecord();
}
