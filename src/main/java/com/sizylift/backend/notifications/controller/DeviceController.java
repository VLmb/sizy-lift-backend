package com.sizylift.backend.notifications.controller;

import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.notifications.dto.DeviceRequest;
import com.sizylift.backend.notifications.dto.DeviceResponse;
import com.sizylift.backend.notifications.repository.DeviceRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Устройства для push-уведомлений (docs/notifications.md). Id — id установки, его генерирует приложение. */
@RestController
@RequestMapping("/api/v2/devices")
public class DeviceController {

    private final AuthenticatedAccount authenticatedAccount;
    private final DeviceRepository devices;

    public DeviceController(AuthenticatedAccount authenticatedAccount, DeviceRepository devices) {
        this.authenticatedAccount = authenticatedAccount;
        this.devices = devices;
    }

    /** После входа и при смене токена. */
    @PutMapping("/{deviceId}")
    public DeviceResponse register(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
                                   @Valid @RequestBody DeviceRequest request) {
        devices.register(deviceId, authenticatedAccount.resolve(jwt).id(), request.platform(), request.pushToken());
        return new DeviceResponse(deviceId, request.platform(), request.pushToken() != null);
    }

    /** При выходе из аккаунта; чужое или неизвестное устройство не трогается. */
    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
        devices.deleteOwn(deviceId, authenticatedAccount.resolve(jwt).id());
    }
}
