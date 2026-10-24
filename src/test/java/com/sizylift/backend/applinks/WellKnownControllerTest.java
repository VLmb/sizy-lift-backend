package com.sizylift.backend.applinks;

import java.util.List;

import com.sizylift.backend.applinks.config.AppLinksProperties;
import com.sizylift.backend.applinks.controller.WellKnownController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** Пока Team ID и отпечатки не заданы, файлы связи не публикуются: пустой список Apple счёл бы ошибкой. */
class WellKnownControllerTest {

    @Test
    void filesAreOffUntilConfigured() {
        var controller = new WellKnownController(new AppLinksProperties("sizylift", " ", "com.sizylift.app",
                "com.sizylift.app", List.of("", " "), null, null));

        assertThat(controller.appleAppSiteAssociation().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.assetLinks().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
