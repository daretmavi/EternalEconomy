package com.eternalcode.economy.config.item;

import eu.okaeri.configs.OkaeriConfig;
import java.math.BigDecimal;
import java.util.List;
import org.bukkit.Material;

public class ExactWithdrawItemEntry extends OkaeriConfig {

    private BigDecimal exactValue;
    private ConfigItem item;

    public ExactWithdrawItemEntry(BigDecimal exactValue, ConfigItem item) {
        this.exactValue = exactValue;
        this.item = item;
    }

    public ExactWithdrawItemEntry() {
        this.exactValue = BigDecimal.ONE;
        this.item = ConfigItem.builder()
            .withName("<white>Coin worth <green>{VALUE}$")
            .withLore(List.of("<gray>Right click to redeem"))
            .withMaterial(Material.GOLD_NUGGET)
            .withTexture(1)
            .withGlow(false)
            .build();
    }

    public BigDecimal exactValue() {
        return this.exactValue;
    }

    public ConfigItem item() {
        return this.item;
    }
}
