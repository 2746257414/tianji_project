package com.tianji.api.client.remark;

import com.tianji.api.client.remark.fallback.RemarkClientFallback;
import io.swagger.annotations.ApiOperation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * ClassName: RemarkClient
 * Package: com.tianji.api.client.remark
 * Description:
 *
 * @Author Mr.Xu
 * @Create 2023/11/30 1:08
 * @Version 1.0
 */
@FeignClient(value = "remark-service",fallbackFactory = RemarkClientFallback.class)  //被调用方的接口
public interface RemarkClient {
    @GetMapping("/likes/list")
    public Set<Long> getLikesStatusByBizIds(@RequestParam("bizIds") List<Long> bizIds);
}
