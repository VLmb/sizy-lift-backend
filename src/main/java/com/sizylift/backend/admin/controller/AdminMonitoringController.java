package com.sizylift.backend.admin.controller;

import com.sizylift.backend.admin.service.AdminMetricsService;
import com.sizylift.backend.admin.service.AdminRuntimeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin")
@Validated
public class AdminMonitoringController {
    private final AdminMetricsService metrics;
    private final AdminRuntimeService runtime;

    public AdminMonitoringController(AdminMetricsService metrics, AdminRuntimeService runtime) {
        this.metrics = metrics;
        this.runtime = runtime;
    }

    @GetMapping("/metrics")
    public AdminMetricsService.Metrics metrics(@RequestParam(defaultValue = "1h") String period,
            @RequestParam(defaultValue = "false") boolean detail) {
        return metrics.metrics(period, detail);
    }

    @GetMapping("/containers")
    public AdminRuntimeService.Containers containers() {
        return runtime.containers();
    }

    @GetMapping("/containers/{id}/logs")
    public AdminRuntimeService.Logs logs(@PathVariable @Size(max = 64) String id,
            @RequestParam(defaultValue = "1h") String period,
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        return runtime.logs(id, period, limit);
    }
}
