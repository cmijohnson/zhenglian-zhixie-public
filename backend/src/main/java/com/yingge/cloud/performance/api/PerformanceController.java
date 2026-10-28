package com.yingge.cloud.performance.api;

import com.yingge.cloud.performance.application.PerformanceService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceController {
    private final PerformanceService performance;

    public PerformanceController(PerformanceService performance) {
        this.performance = performance;
    }

    @GetMapping("/today")
    public PerformanceDtos.TodayResponse today() { return performance.today(); }

    @GetMapping("/leaderboard")
    public List<PerformanceDtos.LeaderboardRow> leaderboard(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID teamId) {
        return performance.leaderboard(from, to, teamId);
    }

    @GetMapping("/history")
    public List<PerformanceDtos.HistoryPoint> history(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return performance.history(from, to);
    }

    @GetMapping("/claims")
    public List<PerformanceDtos.ClaimResponse> claims(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return performance.claims(status, membershipId, date);
    }

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceDtos.ClaimResponse createClaim(@Valid @RequestBody PerformanceDtos.CreateClaimRequest body) {
        return performance.createClaim(body);
    }

    @PostMapping(value = "/claims/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceDtos.EvidenceResponse uploadClaimEvidence(
            @PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return performance.uploadEvidence("INTERACTION", id, file);
    }

    @PostMapping("/claims/{id}/submit")
    public PerformanceDtos.ClaimResponse submitClaim(@PathVariable UUID id) {
        return performance.submitClaim(id);
    }

    @PostMapping("/claims/{id}/review")
    public PerformanceDtos.ClaimResponse reviewClaim(
            @PathVariable UUID id, @Valid @RequestBody PerformanceDtos.ReviewClaimRequest body) {
        return performance.reviewClaim(id, body);
    }

    @GetMapping("/activities")
    public List<PerformanceDtos.ActivityResponse> activities(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return performance.activities(status, membershipId, date);
    }

    @PostMapping("/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceDtos.ActivityResponse createActivity(
            @Valid @RequestBody PerformanceDtos.CreateActivityRequest body) {
        return performance.createActivity(body);
    }

    @PostMapping("/activities/{id}/review")
    public PerformanceDtos.ActivityResponse reviewActivity(
            @PathVariable UUID id, @Valid @RequestBody PerformanceDtos.ReviewActivityRequest body) {
        return performance.reviewActivity(id, body);
    }

    @PostMapping(value = "/activities/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceDtos.EvidenceResponse uploadActivityEvidence(
            @PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return performance.uploadEvidence("ACTIVITY", id, file);
    }

    @PostMapping("/activities/{id}/complete")
    public PerformanceDtos.ActivityResponse completeActivity(
            @PathVariable UUID id, @Valid @RequestBody PerformanceDtos.CompleteActivityRequest body) {
        return performance.completeActivity(id, body);
    }

    @PostMapping("/activities/{id}/allocate")
    public PerformanceDtos.ActivityResponse allocateActivity(
            @PathVariable UUID id, @Valid @RequestBody PerformanceDtos.AllocateActivityRequest body) {
        return performance.allocateActivity(id, body);
    }

    @GetMapping("/evidence/{id}")
    public ResponseEntity<ByteArrayResource> evidence(@PathVariable UUID id) {
        var file = performance.evidence(id);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(file.content()));
    }

    @GetMapping("/admin/rules")
    public List<PerformanceDtos.RuleResponse> rules() { return performance.rules(); }

    @PostMapping("/admin/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceDtos.RuleResponse createRule(@Valid @RequestBody PerformanceDtos.CreateRuleRequest body) {
        return performance.createRule(body);
    }

    @GetMapping("/admin/participants")
    public List<PerformanceDtos.ParticipantResponse> participants() { return performance.participants(); }

    @PostMapping("/admin/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceDtos.ParticipantResponse addParticipant(
            @Valid @RequestBody PerformanceDtos.ParticipantRequest body) {
        return performance.addParticipant(body);
    }

    @PatchMapping("/admin/participants/{id}")
    public PerformanceDtos.ParticipantResponse updateParticipant(
            @PathVariable UUID id, @Valid @RequestBody PerformanceDtos.ParticipantUpdateRequest body) {
        return performance.updateParticipant(id, body);
    }

    @PostMapping("/admin/manual-entries")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceDtos.LedgerEntry createManualEntry(
            @Valid @RequestBody PerformanceDtos.ManualEntryRequest body) {
        return performance.createManualEntry(body);
    }

    @PostMapping(value = "/admin/manual-entries/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceDtos.EvidenceResponse uploadManualEvidence(
            @PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return performance.uploadEvidence("MANUAL", id, file);
    }

    @GetMapping("/admin/days/{date}")
    public ResponseEntity<PerformanceDtos.DayResponse> day(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return performance.findDay(date)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/admin/days/{date}/settle")
    public PerformanceDtos.DayResponse settle(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam long expectedVersion) {
        return performance.settle(date, expectedVersion);
    }

    @PostMapping("/admin/days/{date}/reopen")
    public PerformanceDtos.DayResponse reopen(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody PerformanceDtos.ReopenDayRequest body) {
        return performance.reopen(date, body);
    }

    @GetMapping("/admin/export")
    public void export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID teamId,
            HttpServletResponse response) throws java.io.IOException {
        byte[] bytes = performance.export(from, to, teamId);
        String name = URLEncoder.encode("绩效明细-" + from + "-" + to + ".xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + name);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
