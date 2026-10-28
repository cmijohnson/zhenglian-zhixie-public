package com.yingge.cloud.deal.application;

import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.permission.domain.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

@Component
public class CustomerDealStateProjector {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public CustomerDealStateProjector(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    public CustomerDtos.Response project(CustomerEntity customer, CurrentActor actor, boolean profileEditable) {
        State state = jdbc.query("""
                select status,requested_at from customer_deal_requests
                where organization_id=? and customer_id=? and status in ('APPROVED','PENDING')
                order by case status when 'APPROVED' then 0 else 1 end, requested_at desc limit 1
                """, rs -> rs.next()
                ? new State("APPROVED".equals(rs.getString("status")) ? "WON" : "PENDING",
                rs.getTimestamp("requested_at").toInstant()) : new State("NONE", null),
                actor.organizationId(), customer.getId());
        boolean canRequest = "NONE".equals(state.value()) && customer.getArchivedAt() == null
                && actor.canWriteCustomers() && profileEditable;
        return CustomerDtos.Response.from(customer).withDealState(state.value(), canRequest, state.requestedAt());
    }

    /**
     * Projects a page of customers with one set-based state query. Keeping this separate from the
     * single-customer path avoids one deal-state query per row on the dense admin customer table.
     */
    public List<CustomerDtos.Response> projectAll(List<CustomerEntity> customers, CurrentActor actor,
                                                   Predicate<CustomerEntity> profileEditable) {
        if (customers.isEmpty()) return List.of();
        Map<UUID, State> states = new LinkedHashMap<>();
        namedJdbc.query("""
                select distinct on (customer_id) customer_id,status,requested_at
                from customer_deal_requests
                where organization_id=:organizationId and customer_id in (:customerIds)
                  and status in ('APPROVED','PENDING')
                order by customer_id,case status when 'APPROVED' then 0 else 1 end,requested_at desc
                """, new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId())
                .addValue("customerIds", customers.stream().map(CustomerEntity::getId).toList()), rs -> {
            states.put(rs.getObject("customer_id", UUID.class),
                    new State("APPROVED".equals(rs.getString("status")) ? "WON" : "PENDING",
                            rs.getTimestamp("requested_at").toInstant()));
        });
        return customers.stream().map(customer -> {
            State state = states.getOrDefault(customer.getId(), new State("NONE", null));
            boolean canRequest = "NONE".equals(state.value()) && customer.getArchivedAt() == null
                    && actor.canWriteCustomers() && profileEditable.test(customer);
            return CustomerDtos.Response.from(customer)
                    .withDealState(state.value(), canRequest, state.requestedAt());
        }).toList();
    }

    private record State(String value, Instant requestedAt) {}
}
