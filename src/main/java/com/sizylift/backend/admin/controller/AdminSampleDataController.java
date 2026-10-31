package com.sizylift.backend.admin.controller;

import com.sizylift.backend.admin.service.AdminSampleDataService;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin/users")
@Validated
public class AdminSampleDataController {
    private final AdminSampleDataService samples;

    public AdminSampleDataController(AdminSampleDataService samples) { this.samples = samples; }

    @PostMapping("/{id}/sample-data")
    public AdminSampleDataService.Result generate(@PathVariable @Size(max = 100) String id,
            @AuthenticationPrincipal Jwt token) {
        return samples.generate(id, token.getSubject());
    }
}
