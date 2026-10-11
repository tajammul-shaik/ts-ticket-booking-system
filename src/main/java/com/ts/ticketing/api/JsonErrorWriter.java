package com.ts.ticketing.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes an {@link ErrorResponse} from a servlet filter, where @RestControllerAdvice doesn't reach. */
@Component
public class JsonErrorWriter {

    private final ObjectMapper json;

    public JsonErrorWriter(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse res, HttpStatus status, String error, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), ErrorResponse.of(error, message));
    }
}
