package com.eternalcode.economy.command.impl.admin;

import com.eternalcode.economy.EconomyPermissionConstant;
import com.eternalcode.economy.account.Account;
import com.eternalcode.economy.account.AccountManager;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

@Command(name = "economy cleanupdb", aliases = "eco cleanupdb")
@Permission(EconomyPermissionConstant.ADMIN_CLEANUPDB_PERMISSION)
public class AdminCleanupDbCommand {

    private final AccountManager accountManager;
    private final JavaPlugin plugin;

    public AdminCleanupDbCommand(AccountManager accountManager, JavaPlugin plugin) {
        this.accountManager = accountManager;
        this.plugin = plugin;
    }

    @Execute
    void execute(@Context CommandSender sender) {
        this.accountManager.cleanupZeroBalanceAccounts()
            .thenAccept(accounts -> this.runOnServer(() -> this.sendResults(sender, accounts)))
            .exceptionally(throwable -> {
                this.runOnServer(() -> sender.sendMessage(
                    "Could not clean up economy database: " + throwable.getMessage()
                ));
                return null;
            });
    }

    private void sendResults(CommandSender sender, List<Account> accounts) {
        sender.sendMessage("Removed " + accounts.size() + " zero-balance economy account(s).");
        for (Account account : accounts) {
            sender.sendMessage("- " + account.name() + " (" + account.uuid() + ")");
        }
    }

    private void runOnServer(Runnable runnable) {
        this.plugin.getServer().getGlobalRegionScheduler().run(this.plugin, task -> runnable.run());
    }
}
