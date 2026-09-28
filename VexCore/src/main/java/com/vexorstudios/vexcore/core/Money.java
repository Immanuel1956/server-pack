package com.vexorstudios.vexcore.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * The one way features touch money. Uses VexCore's own economy when that feature is on, and any
 * other Vault economy when it is off. Every withdraw and deposit reports whether it really
 * happened; callers must not hand anything out when it did not.
 */
public final class Money {

    /** What an economy has to offer. */
    public interface Provider {
        double balance(OfflinePlayer player);

        boolean withdraw(OfflinePlayer player, double amount);

        boolean deposit(OfflinePlayer player, double amount);

        /** With the currency, the way chat shows it: "$1,234.50". */
        String format(double amount);

        /** Short, for menus and scoreboards: "$1.23K". */
        String shortFormat(double amount);
    }

    private volatile Provider own;

    /** Called by the economy feature (null when it stops). */
    public void use(Provider provider) {
        this.own = provider;
    }

    private Provider current() {
        Provider p = own;
        if (p != null) return p;
        return Bukkit.getPluginManager().isPluginEnabled("Vault") ? VaultBridge.find() : null;
    }

    public boolean available() {
        return current() != null;
    }

    public double balance(OfflinePlayer player) {
        Provider p = current();
        return p == null ? 0 : p.balance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        return balance(player) >= amount;
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        Provider p = current();
        return p != null && Double.isFinite(amount) && amount >= 0 && p.withdraw(player, amount);
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        Provider p = current();
        return p != null && Double.isFinite(amount) && amount >= 0 && p.deposit(player, amount);
    }

    public String format(double amount) {
        Provider p = current();
        return p == null ? Numbers.full(amount, 2, ",") : p.format(amount);
    }

    public String shortFormat(double amount) {
        Provider p = current();
        return p == null ? Numbers.shortened(amount, new String[]{"K", "M", "B", "T", "Q"}) : p.shortFormat(amount);
    }

    /** Only loaded when Vault is installed. */
    static final class VaultBridge implements Provider {
        private final net.milkbowl.vault.economy.Economy economy;

        private VaultBridge(net.milkbowl.vault.economy.Economy economy) {
            this.economy = economy;
        }

        static Provider find() {
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            return rsp == null ? null : new VaultBridge(rsp.getProvider());
        }

        @Override
        public double balance(OfflinePlayer player) {
            return economy.getBalance(player);
        }

        @Override
        public boolean withdraw(OfflinePlayer player, double amount) {
            return economy.has(player, amount) && economy.withdrawPlayer(player, amount).transactionSuccess();
        }

        @Override
        public boolean deposit(OfflinePlayer player, double amount) {
            return economy.depositPlayer(player, amount).transactionSuccess();
        }

        @Override
        public String format(double amount) {
            return economy.format(amount);
        }

        @Override
        public String shortFormat(double amount) {
            return Numbers.shortened(amount, new String[]{"K", "M", "B", "T", "Q"});
        }
    }
}
