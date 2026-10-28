package com.yingge.cloud.teamdata.api;

import com.yingge.cloud.teamdata.application.TeamDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/team-data")
public class TeamDataController {
    private final TeamDataService teamData;

    public TeamDataController(TeamDataService teamData) {
        this.teamData = teamData;
    }

    @GetMapping("/overview")
    public TeamDataDtos.Overview overview(
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String grade) {
        return teamData.overview(teamId, membershipId, search, grade);
    }
}
