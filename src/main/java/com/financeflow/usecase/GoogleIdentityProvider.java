package com.financeflow.usecase;

import com.financeflow.model.GoogleIdentity;

public interface GoogleIdentityProvider {

    String authorizationUrl(String state, String verifier, String nonce);

    GoogleIdentity identity(String code, String verifier, String nonce);
}
