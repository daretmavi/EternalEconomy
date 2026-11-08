package com.eternalcode.economy.account;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;

public class PlayerUuidResolver {

    private final AccountManager accountManager;
    private final Server server;

    public PlayerUuidResolver(AccountManager accountManager, Server server) {
        this.accountManager = accountManager;
        this.server = server;
    }

    public CompletableFuture<UUID> resolveLocal(String playerName) {
        Account existing = this.accountManager.getAccount(playerName);

        if (existing != null) {
            return CompletableFuture.completedFuture(existing.uuid());
        }

        Set<UUID> candidates = new LinkedHashSet<>();

        for (OfflinePlayer player : this.server.getWhitelistedPlayers()) {
            if (player.getName() != null
                && player.getName().equalsIgnoreCase(playerName)) {
                candidates.add(player.getUniqueId());
            }
        }

        this.addCachedPlayer(candidates, playerName);

        return this.lookupLuckPerms(playerName)
            .thenApply(luckPermsUuid -> {
                if (luckPermsUuid != null) {
                    candidates.add(luckPermsUuid);
                }

                if (candidates.isEmpty()) {
                    throw new CompletionException(
                        new IllegalStateException(
                            "No local UUID found for player '" + playerName + "'."
                        )
                    );
                }

                if (candidates.size() > 1) {
                    throw new CompletionException(
                        new IllegalStateException(
                            "Conflicting UUIDs found for player '" + playerName
                                + "': " + candidates
                        )
                    );
                }

                return candidates.iterator().next();
            });
    }

    private void addCachedPlayer(Set<UUID> candidates, String playerName) {
        try {
            Method method = this.server.getClass()
                .getMethod("getOfflinePlayerIfCached", String.class);

            Object result = method.invoke(this.server, playerName);

            if (result instanceof OfflinePlayer offlinePlayer) {
                candidates.add(offlinePlayer.getUniqueId());
            }
        }
        catch (ReflectiveOperationException ignored) {
            // Server implementation does not expose Paper's cache API.
        }
    }

    private CompletableFuture<UUID> lookupLuckPerms(String playerName) {
        LuckPerms luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);

        if (luckPerms == null) {
            return CompletableFuture.completedFuture(null);
        }

        return luckPerms.getUserManager()
            .lookupUniqueId(playerName)
            .handle((optionalUuid, throwable) -> {
                if (throwable != null || optionalUuid.isEmpty()) {
                    return null;
                }

                return optionalUuid.get();
            });
    }
}
