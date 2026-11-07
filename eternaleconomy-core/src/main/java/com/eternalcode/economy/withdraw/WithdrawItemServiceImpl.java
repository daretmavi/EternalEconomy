package com.eternalcode.economy.withdraw;

import com.cryptomorin.xseries.XEnchantment;
import com.eternalcode.economy.config.implementation.PluginConfig;
import com.eternalcode.economy.config.item.ConfigItem;
import com.eternalcode.economy.config.item.ExactWithdrawItemEntry;
import com.eternalcode.economy.config.item.WithdrawItemEntry;
import com.eternalcode.economy.format.DecimalFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public class WithdrawItemServiceImpl implements WithdrawItemService {

    private static final String VALUE_PLACEHOLDER = "{VALUE}";
    private static final String PLAYER_PLACEHOLDER = "{PLAYER}";
    private static final String WITHDRAW_VALUE_KEY = "withdraw_value";
    private static final TagResolver EMPTY_RESOLVER = TagResolver.empty();

    private final PluginConfig pluginConfig;
    private final DecimalFormatter moneyFormatter;
    private final MiniMessage miniMessage;
    private final NamespacedKey banknoteValueKey;
    private final Enchantment glowEnchantment;

    private volatile NavigableMap<BigDecimal, ConfigItem> cachedThresholdMap;
    private volatile List<WithdrawItemEntry> cachedEntries;
    private volatile NavigableMap<BigDecimal, ConfigItem> cachedExactValueMap;
    private volatile List<ExactWithdrawItemEntry> cachedExactEntries;

    public WithdrawItemServiceImpl(
        Plugin plugin,
        PluginConfig pluginConfig,
        DecimalFormatter moneyFormatter,
        MiniMessage miniMessage
    ) {
        this.pluginConfig = pluginConfig;
        this.moneyFormatter = moneyFormatter;
        this.miniMessage = miniMessage;
        this.banknoteValueKey = new NamespacedKey(plugin, WITHDRAW_VALUE_KEY);
        this.glowEnchantment = XEnchantment.UNBREAKING.get();
    }

    @Override
    public ItemStack createBanknote(BigDecimal value, String creatorName) {
        return this.createBanknote(value, creatorName, this.selectConfigItem(value));
    }

    @Override
    public List<ItemStack> createBanknotes(BigDecimal value, String creatorName) {
        if (this.pluginConfig.withdraw.multiItemEnabled || !this.pluginConfig.withdraw.exactItemEnabled) {
            return List.of(this.createBanknote(value, creatorName));
        }

        NavigableMap<BigDecimal, ConfigItem> exactValueMap = this.getOrRebuildExactValueMap();
        if (exactValueMap.isEmpty()) {
            throw new IllegalStateException("Exact withdraw is enabled, but exactItemEntries is empty or invalid");
        }

        List<ItemStack> banknotes = new ArrayList<>();
        BigDecimal remaining = value;

        for (Map.Entry<BigDecimal, ConfigItem> entry : exactValueMap.descendingMap().entrySet()) {
            BigDecimal denomination = entry.getKey();
            int count = remaining.divideToIntegralValue(denomination).intValueExact();

            if (count <= 0) {
                continue;
            }

            remaining = remaining.subtract(denomination.multiply(BigDecimal.valueOf(count)));
            ItemStack banknote = this.createBanknote(denomination, creatorName, entry.getValue());
            int maxStackSize = banknote.getMaxStackSize();

            while (count > 0) {
                int stackAmount = Math.min(count, maxStackSize);
                ItemStack stack = banknote.clone();
                stack.setAmount(stackAmount);
                banknotes.add(stack);
                count -= stackAmount;
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(
                "Withdraw amount cannot be represented by configured exact denominations: " + value
            );
        }

        return banknotes;
    }

    private ItemStack createBanknote(BigDecimal value, String creatorName, ConfigItem configItem) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Banknote value must be positive, got: " + value);
        }

        String formattedValue = this.pluginConfig.withdraw.showExactValue
            ? this.moneyFormatter.formatExact(value)
            : this.moneyFormatter.format(value);
        ItemStack itemStack = new ItemStack(configItem.material());

        itemStack.editMeta(meta -> {
            if (configItem.texture() != null) {
                meta.setCustomModelData(configItem.texture());
            }

            Component name = this.miniMessage.deserialize(
                this.replaceBanknotePlaceholders(configItem.name(), formattedValue, creatorName),
                EMPTY_RESOLVER
            ).decoration(TextDecoration.ITALIC, false);

            meta.displayName(name);

            if (!configItem.lore().isEmpty()) {
                List<Component> lore = new ArrayList<>(configItem.lore().size());
                for (String line : configItem.lore()) {
                    lore.add(
                        this.miniMessage.deserialize(
                            this.replaceBanknotePlaceholders(line, formattedValue, creatorName),
                            EMPTY_RESOLVER
                        ).decoration(TextDecoration.ITALIC, false)
                    );
                }
                meta.lore(lore);
            }

            if (configItem.glow()) {
                meta.addEnchant(this.glowEnchantment, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            meta.getPersistentDataContainer().set(
                this.banknoteValueKey,
                PersistentDataType.STRING,
                value.toPlainString()
            );
        });

        return itemStack;
    }

    private NavigableMap<BigDecimal, ConfigItem> getOrRebuildExactValueMap() {
        List<ExactWithdrawItemEntry> entries = this.pluginConfig.withdraw.exactItemEntries;

        if (this.cachedExactValueMap == null || this.cachedExactEntries != entries) {
            NavigableMap<BigDecimal, ConfigItem> map = new TreeMap<>();

            if (entries != null) {
                for (ExactWithdrawItemEntry entry : entries) {
                    if (entry.exactItemValue() != null
                        && entry.exactItemValue().compareTo(BigDecimal.ZERO) > 0
                        && entry.item() != null) {
                        map.put(entry.exactItemValue(), entry.item());
                    }
                }
            }

            this.cachedExactValueMap = map;
            this.cachedExactEntries = entries;
        }

        return this.cachedExactValueMap;
    }

    private String replaceBanknotePlaceholders(String text, String formattedValue, String creatorName) {
        return text
            .replace(VALUE_PLACEHOLDER, formattedValue)
            .replace(PLAYER_PLACEHOLDER, creatorName);
    }

    private ConfigItem selectConfigItem(BigDecimal value) {
        if (!this.pluginConfig.withdraw.multiItemEnabled) {
            return this.pluginConfig.withdraw.item;
        }

        NavigableMap<BigDecimal, ConfigItem> thresholdMap = this.getOrRebuildThresholdMap();
        if (thresholdMap.isEmpty()) {
            return this.pluginConfig.withdraw.item;
        }

        Map.Entry<BigDecimal, ConfigItem> entry = thresholdMap.floorEntry(value);
        if (entry == null) {
            return this.pluginConfig.withdraw.item;
        }

        return entry.getValue();
    }

    private NavigableMap<BigDecimal, ConfigItem> getOrRebuildThresholdMap() {
        List<WithdrawItemEntry> entries = this.pluginConfig.withdraw.multiItemEntries;

        if (this.cachedThresholdMap == null || this.cachedEntries != entries) {
            this.cachedThresholdMap = this.buildThresholdMap(entries);
            this.cachedEntries = entries;
        }

        return this.cachedThresholdMap;
    }

    private NavigableMap<BigDecimal, ConfigItem> buildThresholdMap(List<WithdrawItemEntry> entries) {
        NavigableMap<BigDecimal, ConfigItem> map = new TreeMap<>();

        if (entries == null || entries.isEmpty()) {
            return map;
        }

        for (WithdrawItemEntry entry : entries) {
            if (entry.minValue() != null && entry.item() != null) {
                map.put(entry.minValue(), entry.item());
            }
        }

        return map;
    }

    @Override
    public boolean isBanknote(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = itemStack.getItemMeta();
        return meta.getPersistentDataContainer().has(this.banknoteValueKey, PersistentDataType.STRING);
    }

    @Override
    public BigDecimal getValue(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return BigDecimal.ZERO;
        }

        ItemMeta meta = itemStack.getItemMeta();
        String value = meta.getPersistentDataContainer()
            .get(this.banknoteValueKey, PersistentDataType.STRING);

        if (value == null) {
            return BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(value);
        }
        catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }
}
