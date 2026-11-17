package com.financeflow.usecase;

import java.time.Instant;

/** O token só é entregue ao cookie: o banco recebe o seu hash. */
public record Session(String token, Instant expiresAt) {
}
