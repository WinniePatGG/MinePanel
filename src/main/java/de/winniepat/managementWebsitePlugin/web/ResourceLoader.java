package de.winniepat.managementWebsitePlugin.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ResourceLoader {

    private ResourceLoader() {
    }

    public static String loadUtf8Text(String resourcePath) {
        try (InputStream inputStream = ResourceLoader.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read resource: " + resourcePath, exception);
        }
    }
}

