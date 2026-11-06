package com.eternalcode.economy.account;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MojangApiClient {

    private static final Pattern ID_PATTERN =
        Pattern.compile("\"id\"\\s*:\\s*\"([a-fA-F0-9]{32})\"");

    private final HttpClient httpClient;

    public MojangApiClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public CompletableFuture<UUID> findUuid(String playerName) {
        String url = "https://api.mojang.com/users/profiles/minecraft/" + playerName;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .GET()
            .build();

        return this.httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
            )
            .thenApply(response -> {
                if (response.statusCode() == 404) {
                    throw new IllegalStateException(
                        "Mojang does not know player '" + playerName + "'."
                    );
                }

                if (response.statusCode() == 429) {
                    throw new IllegalStateException(
                        "Mojang API rate limit reached. Try again later."
                    );
                }

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                        "Mojang API returned HTTP " + response.statusCode()
                    );
                }

                Matcher matcher = ID_PATTERN.matcher(response.body());

                if (!matcher.find()) {
                    throw new IllegalStateException(
                        "Mojang returned an invalid profile response."
                    );
                }

                String rawUuid = matcher.group(1);

                String formattedUuid =
                    rawUuid.substring(0, 8) + "-"
                    + rawUuid.substring(8, 12) + "-"
                    + rawUuid.substring(12, 16) + "-"
                    + rawUuid.substring(16, 20) + "-"
                    + rawUuid.substring(20);

                return UUID.fromString(formattedUuid);
            });
    }
}
