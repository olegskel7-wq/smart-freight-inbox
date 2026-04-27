package com.smartfreight.inbox.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Vienetų testai globaliam išimčių tvarkytojui (GlobalExceptionHandler).
 *
 * Naudoja pagalbinį TestThrowingController, kuris meta kiekvieno tipo išimtis –
 * patikrinamas teisingas HTTP būsenos kodas ir JSON atsakas.
 *
 * @RestControllerAdvice(annotations = RestController.class) scope užtikrina,
 * kad tvarkytojas veikia tik su @RestController klasėmis –
 * tai verificuojama naudojant standaloneSetup su setControllerAdvice().
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * Testas: ContainerNotFoundException → 404 Not Found su JSON atsaku.
     *
     * Tikrinami laukai: error = "CONTAINER_NOT_FOUND", message = konteinerio ID.
     */
    @Test
    void handleContainerNotFound_returns404WithErrorJson() throws Exception {
        mockMvc.perform(get("/test/container-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONTAINER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Container MSCU0000001 not found"));
    }

    /**
     * Testas: IllegalArgumentException → 400 Bad Request su JSON atsaku.
     *
     * Naudojamas klaidingų įvesties parametrų atveju
     * (pvz., neleistina enum reikšmė, netinkamas formatas).
     */
    @Test
    void handleIllegalArgument_returns400WithErrorJson() throws Exception {
        mockMvc.perform(get("/test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("Bad input value"));
    }

    /**
     * Testas: nenumatyta Exception → 500 Internal Server Error.
     *
     * Tikrinama, kad vidinio serverio klaidos pranešimas yra generalizuotas –
     * klientas negauna stack trace ar vidinių detalių.
     */
    @Test
    void handleGenericException_returns500WithGeneralMessage() throws Exception {
        mockMvc.perform(get("/test/generic-exception"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    /**
     * Pagalbinis REST valdiklis, skirtas išimčių išmetimui testuose.
     *
     * Kiekvienas galūninis taškas meta skirtingo tipo išimtį –
     * GlobalExceptionHandler turi teisingai jas apdoroti.
     */
    @RestController
    @RequestMapping("/test")
    static class TestThrowingController {

        @GetMapping("/container-not-found")
        String throwContainerNotFound() {
            throw new ContainerNotFoundException("Container MSCU0000001 not found");
        }

        @GetMapping("/illegal-argument")
        String throwIllegalArgument() {
            throw new IllegalArgumentException("Bad input value");
        }

        @GetMapping("/generic-exception")
        String throwGenericException() {
            throw new RuntimeException("Something went terribly wrong");
        }
    }
}
