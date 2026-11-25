package com.sizylift.backend.photo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import javax.imageio.ImageIO;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.photo.service.WorkoutPhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkoutPhotoApiTest extends CoachWorkspaceApiSupport {

    private static final String PHOTOS = "/api/v2/workout-photos";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    WorkoutPhotoService photos;

    @Test
    void storesPrivatePhotoAndKeepsItWhenTheWorkoutIsRepublished() throws Exception {
        var athlete = newSubject();
        var coach = newSubject();
        link(coach, athlete);
        var workoutId = UUID.randomUUID();
        var version = UUID.randomUUID();
        var jpeg = jpeg(12);
        var path = PHOTOS + "/" + workoutId;
        mvc.perform(get(PHOTOS)).andExpect(status().isUnauthorized());
        callMobile(athlete, put(path + "/" + version).contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isNotFound());

        var workout = workout();
        legacy(athlete, "workout", workoutId, 1, workout);
        callMobile(athlete, put(path + "/" + version).contentType(MediaType.IMAGE_JPEG).content("not a jpeg"))
                .andExpect(status().isUnsupportedMediaType());
        callMobile(athlete, put(path + "/" + version).contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));

        var first = body(callMobile(athlete, get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.photo.width").value(12))).get("url").asString();
        var signed = read(first);
        assertThat(signed.statusCode()).isEqualTo(200);
        assertThat(signed.body()).isEqualTo(jpeg);
        assertThat(read(first.substring(0, first.indexOf('?'))).statusCode()).isEqualTo(403);
        callMobile(coach, get(path)).andExpect(status().isNotFound());
        callMobile(coach, get(PHOTOS)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        callMobile(coach, delete(path + "/" + UUID.randomUUID()).param("base", version.toString()))
                .andExpect(status().isNotFound());

        legacy(athlete, "workout", workoutId, 2, workout.put("title", "Updated workout"));
        callMobile(athlete, put(path + "/" + version).contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        var repeated = body(callMobile(athlete, get(path)).andExpect(status().isOk())).get("url").asString();
        assertThat(URI.create(repeated).getPath()).isEqualTo(URI.create(first).getPath());
        callMobile(athlete, get(PHOTOS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(version.toString()));

        legacy(athlete, "workout", workoutId, 3, null);
        callMobile(athlete, get(path)).andExpect(status().isNotFound());
        photos.removeObjects();
        assertThat(read(first).statusCode()).isEqualTo(404);
    }

    @Test
    void doesNotResurrectPhotoWhenEarlierUploadIsRetriedAfterRemoval() throws Exception {
        var athlete = newSubject();
        var workoutId = UUID.randomUUID();
        var uploaded = UUID.randomUUID();
        var removed = UUID.randomUUID();
        var path = PHOTOS + "/" + workoutId;
        var jpeg = jpeg(20);
        legacy(athlete, "workout", workoutId, 1, workout());
        callMobile(athlete, put(path + "/" + uploaded).contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk());
        var url = body(callMobile(athlete, get(path)).andExpect(status().isOk())).get("url").asString();
        callMobile(athlete, delete(path + "/" + removed)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));
        callMobile(athlete, delete(path + "/" + removed).param("base", uploaded.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        callMobile(athlete, put(path + "/" + uploaded).contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.version").value(removed.toString()));
        callMobile(athlete, get(path)).andExpect(status().isNotFound());
        callMobile(athlete, get(PHOTOS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deleted").value(true));
        photos.removeObjects();
        assertThat(read(url).statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsWebTokensEvenWhenTheyBelongToTheWorkoutOwner() throws Exception {
        var athlete = newSubject();
        var workoutId = UUID.randomUUID();
        var path = PHOTOS + "/" + workoutId;
        var version = UUID.randomUUID();
        var jpeg = jpeg(12);
        legacy(athlete, "workout", workoutId, 1, workout());
        callMobile(athlete, put(path + "/" + version).contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk());

        var token = jwt().jwt(builder -> builder.issuer(ISSUER).subject(athlete).claim("azp", "sizylift-web"));
        mvc.perform(get(PHOTOS).with(token)).andExpect(status().isForbidden());
        mvc.perform(get(path).with(token)).andExpect(status().isForbidden());
        mvc.perform(put(path + "/" + UUID.randomUUID()).contentType(MediaType.IMAGE_JPEG).content(jpeg)
                .with(token)).andExpect(status().isForbidden());
        mvc.perform(delete(path + "/" + UUID.randomUUID()).with(token)).andExpect(status().isForbidden());
        call(athlete, get(PHOTOS)).andExpect(status().isForbidden());
        callMobile(athlete, get(path)).andExpect(status().isOk());
    }

    @Test
    void pwaSharesPhotosWithTheMobileApp() throws Exception {
        var athlete = newSubject();
        var workoutId = UUID.randomUUID();
        var path = PHOTOS + "/" + workoutId;
        var fromPwa = UUID.randomUUID();
        legacy(athlete, "workout", workoutId, 1, workout());
        callPwa(athlete, put(path + "/" + fromPwa).contentType(MediaType.IMAGE_JPEG).content(jpeg(12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(fromPwa.toString()))
                .andExpect(jsonPath("$.accepted").value(true));
        // The phone sees the photo added in the PWA, and the PWA sees the phone's replacement.
        callMobile(athlete, get(PHOTOS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(fromPwa.toString()));
        var fromPhone = UUID.randomUUID();
        callMobile(athlete, put(path + "/" + fromPhone + "?base=" + fromPwa).contentType(MediaType.IMAGE_JPEG)
                .content(jpeg(16))).andExpect(status().isOk());
        callPwa(athlete, get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.photo.version").value(fromPhone.toString()))
                .andExpect(jsonPath("$.url").isNotEmpty());
        callPwa(athlete, delete(path + "/" + UUID.randomUUID() + "?base=" + fromPhone)).andExpect(status().isOk());
        callMobile(athlete, get(PHOTOS)).andExpect(jsonPath("$.items[0].deleted").value(true));
    }

    private ResultActions callPwa(String subject, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject)
                .claim("azp", "sizylift-pwa"))));
    }

    private ResultActions callMobile(String subject, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject)
                .claim("azp", "sizylift-mobile"))));
    }

    private static byte[] jpeg(int width) throws Exception {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, 10, BufferedImage.TYPE_INT_RGB), "jpeg", buffer);
        return buffer.toByteArray();
    }

    private static HttpResponse<byte[]> read(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
