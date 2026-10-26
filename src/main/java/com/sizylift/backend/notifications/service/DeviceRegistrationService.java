package com.sizylift.backend.notifications.service;

import java.util.UUID;
import com.sizylift.backend.notifications.repository.DeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

/** UUID установки не является доказательством права менять чужое устройство. */
@Service
public class DeviceRegistrationService {
    private final DeviceRepository devices;

    public DeviceRegistrationService(DeviceRepository devices) {
        this.devices = devices;
    }

    @Transactional
    public void register(UUID device, UUID owner, String platform, String token) {
        if (!devices.register(device, owner, platform, token)) throw new Forbidden();
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class Forbidden extends RuntimeException { }
}
