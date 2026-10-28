package com.yingge.cloud.permission.application;

import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    public void requireCustomerWrite(CurrentActor actor) {
        if (!actor.canWriteCustomers()) {
            throw ApiException.forbidden("当前角色只能查看客户");
        }
    }

    public void requireBulkTransfer(CurrentActor actor) {
        if (!actor.canBulkTransfer()) {
            throw ApiException.forbidden("批量导入导出仅限管理员或团队主管");
        }
    }

    public void requireAdmin(CurrentActor actor) {
        if (!actor.isAdmin()) {
            throw ApiException.forbidden("该操作仅限组织管理员");
        }
    }

    public void requireAssistant(CurrentActor actor) {
        if (actor.roles().stream().noneMatch(role -> role.name().matches("ORG_ADMIN|TEAM_MANAGER|MEMBER"))) {
            throw ApiException.forbidden("当前角色不能使用智能助手");
        }
    }
}

