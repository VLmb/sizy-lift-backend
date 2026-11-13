package com.sizylift.backend.voice.controller;

import java.io.IOException;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.voice.dto.VoiceDtos.AliasRequest;
import com.sizylift.backend.voice.dto.VoiceDtos.Failure;
import com.sizylift.backend.voice.dto.VoiceDtos.ParseRequest;
import com.sizylift.backend.voice.dto.VoiceDtos.ParseResponse;
import com.sizylift.backend.voice.service.VoiceFailure;
import com.sizylift.backend.voice.service.VoiceParseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Голосовой ввод тренировки: docs/voice.md. */
@RestController
@RequestMapping("/api/v2/voice")
public class VoiceController {
    private final AuthenticatedAccount accounts;
    private final VoiceParseService voice;

    public VoiceController(AuthenticatedAccount accounts, VoiceParseService voice) {
        this.accounts = accounts;
        this.voice = voice;
    }

    @PostMapping(path = "/workout-parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ParseResponse parse(@AuthenticationPrincipal Jwt jwt, @Valid @RequestPart("metadata") ParseRequest metadata,
                               @RequestPart("audio") MultipartFile audio) throws IOException {
        return voice.parse(accounts.resolve(jwt).id(), metadata, audio.getBytes(), audio.getContentType());
    }

    @PostMapping(path = "/aliases", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void alias(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AliasRequest request) {
        voice.rememberAlias(accounts.resolve(jwt).id(), request);
    }

    @ExceptionHandler(VoiceFailure.class)
    ResponseEntity<Failure> failure(VoiceFailure failure) {
        return ResponseEntity.status(failure.status()).body(new Failure(failure.code(), failure.getMessage()));
    }
}
