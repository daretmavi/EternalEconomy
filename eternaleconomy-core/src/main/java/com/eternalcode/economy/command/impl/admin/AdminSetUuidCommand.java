package com.eternalcode.economy.command.impl.admin;

import com.eternalcode.economy.EconomyPermissionConstant;
import com.eternalcode.economy.account.Account;
import com.eternalcode.economy.account.AccountManager;
import com.eternalcode.economy.account.AccountPaymentService;
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
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

@Command(name = "economy setuuid", aliases = "eco setuuid")
@Permission(EconomyPermissionConstant.ADMIN_SET_PERMISSION)
public class AdminSetUuidCommand {

    private final AccountManager accountManager;
    private final AccountPaymentService paymentService;
    private final DecimalFormatter decimalFormatter;
    private final NoticeService noticeService;
    private final JavaPlugin plugin;

    public AdminSetUuidCommand(
        AccountManager accountManager,
        AccountPaymentService paymentService,
        DecimalFormatter decimalFormatter,
        NoticeService noticeService,
        JavaPlugin plugin
    ) {
        this.accountManager = accountManager;
        this.paymentService = paymentService;
        this.decimalFormatter = decimalFormatter;
        this.noticeService = noticeService;
        this.plugin = plugin;
    }

    @Execute
    void execute(
        @Context CommandSender sender,
        @Arg String playerName,
        @Arg UUID uuid,
        @Arg @Min(0) BigDecimal amount
    ) {
        Account accountByUuid = this.accountManager.getAccount(uuid);
        Account accountByName = this.accountManager.getAccount(playerName);

        if (accountByUuid != null) {
            if (!accountByUuid.name().equalsIgnoreCase(playerName)) {
                this.runOnServer(() -> sender.sendMessage(
                    "UUID conflict: this UUID already belongs to "
                        + accountByUuid.name() + "."
                ));
                return;
            }

            if (accountByName != null
                && !accountByName.uuid().equals(uuid)) {
                this.runOnServer(() -> sender.sendMessage(
                    "Name conflict: " + playerName
                        + " already belongs to another UUID."
                ));
                return;
            }

            this.runOnServer(() -> {
                this.paymentService.setBalance(accountByUuid, amount);
                this.sendSuccess(sender, accountByUuid, amount);
            });
            return;
        }

        if (accountByName != null) {
            this.runOnServer(() -> sender.sendMessage(
                "Name conflict: " + playerName
                    + " already belongs to UUID "
                    + accountByName.uuid() + "."
            ));
            return;
        }

        this.accountManager.createAccount(uuid, playerName, amount)
            .thenAccept(account -> this.runOnServer(() ->
                this.sendSuccess(sender, account, amount)
            ))
            .exceptionally(throwable -> {
                this.runOnServer(() -> sender.sendMessage(
                    "Could not create account: " + throwable.getMessage()
                ));
                return null;
            });
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
}
