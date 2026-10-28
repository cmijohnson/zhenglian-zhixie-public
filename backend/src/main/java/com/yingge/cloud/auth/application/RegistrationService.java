package com.yingge.cloud.auth.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.infrastructure.RegistrationCodeEntity;
import com.yingge.cloud.auth.infrastructure.RegistrationCodeRepository;
import com.yingge.cloud.organization.infrastructure.MembershipEntity;
import com.yingge.cloud.organization.infrastructure.MembershipRepository;
import com.yingge.cloud.organization.infrastructure.TeamEntity;
import com.yingge.cloud.organization.infrastructure.TeamRepository;
import com.yingge.cloud.organization.infrastructure.UserAccountEntity;
import com.yingge.cloud.organization.infrastructure.UserAccountRepository;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.task.application.TaskService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class RegistrationService {
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final UUID MEMBER_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final RegistrationCodeRepository codes;
    private final UserAccountRepository users;
    private final MembershipRepository memberships;
    private final TeamRepository teams;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;
    private final TaskService tasks;

    public RegistrationService(RegistrationCodeRepository codes, UserAccountRepository users,
                               MembershipRepository memberships, TeamRepository teams, PasswordEncoder passwordEncoder,
                               JdbcTemplate jdbc, CurrentActorProvider actors, PermissionService permissions,
                               AuditService audit, TaskService tasks) {
        this.codes = codes;
        this.users = users;
        this.memberships = memberships;
        this.teams = teams;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
        this.tasks = tasks;
    }

    @Transactional
    public RegistrationResponse register(RegisterRequest request) {
        String username = required(request.username(), "USERNAME_REQUIRED", "用户名不能为空");
        String realName = required(request.realName(), "REAL_NAME_REQUIRED", "真实姓名不能为空");
        String password = request.password();
        if (password == null || password.isBlank()) {
            throw ApiException.badRequest("PASSWORD_REQUIRED", "密码不能为空");
        }
        if (username.length() > 100) throw ApiException.badRequest("USERNAME_TOO_LONG", "用户名不能超过 100 个字符");
        if (realName.length() > 120) throw ApiException.badRequest("REAL_NAME_TOO_LONG", "真实姓名不能超过 120 个字符");
        if (password.length() > 200) throw ApiException.badRequest("PASSWORD_TOO_LONG", "密码不能超过 200 个字符");

        Instant now = Instant.now();
        RegistrationCodeEntity code = codes.findForRegistration(hash(normalizeCode(request.registrationCode())))
                .orElseThrow(() -> ApiException.badRequest("REGISTRATION_CODE_INVALID", "注册码无效"));
        validateCode(code, now);
        if (request.teamId() == null) throw ApiException.badRequest("TEAM_REQUIRED", "请选择要加入的团队");
        TeamEntity team = teams.findByIdAndOrganizationId(request.teamId(), code.getOrganizationId())
                .filter(TeamEntity::isActive)
                .orElseThrow(() -> ApiException.badRequest("TEAM_NOT_FOUND", "所选团队不存在或已停用"));
        if (users.findByUsernameIgnoreCase(username).isPresent()) {
            throw ApiException.conflict("USERNAME_EXISTS", "用户名已经存在");
        }

        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        try {
            users.saveAndFlush(new UserAccountEntity(userId, username, passwordEncoder.encode(password), realName, false, now));
            memberships.saveAndFlush(new MembershipEntity(membershipId, code.getOrganizationId(), userId, now));
            jdbc.update("insert into membership_roles (membership_id, role_id) values (?, ?)", membershipId, MEMBER_ROLE_ID);
            jdbc.update("insert into team_members (team_id, membership_id, is_manager) values (?, ?, false)",
                    team.getId(), membershipId);
            tasks.assignActiveMandatoryTasks(code.getOrganizationId(), membershipId);
            code.consume(now);
            codes.save(code);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("USERNAME_EXISTS", "用户名已经存在");
        }

        CurrentActor registered = new CurrentActor(
                userId, membershipId, code.getOrganizationId(), username, realName,
                Set.of(RoleCode.MEMBER), Set.of(team.getId()), Set.of());
        audit.record(registered, "USER_REGISTERED", "MEMBERSHIP", membershipId.toString(), "SUCCESS",
                Map.of("registrationCodeId", code.getId().toString(), "role", RoleCode.MEMBER.name(),
                        "teamId", team.getId().toString()));
        return new RegistrationResponse(userId, membershipId, username, realName, RoleCode.MEMBER.name(),
                team.getId(), team.getName());
    }

    @Transactional(readOnly = true)
    public RegistrationOptions registrationOptions(String registrationCode) {
        Instant now = Instant.now();
        RegistrationCodeEntity code = codes.findByCodeHash(hash(normalizeCode(registrationCode)))
                .orElseThrow(() -> ApiException.badRequest("REGISTRATION_CODE_INVALID", "注册码无效"));
        validateCode(code, now);
        List<TeamOption> options = teams.findByOrganizationIdAndActiveTrueOrderByNameAsc(code.getOrganizationId()).stream()
                .map(team -> new TeamOption(team.getId(), team.getName())).toList();
        if (options.isEmpty()) throw ApiException.conflict("NO_ACTIVE_TEAMS", "当前没有可加入的团队，请联系管理员");
        return new RegistrationOptions(options);
    }

    @Transactional
    public CreatedCodeResponse createCode(Integer requestedMaxUses, Instant expiresAt) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        int maxUses = requestedMaxUses == null ? 1 : requestedMaxUses;
        if (maxUses < 1 || maxUses > 1000) {
            throw ApiException.badRequest("INVALID_MAX_USES", "最大使用次数必须在 1 到 1000 之间");
        }
        Instant now = Instant.now();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw ApiException.badRequest("INVALID_EXPIRY", "有效期必须晚于当前时间");
        }
        String plainCode = generateCode();
        RegistrationCodeEntity saved = codes.save(new RegistrationCodeEntity(
                actor.organizationId(), hash(plainCode), hint(plainCode), maxUses, expiresAt,
                actor.membershipId(), now));
        audit.record(actor, "REGISTRATION_CODE_CREATED", "REGISTRATION_CODE", saved.getId().toString(), "SUCCESS",
                Map.of("maxUses", maxUses, "hasExpiry", expiresAt != null));
        return new CreatedCodeResponse(saved.getId(), plainCode, saved.getCodeHint(), maxUses, 0,
                expiresAt, true, now);
    }

    @Transactional(readOnly = true)
    public List<CodeResponse> listCodes() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        Instant now = Instant.now();
        return codes.findByOrganizationIdOrderByCreatedAtDesc(actor.organizationId()).stream()
                .map(code -> response(code, now)).toList();
    }

    @Transactional
    public void disableCode(UUID id) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        RegistrationCodeEntity code = codes.findByIdAndOrganizationId(id, actor.organizationId())
                .orElseThrow(() -> ApiException.notFound("REGISTRATION_CODE_NOT_FOUND", "注册码不存在"));
        if (code.isActive()) {
            code.disable(Instant.now());
            audit.record(actor, "REGISTRATION_CODE_DISABLED", "REGISTRATION_CODE", id.toString(), "SUCCESS", null);
        }
    }

    private CodeResponse response(RegistrationCodeEntity code, Instant now) {
        String status;
        if (!code.isActive()) status = code.getUsedCount() >= code.getMaxUses() ? "EXHAUSTED" : "DISABLED";
        else if (code.getExpiresAt() != null && !code.getExpiresAt().isAfter(now)) status = "EXPIRED";
        else status = "ACTIVE";
        return new CodeResponse(code.getId(), code.getCodeHint(), code.getMaxUses(), code.getUsedCount(),
                code.getExpiresAt(), status, code.getCreatedAt(), code.getLastUsedAt());
    }

    private void validateCode(RegistrationCodeEntity code, Instant now) {
        if (!code.isActive()) {
            throw ApiException.conflict("REGISTRATION_CODE_UNAVAILABLE", "注册码已停用或使用次数已满");
        }
        if (code.getExpiresAt() != null && !code.getExpiresAt().isAfter(now)) {
            throw ApiException.conflict("REGISTRATION_CODE_EXPIRED", "注册码已过期");
        }
        String organizationStatus = jdbc.queryForObject(
                "select status from organizations where id=?", String.class, code.getOrganizationId());
        if (!"ACTIVE".equals(organizationStatus)) {
            throw ApiException.conflict("ORGANIZATION_UNAVAILABLE", "注册码所属组织当前不可用");
        }
    }

    private String required(String value, String code, String message) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(code, message);
        return value.trim();
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("REGISTRATION_CODE_REQUIRED", "注册码不能为空");
        return value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder("YG");
        for (int group = 0; group < 4; group++) {
            code.append('-');
            for (int index = 0; index < 4; index++) {
                code.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
            }
        }
        return code.toString();
    }

    private String hint(String code) {
        String[] groups = code.split("-");
        return groups[0] + "-" + groups[1] + "-••••-••••-" + groups[4];
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record RegisterRequest(String username, String realName, String password, String registrationCode, UUID teamId) {}
    public record RegistrationResponse(UUID userId, UUID membershipId, String username, String realName, String role,
                                       UUID teamId, String teamName) {}
    public record TeamOption(UUID id, String name) {}
    public record RegistrationOptions(List<TeamOption> teams) {}
    public record CreatedCodeResponse(UUID id, String code, String codeHint, int maxUses, int usedCount,
                                      Instant expiresAt, boolean active, Instant createdAt) {}
    public record CodeResponse(UUID id, String codeHint, int maxUses, int usedCount, Instant expiresAt,
                               String status, Instant createdAt, Instant lastUsedAt) {}
}
