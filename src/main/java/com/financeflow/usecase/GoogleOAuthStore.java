package com.financeflow.usecase;

import com.financeflow.model.GoogleOAuthChallenge;

public interface GoogleOAuthStore {

    void createGoogleChallenge(GoogleOAuthChallenge challenge);

    GoogleOAuthChallenge consumeGoogleChallenge(String stateHash, String browserHash);

    boolean googleLinkSessionActive(GoogleOAuthChallenge challenge);
}
