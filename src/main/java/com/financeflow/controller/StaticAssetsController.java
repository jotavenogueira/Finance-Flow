package com.financeflow.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A tela acompanha o jar: somente estes arquivos são servidos. */
@RestController
public class StaticAssetsController {

    private record Asset(byte[] content, MediaType type) {
    }

    private final Map<String, Asset> assets = Map.of(
            "index", load("web/index.html", "text/html;charset=utf-8"),
            "dashboard", load("web/dashboard.js", "text/javascript;charset=utf-8"),
            "app", load("web/app.js", "text/javascript;charset=utf-8"),
            "style", load("web/style.css", "text/css;charset=utf-8"),
            "google", load("web/google-g.png", "image/png"));

    @GetMapping("/")
    public ResponseEntity<byte[]> index() {
        return serve("index");
    }

    @GetMapping("/static/dashboard.js")
    public ResponseEntity<byte[]> dashboard() {
        return serve("dashboard");
    }

    @GetMapping("/static/app.js")
    public ResponseEntity<byte[]> app() {
        return serve("app");
    }

    @GetMapping("/static/style.css")
    public ResponseEntity<byte[]> style() {
        return serve("style");
    }

    @GetMapping("/static/google-g.png")
    public ResponseEntity<byte[]> googleLogo() {
        return serve("google");
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("message", "pong");
    }

    private ResponseEntity<byte[]> serve(String name) {
        Asset asset = assets.get(name);
        return ResponseEntity.ok().contentType(asset.type()).body(asset.content());
    }

    private static Asset load(String path, String type) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return new Asset(input.readAllBytes(), MediaType.parseMediaType(type));
        } catch (IOException exception) {
            throw new IllegalStateException("arquivo da interface ausente: " + path, exception);
        }
    }
}
