package de.winniepat.minePanel.web;

import de.winniepat.minePanel.persistence.UserRepository;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

public final class BootstrapService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final String bootstrapToken;

    public BootstrapService(UserRepository userRepository, int tokenLength) {
        this.userRepository = userRepository;
        this.bootstrapToken = generateToken(Math.max(16, tokenLength));
    }

    public boolean needsBootstrap() {
        return userRepository.countUsers() == 0;
    }

    public Optional<String> getBootstrapToken() {
        if (!needsBootstrap()) {
            return Optional.empty();
        }
        return Optional.of(bootstrapToken);
    }

    public boolean verifyToken(String token) {
        return needsBootstrap() && token != null && bootstrapToken.equals(token);
    }

    private String generateToken(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }
}

