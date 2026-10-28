package com.yingge.cloud.business.application;

import com.yingge.cloud.shared.api.ApiException;
import org.springframework.http.HttpStatus;

final class LegacyDealFeatures {
    private LegacyDealFeatures() {}

    static void requireWritable() {
        throw new ApiException(HttpStatus.GONE, "FEATURE_RETIRED",
                "业务周期与成交预测已停用，请使用客户档案中的“成单”申请");
    }

    static boolean retired() {
        return true;
    }
}
