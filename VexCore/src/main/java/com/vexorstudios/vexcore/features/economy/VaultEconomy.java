package com.vexorstudios.vexcore.features.economy;

import com.vexorstudios.vexcore.VexCore;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.util.List;
import java.util.Locale;

/**
 * VexCore's economy as seen through Vault. Only loaded when Vault is installed.
 *
 * <p>Registered once and kept across /vexcore reload: other plugins keep the provider they got
 * at startup, so it always forwards to the economy that is running now (never to an old one,
 * whose balances were already saved and dropped).
 */
// Vault's Economy interface still has the old name-based methods; every implementation must
// provide them, deprecated or not.
@SuppressWarnings("deprecation")
final class VaultEconomy implements Economy {

    private static volatile EconomyFeature current;
    private static volatile VaultEconomy registered;

    private static final EconomyResponse NO_BANKS =
            new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "VexCore has no banks");

    private VaultEconomy() {
    }

    /** The running economy, or null once VexCore's economy was turned off (every call then fails politely). */
    private static EconomyFeature economy() {
        return current;
    }

    static synchronized Object register(EconomyFeature economy, VexCore plugin, String priority) {
        current = economy;
        if (registered != null) return registered; // after a reload: the same provider, now forwarding to this economy
        VaultEconomy provider = new VaultEconomy();
        registered = provider;
        ServicePriority p;
        try {
            p = ServicePriority.valueOf(priority.substring(0, 1).toUpperCase(Locale.ROOT) + priority.substring(1).toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            p = ServicePriority.Highest;
        }
        Bukkit.getServicesManager().register(Economy.class, provider, plugin, p);
        plugin.getLogger().info("Registered the VexCore economy with Vault (" + p + ").");
        return provider;
    }

    /**
     * The economy is stopping. On a reload the provider stays for the new economy; it is only
     * removed if none took over (economy turned off) or the plugin itself is stopping.
     */
    static synchronized void unregister(EconomyFeature economy, VexCore plugin) {
        if (plugin.isEnabled()) {
            com.vexorstudios.vexcore.core.Scheduler.global(() -> {
                synchronized (VaultEconomy.class) {
                    if (current == economy) remove();
                }
            });
            return;
        }
        remove();
    }

    private static void remove() {
        if (registered != null) Bukkit.getServicesManager().unregister(Economy.class, registered);
        registered = null;
        current = null;
    }

    private static OfflinePlayer byName(String name) {
        OfflinePlayer online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    private EconomyResponse result(OfflinePlayer player, double amount, boolean ok, String error) {
        double balance = player == null || economy() == null ? 0 : economy().balance(player);
        return new EconomyResponse(amount, balance,
                ok ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, ok ? "" : error);
    }

    // ── Info ──────────────────────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        EconomyFeature e = economy();
        return e != null && e.isEnabled();
    }

    @Override
    public String getName() {
        return "VexCore";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return economy() == null ? 2 : economy().decimals();
    }

    @Override
    public String format(double amount) {
        return economy() == null ? String.valueOf(amount) : economy().format(amount);
    }

    @Override
    public String currencyNamePlural() {
        return economy() == null ? "" : economy().currencyName(true);
    }

    @Override
    public String currencyNameSingular() {
        return economy() == null ? "" : economy().currencyName(false);
    }

    // ── Accounts ──────────────────────────────────────────────────────────

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null && economy() != null && economy().hasAccount(player);
    }

    @Override
    public boolean hasAccount(String name) {
        return hasAccount(byName(name));
    }

    @Override
    public boolean hasAccount(String name, String world) {
        return hasAccount(name);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        // Accounts are made on first join; a deposit to a known offline player makes one too.
        return player != null && economy() != null && (economy().hasAccount(player) || economy().deposit(player, 0));
    }

    @Override
    public boolean createPlayerAccount(String name) {
        return createPlayerAccount(byName(name));
    }

    @Override
    public boolean createPlayerAccount(String name, String world) {
        return createPlayerAccount(name);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return createPlayerAccount(player);
    }

    // ── Balance ───────────────────────────────────────────────────────────

    @Override
    public double getBalance(OfflinePlayer player) {
        return player == null || economy() == null ? 0 : economy().balance(player);
    }

    @Override
    public double getBalance(String name) {
        return getBalance(byName(name));
    }

    @Override
    public double getBalance(String name, String world) {
        return getBalance(name);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return player != null && getBalance(player) >= amount;
    }

    @Override
    public boolean has(String name, double amount) {
        return has(byName(name), amount);
    }

    @Override
    public boolean has(String name, String world, double amount) {
        return has(name, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        return has(player, amount);
    }

    // ── Changes ───────────────────────────────────────────────────────────

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) return result(null, amount, false, "Unknown player");
        if (!Double.isFinite(amount) || amount < 0) return result(player, amount, false, "Invalid amount");
        return result(player, amount, economy() != null && economy().withdraw(player, amount), "Insufficient funds, frozen or loading");
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, double amount) {
        return withdrawPlayer(byName(name), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, String world, double amount) {
        return withdrawPlayer(name, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) return result(null, amount, false, "Unknown player");
        if (!Double.isFinite(amount) || amount < 0) return result(player, amount, false, "Invalid amount");
        return result(player, amount, economy() != null && economy().deposit(player, amount), "Frozen, over the limit or loading");
    }

    @Override
    public EconomyResponse depositPlayer(String name, double amount) {
        return depositPlayer(byName(name), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String name, String world, double amount) {
        return depositPlayer(name, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    // ── Banks (not supported) ─────────────────────────────────────────────

    @Override
    public EconomyResponse createBank(String name, String player) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return NO_BANKS;
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return NO_BANKS;
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
