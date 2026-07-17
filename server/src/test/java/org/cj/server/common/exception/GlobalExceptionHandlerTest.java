package org.cj.server.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Verifies the {@link GlobalExceptionHandler} produces the standard {@link ApiError}
 * JSON shape for each error class it handles.
 *
 * <p>We use MockMvc <em>standalone</em> setup: it wires just a throwaway controller plus
 * the exception handler, with no Spring context, Postgres, or security in the way. That
 * makes this a fast, focused unit test of the error-translation layer — exactly the piece
 * we're verifying — rather than a full app boot.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void notFoundBecomes404WithStandardShape() throws Exception {
        mockMvc.perform(get("/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Board not found"))
                .andExpect(jsonPath("$.path").value("/boom/not-found"))
                .andExpect(jsonPath("$.timestamp").exists())
                // fieldErrors is omitted (NON_NULL) on non-validation errors
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void illegalArgumentBecomes400() throws Exception {
        mockMvc.perform(get("/boom/bad-arg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("bad input"));
    }

    @Test
    void validationFailureBecomes400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/boom/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("title is required"));
    }

    @Test
    void unexpectedExceptionBecomes500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/boom/kaboom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                // internal details must NOT leak to the client
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /** A controller whose only job is to throw, one route per handled exception type. */
    @RestController
    static class ThrowingController {

        @GetMapping("/boom/not-found")
        void notFound() {
            throw new NotFoundException("Board not found");
        }

        @GetMapping("/boom/bad-arg")
        void badArg() {
            throw new IllegalArgumentException("bad input");
        }

        @GetMapping("/boom/kaboom")
        void kaboom() {
            throw new RuntimeException("secret internal detail");
        }

        @PostMapping("/boom/validate")
        void validate(@Valid @RequestBody CreateRequest body) {
            // never reached — @Valid fails first
        }
    }

    record CreateRequest(@NotBlank(message = "title is required") String title) {}
}
