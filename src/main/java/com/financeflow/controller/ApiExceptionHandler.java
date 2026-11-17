package com.financeflow.controller;

import com.financeflow.model.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // Mensagens próprias do cadastro e do login quando o JSON não pode ser lido.
    private static final Map<String, String> INVALID_JSON_MESSAGES = Map.of(
            "/auth/register", "Envie nome, e-mail e senha em JSON valido.",
            "/auth/login", "Envie e-mail e senha em JSON valido.");

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, String>> domain(DomainException exception) {
        return switch (exception.error()) {
            case INVALID_INPUT -> message(HttpStatus.BAD_REQUEST, exception.getMessage());
            case INVALID_CATEGORY -> message(HttpStatus.BAD_REQUEST, exception.error().message());
            case INVALID_CREDENTIALS -> message(HttpStatus.UNAUTHORIZED, exception.error().message());
            case NOT_FOUND -> message(HttpStatus.NOT_FOUND, exception.error().message());
            case CONFLICT -> message(HttpStatus.CONFLICT, "Já existe um cadastro com esses dados.");
            default -> internal();
        };
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, String>> unreadable(HttpServletRequest request) {
        return message(HttpStatus.BAD_REQUEST,
                INVALID_JSON_MESSAGES.getOrDefault(request.getRequestURI(), "dados inválidos"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse response && !response.getStatusCode().is5xxServerError()) {
            return message(response.getStatusCode(), response.getStatusCode().value() == 404
                    ? "Recurso não encontrado." : "Requisição não permitida.");
        }
        return internal();
    }

    private static ResponseEntity<Map<String, String>> internal() {
        // Não registrar payloads, credenciais ou detalhes de conexão.
        LOG.error("FinanceFlow: falha interna durante operação HTTP");
        return message(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível concluir a operação.");
    }

    private static ResponseEntity<Map<String, String>> message(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
