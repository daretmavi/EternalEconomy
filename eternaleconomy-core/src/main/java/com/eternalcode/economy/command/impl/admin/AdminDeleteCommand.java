package com.eternalcode.economy.command.impl.admin;

import com.eternalcode.economy.EconomyPermissionConstant;
import com.eternalcode.economy.account.Account;
import com.eternalcode.economy.account.AccountManager;
import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

@Command(name = "economy delete", aliases = "eco delete")
@Permission(EconomyPermissionConstant.ADMIN_DELETE_PERMISSION)
public class AdminDeleteCommand {

    private final AccountManager accountManager;
    private final JavaPlugin plugin;

    public AdminDeleteCommand(AccountManager accountManager, JavaPlugin plugin) {
        this.accountManager = accountManager;
        this.plugin = plugin;
    }

    @Execute
    void execute(
        @Context CommandSender sender,
        @Arg String playerName,
        @Arg String rawUuid
    ) {
        this.delete(sender, playerName, rawUuid, false);
    }

    @Execute
    void executeConfirmed(
        @Context CommandSender sender,
        @Arg String playerName,
        @Arg String rawUuid,
        @Arg String confirmation
    ) {
        if (!"confirm".equalsIgnoreCase(confirmation)) {
            sender.sendMessage("Invalid confirmation. Use 'confirm' as the final argument.");
            return;
        }

        this.delete(sender, playerName, rawUuid, true);
    }

    private void delete(CommandSender sender, String playerName, String rawUuid, boolean confirmed) {
        UUID uuid;
        try {
            uuid = UUID.fromString(rawUuid);
        }
        catch (IllegalArgumentException exception) {
            sender.sendMessage("Invalid UUID: " + rawUuid);
            return;
        }

        Account account = this.accountManager.getAccount(uuid);
        if (account == null || !account.name().equalsIgnoreCase(playerName)) {
            sender.sendMessage("No economy account found for " + playerName + " (" + uuid + ").");
            return;
        }

        if (account.balance().compareTo(BigDecimal.ZERO) != 0 && !confirmed) {
            sender.sendMessage(
                "Account " + account.name() + " has a non-zero balance of " + account.balance()
                    + ". Repeat the command with 'confirm' to delete it."
            );
            sender.sendMessage("/economy delete " + account.name() + " " + account.uuid() + " confirm");
            return;
        }

        this.accountManager.deleteAccount(account).thenAccept(deleted ->
            this.runOnServer(() -> {
                if (deleted) {
                    sender.sendMessage("Deleted economy account for " + account.name()
                        + " (UUID: " + account.uuid() + ").");
                } else {
                    sender.sendMessage("Account changed while deleting. Check its balance and try again.");
                }
            })
        ).exceptionally(throwable -> {
            this.runOnServer(() -> sender.sendMessage(
                "Could not delete economy account: " + throwable.getMessage()
            ));
            return null;
        });
    }

    private void runOnServer(Runnable runnable) {
        this.plugin.getServer().getGlobalRegionScheduler().run(this.plugin, task -> runnable.run());
    }
}
