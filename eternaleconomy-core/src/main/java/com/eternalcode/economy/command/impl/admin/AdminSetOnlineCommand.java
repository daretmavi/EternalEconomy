
package com.eternalcode.economy.command.impl.admin;

import com.eternalcode.economy.EconomyPermissionConstant;
import com.eternalcode.economy.account.Account;
import com.eternalcode.economy.account.AccountManager;
import com.eternalcode.economy.account.AccountPaymentService;
import com.eternalcode.economy.account.MojangApiClient;
import com.eternalcode.economy.format.DecimalFormatter;
import com.eternalcode.economy.multification.NoticeService;
import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

@Command(name = "economy setonline", aliases = "eco setonline")
@Permission(EconomyPermissionConstant.ADMIN_SET_PERMISSION)
public class AdminSetOnlineCommand {

    private final AccountManager accountManager;
    private final AccountPaymentService paymentService;
    private final MojangApiClient mojangApiClient;
    private final DecimalFormatter decimalFormatter;
    private final NoticeService noticeService;
    private final JavaPlugin plugin;

    public AdminSetOnlineCommand(
        AccountManager accountManager,
        AccountPaymentService paymentService,
        MojangApiClient mojangApiClient,
        DecimalFormatter decimalFormatter,
        NoticeService noticeService,
        JavaPlugin plugin
    ) {
        this.accountManager = accountManager;
        this.paymentService = paymentService;
        this.mojangApiClient = mojangApiClient;
        this.decimalFormatter = decimalFormatter;
        this.noticeService = noticeService;
        this.plugin = plugin;
    }

    @Execute
    void execute(
        @Context CommandSender sender,
        @Arg String playerName,
        @Arg @Min(0) BigDecimal amount
    ) {
        this.mojangApiClient.findUuid(playerName)
            .thenCompose(uuid ->
                this.accountManager.accountsLoaded()
                    .thenCompose(ignored ->
                        this.resolveAccount(uuid, playerName, amount)
                    )
            )
            .thenAccept(result -> this.runOnServer(() -> {
                if (result.created()) {
                    this.sendSuccess(sender, result.account(), amount);
                    return;
                }

                this.paymentService.setBalance(result.account(), amount);
                this.sendSuccess(sender, result.account(), amount);
            }))
            .exceptionally(throwable -> {
                Throwable cause = throwable;

                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }

                String errorMessage = cause.getMessage() != null
                    ? cause.getMessage()
                    : "Unknown error.";

                this.runOnServer(() ->
                    sender.sendMessage(
                        "Unable to set balance for '" + playerName
                            + "': " + errorMessage
                    )
                );

                return null;
            });
    }

    private CompletableFuture<AccountResult> resolveAccount(
        UUID uuid,
        String playerName,
        BigDecimal amount
    ) {
        Account existingByUuid = this.accountManager.getAccount(uuid);

        if (existingByUuid != null) {
            if (!existingByUuid.name().equalsIgnoreCase(playerName)) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException(
                        "UUID " + uuid + " already belongs to account '"
                            + existingByUuid.name() + "', not '" + playerName + "'."
                    )
                );
            }

            return CompletableFuture.completedFuture(
                new AccountResult(existingByUuid, false)
            );
        }

        Account existingByName = this.accountManager.getAccount(playerName);

        if (existingByName != null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(
                    "Player name '" + playerName
                        + "' already belongs to another UUID: "
                        + existingByName.uuid() + "."
                )
            );
        }

        return this.accountManager.createAccount(uuid, playerName, amount)
            .thenApply(account -> new AccountResult(account, true));
    }

    private void sendSuccess(
        CommandSender sender,
        Account account,
        BigDecimal amount
    ) {
        this.noticeService.create()
            .notice(notice -> notice.admin.set)
            .placeholder("{AMOUNT}", this.decimalFormatter.format(amount))
            .placeholder("{PLAYER}", account.name())
            .viewer(sender)
            .send();
    }

    private void runOnServer(Runnable runnable) {
        this.plugin.getServer().getGlobalRegionScheduler()
            .run(this.plugin, task -> runnable.run());
    }

    private record AccountResult(Account account, boolean created) {
    }
}
