package com.eternalcode.economy.withdraw;

import java.math.BigDecimal;
import java.util.List;
import org.bukkit.inventory.ItemStack;

public interface WithdrawItemService {

    BigDecimal getValue(ItemStack itemStack);

    boolean isBanknote(ItemStack itemStack);

    ItemStack createBanknote(BigDecimal value, String creatorName);

    List<ItemStack> createBanknotes(BigDecimal value, String creatorName);
}
