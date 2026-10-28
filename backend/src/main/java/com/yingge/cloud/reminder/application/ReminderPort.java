package com.yingge.cloud.reminder.application;

import com.yingge.cloud.permission.domain.CurrentActor;

import java.time.Instant;
import java.util.UUID;

public interface ReminderPort {
    void replacePending(UUID customerId, UUID interactionId, UUID assignedMembershipId,
                        Instant remindAt, String title, CurrentActor actor);

    int transferPending(UUID customerId, UUID previousMembershipId, UUID targetMembershipId,
                        CurrentActor actor);

    void cancelActive(UUID customerId, CurrentActor actor);
}
