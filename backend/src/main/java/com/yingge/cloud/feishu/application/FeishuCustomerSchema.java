package com.yingge.cloud.feishu.application;

import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;

import java.util.List;

public final class FeishuCustomerSchema {
    public static final int TEXT = 1;
    public static final int DATETIME = 5;
    public static final int CHECKBOX = 7;

    public static final List<FeishuBitableClient.FieldDefinition> FIELDS = List.of(
            field("customerId", "英格云客户ID", TEXT),
            field("fullName", "姓名", TEXT),
            field("nickname", "昵称", TEXT),
            field("phone", "手机号", TEXT),
            field("wechatId", "微信号", TEXT),
            field("qqId", "QQ号", TEXT),
            field("school", "大学", TEXT),
            field("highSchool", "高中", TEXT),
            field("major", "专业", TEXT),
            field("grade", "年级", TEXT),
            field("city", "城市", TEXT),
            field("sourceLocation", "生源地", TEXT),
            field("identityType", "身份类型", TEXT),
            field("sourceChannel", "来源渠道", TEXT),
            field("quality", "客户等级", TEXT),
            field("intentionLevel", "意向等级", TEXT),
            field("urgencyLevel", "紧迫度", TEXT),
            field("currentStatus", "当前状态", TEXT),
            field("dealState", "成单状态", TEXT),
            field("ownerName", "主负责人", TEXT),
            field("teamName", "所属团队", TEXT),
            field("collaborators", "共享协作者", TEXT),
            field("demandSummary", "需求摘要", TEXT),
            field("focusDirections", "关注方向", TEXT),
            field("tags", "标签", TEXT),
            field("lastContactAt", "最近跟进时间", DATETIME),
            field("nextFollowUpAt", "下次跟进时间", DATETIME),
            field("visibility", "可见范围", TEXT),
            field("privateCustomer", "是否私有", CHECKBOX),
            field("archived", "是否归档", CHECKBOX),
            field("createdAt", "创建时间", DATETIME),
            field("updatedAt", "更新时间", DATETIME),
            field("sourceVersion", "英格云数据版本", TEXT),
            field("syncedAt", "同步时间", DATETIME)
    );

    private FeishuCustomerSchema() {}

    private static FeishuBitableClient.FieldDefinition field(String key, String name, int type) {
        return new FeishuBitableClient.FieldDefinition(key, name, type);
    }
}
