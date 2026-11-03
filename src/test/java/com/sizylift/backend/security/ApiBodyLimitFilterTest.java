package com.sizylift.backend.security;

import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiBodyLimitFilterTest {
    @Test
    void enforcesTheLimitWithoutContentLengthAndStopsAfterTheFirstExcessByte() throws Exception {
        var request = chunked(new byte[ApiBodyLimitFilter.PUBLICATION_BYTES + 100]);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new ApiBodyLimitFilter().doFilter(request, response, (req, res) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(called).isFalse();
        assertThat(request.getInputStream().available()).isEqualTo(99);
    }

    @Test
    void passesAnExactBoundaryBodyUnchangedToTheController() throws Exception {
        var bytes = new byte[ApiBodyLimitFilter.PUBLICATION_BYTES];
        bytes[bytes.length - 1] = 42;
        var response = new MockHttpServletResponse();
        new ApiBodyLimitFilter().doFilter(chunked(bytes), response, (request, res) -> {
            assertThat(((HttpServletRequest) request).getContentLengthLong()).isEqualTo(bytes.length);
            assertThat(request.getInputStream().readAllBytes()).isEqualTo(bytes);
        });
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void leavesTheMultipartStreamForTheServletContainerToRead() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v2/imports");
        request.setContentType("multipart/form-data; boundary=example");
        request.setContent(new byte[] {1, 2, 3});
        new ApiBodyLimitFilter().doFilter(request, new MockHttpServletResponse(), (original, response) -> {
            assertThat(original).isSameAs(request);
            assertThat(original.getInputStream().readAllBytes()).containsExactly(1, 2, 3);
        });
    }

    private static HttpServletRequest chunked(byte[] body) {
        var base = new MockHttpServletRequest("POST", "/api/v1/publication/batch");
        base.setContent(body);
        return new HttpServletRequestWrapper(base) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
    }
}
