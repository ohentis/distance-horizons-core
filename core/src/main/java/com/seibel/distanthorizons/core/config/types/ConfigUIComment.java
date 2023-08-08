package com.seibel.distanthorizons.core.config.types;

import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;

/**
 * Adds something like a ConfigEntry but without a button to change the input
 *
 * @author coolGi
 */
public class ConfigUIComment extends AbstractConfigType<String, ConfigUIComment> {
    public ConfigUIComment() {
        super(EConfigEntryAppearance.ONLY_IN_GUI, "");
    }

    /** Appearance shouldn't be changed */
    @Override
    public void setAppearance(EConfigEntryAppearance newAppearance) {}

    /** Pointless to set the value */
    @Override
    public void set(String newValue) {}

    public static class Builder extends AbstractConfigType.Builder<String, Builder> {
        /** Appearance shouldn't be changed */
        @Override
        public Builder setAppearance(EConfigEntryAppearance newAppearance) {
            return this;
        }

        /** Pointless to set the value */
        @Override
        public Builder set(String newValue) {
            return this;
        }

        public ConfigUIComment build() {
            return new ConfigUIComment();
        }
    }
}
