package com.financeflow.model;

import java.time.Instant;

/** Guarda a tentativa de login somente no servidor. */
public record GoogleOAuthChallenge(String stateHash, String browserHash, String nonce, String verifier,
                                   int linkUserId, String linkSessionHash, Instant expiresAt) {
}
