package com.eternalcode.economy.command.impl.admin;

import com.eternalcode.economy.EconomyPermissionConstant;
import com.eternalcode.economy.account.Account;
import com.eternalcode.economy.account.AccountManager;
import com.eternalcode.economy.account.MojangApiClient;
import com.eternalcode.economy.account.PlayerUuidResolver;
import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

@Command(name = "economy create", aliases = "eco create")
@Permission(EconomyPermissionConstant.ADMIN_SET_PERMISSION)
public class AdminCreateCommand {

    private static final BigDecimal INITIAL_BALANCE = BigDecimal.ZERO;

    private final AccountManager accountManager;
    private final PlayerUuidResolver uuidResolver;
    private final MojangApiClient mojangApiClient;
    private final JavaPlugin plugin;

    public AdminCreateCommand(
        AccountManager accountManager,
        PlayerUuidResolver uuidResolver,
        MojangApiClient mojangApiClient,
        JavaPlugin plugin
    ) {
        this.accountManager = accountManager;
        this.uuidResolver = uuidResolver;
        this.mojangApiClient = mojangApiClient;
        this.plugin = plugin;
    }

    @Execute
    void executeLocal(
        @Context CommandSender sender,
        @Arg String playerName
    ) {
        this.createAccount(sender, playerName, this.uuidResolver.resolveLocal(playerName));
    }

    @Execute
    void executeWithUuidStrategy(
        @Context CommandSender sender,
        @Arg String playerName,
        @Arg String strategy
    ) {
        CompletableFuture<UUID> uuidFuture;

        if ("offline".equalsIgnoreCase(strategy)) {
            UUID uuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)
            );
            uuidFuture = CompletableFuture.completedFuture(uuid);
        } else if ("online".equalsIgnoreCase(strategy)) {
            uuidFuture = this.mojangApiClient.findUuid(playerName);
        } else {
            try {
                uuidFuture = CompletableFuture.completedFuture(UUID.fromString(strategy));
            } catch (IllegalArgumentException exception) {
                sender.sendMessage(
                    "Invalid UUID or strategy '" + strategy
                        + "'. Use a UUID, offline or online."
                );
                return;
            }
        }

        this.createAccount(sender, playerName, uuidFuture);
    }

    private void createAccount(
        CommandSender sender,
        String playerName,
        CompletableFuture<UUID> uuidFuture
    ) {
        uuidFuture
            .thenCompose(uuid -> this.accountManager.createAccount(
                uuid,
                playerName,
                INITIAL_BALANCE
            ))
            .thenAccept(account -> this.runOnServer(() ->
                sender.sendMessage(
                    "Created economy account for " + account.name()
                        + " (UUID: " + account.uuid() + ", balance: 0)."
                )
            ))
            .exceptionally(throwable -> {
                Throwable cause = throwable;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }

                String error = cause.getMessage() != null
                    ? cause.getMessage()
                    : "Unknown error.";

                this.runOnServer(() -> sender.sendMessage(
                    "Could not create economy account for '" + playerName + "': " + error
                ));
                return null;
            });
    }

    private void runOnServer(Runnable runnable) {
        this.plugin.getServer().getGlobalRegionScheduler()
            .run(this.plugin, task -> runnable.run());
    }
}
