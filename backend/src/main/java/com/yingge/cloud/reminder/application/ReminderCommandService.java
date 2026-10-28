package com.yingge.cloud.reminder.application;

import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.reminder.infrastructure.ReminderEntity;
import com.yingge.cloud.reminder.infrastructure.ReminderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ReminderCommandService implements ReminderPort {
    private final ReminderRepository reminders;

    public ReminderCommandService(ReminderRepository reminders) {
        this.reminders = reminders;
    }

    @Override
    @Transactional
    public void replacePending(UUID customerId, UUID interactionId, UUID assignedMembershipId,
                               Instant remindAt, String title, CurrentActor actor) {
        reminders.completeActive(customerId, Instant.now());
        if (remindAt != null) {
            reminders.save(new ReminderEntity(actor.organizationId(), customerId, interactionId,
                    assignedMembershipId, remindAt, title));
        }
    }

    @Override
    @Transactional
    public int transferPending(UUID customerId, UUID previousMembershipId, UUID targetMembershipId,
                               CurrentActor actor) {
        return reminders.transferPending(customerId, previousMembershipId, targetMembershipId, Instant.now());
    }

    @Override
    @Transactional
    public void cancelActive(UUID customerId, CurrentActor actor) {
        reminders.cancelActive(customerId, Instant.now());
    }
}
