package com.eternalcode.economy.config.item;

import eu.okaeri.configs.OkaeriConfig;
import java.math.BigDecimal;
import java.util.List;
import org.bukkit.Material;

public class ExactWithdrawItemEntry extends OkaeriConfig {

    private BigDecimal exactItemValue;
    private ConfigItem item;

    public ExactWithdrawItemEntry(BigDecimal exactItemValue, ConfigItem item) {
        this.exactItemValue = exactItemValue;
        this.item = item;
    }

    public ExactWithdrawItemEntry() {
        this.exactItemValue = BigDecimal.ONE;
        this.item = ConfigItem.builder()
            .withName("<white>Coin worth <green>{VALUE}$")
            .withLore(List.of("<gray>Right click to redeem"))
            .withMaterial(Material.IRON_NUGGET)
            .withTexture(1)
            .withGlow(false)
            .build();
    }

    public BigDecimal exactItemValue() {
        return this.exactItemValue;
    }

    public ConfigItem item() {
        return this.item;
    }
}
