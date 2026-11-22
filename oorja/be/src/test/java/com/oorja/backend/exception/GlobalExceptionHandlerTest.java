package com.oorja.backend.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Every failure, whatever its cause, must come back in the one ApiErrorResponse envelope. */
class GlobalExceptionHandlerTest {

    record Body(@NotBlank String name, @Size(max = 3) String code) {}

    @RestController
    static class Probe {
        @PostMapping("/probe") String create(@Valid @RequestBody Body b) { return "ok"; }
        @GetMapping("/probe/conflict") String conflict() { throw ApiException.conflict("Slot full", "SLOT_FULL"); }
        @GetMapping("/probe/forbidden") String forbidden() { throw ApiException.forbidden("Not yours"); }
        @GetMapping("/probe/boom") String boom() { throw new IllegalStateException("secret SQL detail"); }
        @GetMapping("/probe/num") String num(@RequestParam int n) { return "ok"; }
        @PostMapping("/probe/upload") String upload() { throw new org.springframework.web.multipart.MaxUploadSizeExceededException(1024L); }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean v = new LocalValidatorFactoryBean();
        v.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(v).build();
    }

    @Test
    void validationFailureListsEveryBadField() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"code\":\"toolong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.code").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void malformedJsonIsA400WithoutParserDetail() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Unexpected"))));
    }

    @Test
    void businessErrorsKeepTheirStatusAndStableCode() throws Exception {
        mvc.perform(get("/probe/conflict")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_FULL"));
        mvc.perform(get("/probe/forbidden")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unexpectedErrorsNeverLeakInternals() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void maxUploadSizeExceededReturnsStructured413() throws Exception {
        mvc.perform(post("/probe/upload"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("These files are too large to upload."))
                .andExpect(jsonPath("$.path").value("/probe/upload"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void springMvcErrorsUseTheSameEnvelope() throws Exception {
        mvc.perform(get("/probe/num").param("n", "abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/probe/num")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(delete("/probe")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
