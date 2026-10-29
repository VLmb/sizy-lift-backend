package com.sizylift.backend.admin.controller;

import com.sizylift.backend.admin.dto.AdminUser;
import com.sizylift.backend.admin.service.AdminUserService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin")
@Validated
public class AdminUserController {
    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping("/access")
    public java.util.Map<String, Boolean> access() {
        return java.util.Map.of("allowed", true);
    }

    @GetMapping("/overview")
    public AdminUserService.Overview overview() {
        return users.overview();
    }

    @GetMapping("/users")
    public AdminUserService.Page users(@RequestParam(defaultValue = "") @Size(max = 100) String search,
            @RequestParam(defaultValue = "all") String state, @RequestParam(defaultValue = "all") String coach,
            @RequestParam(defaultValue = "registered") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return users.users(search, state, coach, sort, page, size);
    }

    @GetMapping("/users/{id}")
    public AdminUser user(@PathVariable @Size(max = 100) String id) {
        return users.user(id);
    }
}
