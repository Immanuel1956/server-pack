package com.vexorstudios.vexcore.gui;

import com.vexorstudios.vexcore.VexCore;
import com.vexorstudios.vexcore.core.Feature;
import com.vexorstudios.vexcore.core.SoundSpec;
import com.vexorstudios.vexcore.core.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One open menu. The feature gives a builder that fills it in; the builder runs again on every
 * {@link #refresh()}, so the menu always shows the current state.
 *
 * <p>Drawing order: the builder runs first (it adds functions and entries), then the file's
 * {@code items} are placed, then the builder's entries on top. An item whose {@code function}
 * the builder did not offer (like {@code next-page} on the last page) is left out.
 *
 * <p>Every click is cancelled; clicks count only in the menu itself, never in the player's own
 * inventory. A click plays the item's sound (or the menu's {@code click} sound), runs its
 * command attachments, then its built-in behaviour.
 *
 * <p>Editable slots (trash, sell) keep what players put in them when the menu is redrawn, and
 * {@link #onClose} lets a feature deal with those items once the menu closes for any reason
 * (the player closed it, left, died, or the feature was reloaded).
 */
public final class Menu implements InventoryHolder {

    public record Click(Menu menu, Player player, ClickType type, int slot) {
    }

    record Button(MenuItem source, Map<String, Object> placeholders, Consumer<Click> action) {
    }

    private record Entry(ItemStack item, Button button) {
    }

    private final Feature feature;
    private final MenuFile file;
    private final Player viewer;
    private final Consumer<Menu> builder;
    private final Map<String, Object> placeholders = new HashMap<>();
    private final Map<String, Consumer<Click>> functions = new HashMap<>();
    private final Map<Integer, Entry> entries = new HashMap<>();
    private final Map<Integer, Button> buttons = new HashMap<>();
    private final Set<Integer> editable = new HashSet<>();
    private Inventory inventory;
    private String shownTitle;
    private int page;
    private Consumer<Menu> closeAction;
    private boolean closed;

    public Menu(Feature feature, MenuFile file, Player viewer, Consumer<Menu> builder) {
        this.feature = feature;
        this.file = file;
        this.viewer = viewer;
        this.builder = builder == null ? m -> {
        } : builder;
    }

    // ── For builders ──────────────────────────────────────────────────────

    public Player viewer() {
        return viewer;
    }

    public Feature feature() {
        return feature;
    }

    public MenuFile file() {
        return file;
    }

    /** A placeholder for the title, the items and the command attachments. */
    public Menu with(String key, Object value) {
        placeholders.put(key, value);
        return this;
    }

    /** Offers a built-in behaviour to items with {@code function: <name>}. */
    public Menu function(String name, Consumer<Click> action) {
        functions.put(name, action);
        return this;
    }

    /** Places template {@code name} in {@code slot} with extra placeholders. */
    public void place(String template, int slot, Map<String, ?> extra, Consumer<Click> action) {
        MenuItem item = file.template(template);
        if (item == null) {
            VexCore.get().getLogger().warning(file.path() + " has no template '" + template + "'");
            return;
        }
        Map<String, Object> ph = new HashMap<>(placeholders);
        if (extra != null) ph.putAll(extra);
        if (item.permission().isEmpty() || viewer.hasPermission(item.permission())) {
            set(slot, item.spec().build(viewer, ph), new Button(item, ph, action));
        }
    }

    /** Places any item. */
    public void set(int slot, ItemStack item, Consumer<Click> action) {
        set(slot, item, new Button(null, new HashMap<>(placeholders), action));
    }

    private void set(int slot, ItemStack item, Button button) {
        if (slot < 0 || slot >= file.size()) return;
        entries.put(slot, new Entry(item, button));
    }

    /**
     * Lays {@code list} out over the file's {@code content-slots}, one page at a time, and offers
     * the {@code previous-page} and {@code next-page} functions where there is a page to go to.
     */
    public <T> void paginate(List<T> list, BiConsumer<T, Integer> placer) {
        List<Integer> slots = file.contentSlots();
        int per = Math.max(1, slots.size());
        int pages = Math.max(1, (list.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        with("page", page + 1).with("pages", pages).with("next_page", Math.min(pages, page + 2)).with("previous_page", Math.max(1, page));
        if (slots.isEmpty()) return;
        for (int i = page * per; i < Math.min(list.size(), (page + 1) * per); i++) {
            placer.accept(list.get(i), slots.get(i - page * per));
        }
        if (page > 0) function("previous-page", c -> turn(-1));
        if (page < pages - 1) function("next-page", c -> turn(1));
    }

    private void turn(int by) {
        page += by;
        sound("page");
        refresh();
    }

    public int page() {
        return page;
    }

    /** Slots the player may put items into and take them out of (the trash, the sell menu). */
    public void editable(Collection<Integer> slots) {
        editable.addAll(slots);
    }

    /** Runs once when this menu closes, for whatever reason. Set it from the builder. */
    public void onClose(Consumer<Menu> action) {
        closeAction = action;
    }

    /** The editable slots, in order. */
    public List<Integer> editableSlots() {
        List<Integer> out = new java.util.ArrayList<>(editable);
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Puts {@code item} into the editable slots (onto similar stacks first, then empty slots)
     * and returns what didn't fit, or null.
     */
    public ItemStack insert(ItemStack item) {
        if (item == null || item.isEmpty() || inventory == null) return null;
        ItemStack left = item.clone();
        int max = Math.max(1, item.getMaxStackSize());
        List<Integer> slots = editableSlots();
        for (int pass = 0; pass < 2 && left.getAmount() > 0; pass++) {
            for (int slot : slots) {
                ItemStack there = inventory.getItem(slot);
                if (pass == 0 && there != null && !there.isEmpty() && there.isSimilar(left) && there.getAmount() < max) {
                    int move = Math.min(max - there.getAmount(), left.getAmount());
                    there.setAmount(there.getAmount() + move);
                    inventory.setItem(slot, there);
                    left.setAmount(left.getAmount() - move);
                } else if (pass == 1 && (there == null || there.isEmpty())) {
                    int move = Math.min(max, left.getAmount());
                    ItemStack placed = left.clone();
                    placed.setAmount(move);
                    inventory.setItem(slot, placed);
                    left.setAmount(left.getAmount() - move);
                }
                if (left.getAmount() <= 0) return null;
            }
        }
        return left.getAmount() > 0 ? left : null;
    }

    /**
     * Plays one of the file's sounds. open/close/page count as menu sounds and click as a click,
     * so the result of a button (a reward, an error, the next menu) wins over them; the rest (a
     * coinflip win) are ordinary sounds.
     */
    public void sound(String key) {
        SoundSpec sound = file.sound(key);
        if (sound != null) sound.play(viewer, soundLevel(key));
    }

    static int soundLevel(String key) {
        return switch (key) {
            case "click" -> com.vexorstudios.vexcore.core.SoundGate.CLICK;
            case "open", "close", "page" -> com.vexorstudios.vexcore.core.SoundGate.MENU;
            default -> com.vexorstudios.vexcore.core.SoundGate.NORMAL;
        };
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    public void open() {
        render(true);
        sound("open");
    }

    /** Redraws the menu if the player still has it open. */
    public void refresh() {
        if (inventory != null && viewer.getOpenInventory().getTopInventory() == inventory) render(false);
    }

    private void render(boolean open) {
        // What players put into editable slots survives the redraw.
        Map<Integer, ItemStack> kept = new HashMap<>();
        if (inventory != null) {
            for (int slot : editable) {
                ItemStack item = inventory.getItem(slot);
                if (item != null && !item.isEmpty()) kept.put(slot, item.clone());
            }
        }
        functions.clear();
        entries.clear();
        buttons.clear();
        editable.clear();
        closeAction = null;
        placeholders.put("player", viewer.getName());
        builder.accept(this);

        String title = Text.fill(file.title(), placeholders);
        boolean reopen = open || inventory == null || !title.equals(shownTitle);
        Inventory target = inventory;
        if (inventory == null || !title.equals(shownTitle)) {
            target = Bukkit.createInventory(this, file.size(), Text.parse(file.title(), viewer, placeholders));
        } else {
            target.clear();
        }
        for (MenuItem item : file.items()) {
            if (!item.permission().isEmpty() && !viewer.hasPermission(item.permission())) continue;
            Consumer<Click> action = null;
            if (item.function() != null) {
                action = functions.get(item.function());
                if (action == null) continue;
            }
            ItemStack stack = item.spec().build(viewer, placeholders);
            Button button = new Button(item, new HashMap<>(placeholders), action);
            for (int slot : item.slots()) {
                if (slot < 0 || slot >= target.getSize()) continue;
                target.setItem(slot, stack);
                buttons.put(slot, button);
            }
        }
        for (Map.Entry<Integer, Entry> e : entries.entrySet()) {
            target.setItem(e.getKey(), e.getValue().item);
            buttons.put(e.getKey(), e.getValue().button);
        }
        for (Map.Entry<Integer, ItemStack> e : kept.entrySet()) {
            int slot = e.getKey();
            ItemStack there = slot < target.getSize() ? target.getItem(slot) : null;
            if (editable.contains(slot) && (there == null || there.isEmpty())) target.setItem(slot, e.getValue());
            else giveBack(viewer, e.getValue()); // the slot stopped being editable: never lose it
        }
        inventory = target;
        shownTitle = title;
        if (reopen) viewer.openInventory(target);
    }

    /** Hands an item to the player; what doesn't fit drops at their feet. */
    public static void giveBack(Player player, ItemStack item) {
        if (item == null || item.isEmpty()) return;
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), left, drop -> drop.setOwner(player.getUniqueId()));
        }
    }

    // ── Used by the listener ──────────────────────────────────────────────

    /** The menu closed. Runs the close action once. */
    void closed() {
        if (closed) return;
        closed = true;
        closeSound();
        Consumer<Menu> action = closeAction;
        if (action == null) return;
        try {
            action.accept(this);
        } catch (RuntimeException error) {
            VexCore.get().getLogger().log(java.util.logging.Level.SEVERE, file.path() + ": the close action failed", error);
        }
    }

    /**
     * {@code sounds.close}: played when the player really leaves the menu, not when a click only
     * swaps it for another VexCore menu (checked one tick later).
     */
    private void closeSound() {
        SoundSpec sound = file.sound("close");
        VexCore core = VexCore.get();
        if (sound == null || core == null || !core.isEnabled() || !viewer.isOnline()) return;
        try {
            com.vexorstudios.vexcore.core.Scheduler.entityLater(viewer, () -> {
                if (!(viewer.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu)) sound.play(viewer, com.vexorstudios.vexcore.core.SoundGate.MENU);
            }, 1);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    boolean live() {
        return feature.isEnabled();
    }

    Button button(int slot) {
        return buttons.get(slot);
    }

    boolean isEditable(int slot) {
        return editable.contains(slot);
    }

    boolean hasEditable() {
        return !editable.isEmpty();
    }

    void click(int slot, Button button, ClickType type) {
        MenuItem source = button.source;
        if (source == null || !source.silent()) {
            // An item's own sound always plays; the menu's click only on a button that does
            // something (a border or an info item clicked by accident stays quiet).
            boolean acts = button.action != null || (source != null && (source.close() || !source.commandsFor(type).isEmpty()));
            SoundSpec sound = source != null && source.sound() != null ? source.sound() : acts ? file.sound("click") : null;
            // A click: whatever the button does (its reward, an error, the next menu) is heard instead.
            if (sound != null) sound.play(viewer, com.vexorstudios.vexcore.core.SoundGate.CLICK);
        }
        if (source != null) Actions.run(viewer, source.commandsFor(type), button.placeholders, this);
        if (button.action != null) button.action.accept(new Click(this, viewer, type, slot));
        if (source != null && source.close()) viewer.closeInventory();
    }
}
