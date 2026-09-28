# VexCore update: install and configuration

## Round 22 (staff mode and mute fixes, rollback safety)

Nothing to change: install the new jar. Optional: add `backup-before-restore: true` to `features/invrollback/config.yml` to see the new switch (it is on without it).

Quick test: /staff, open a chest and shift-click an item out, /staff again (the item is in your inventory). /staff, `/kill` yourself (nothing drops, tools are back after respawn). `/mute <alt> 5m`, then `!hi` as the alt in a team (refused with the mute message). Restore a rollback backup, then find "before restore by <you>" under AUTO.

## Round 21 (one sound per action, fixes, speed)

Nothing to change: install the new jar. One sound per action is on by default. To see the switch in your file, add `one-per-action: true` under `sounds:` in `config.yml` (`false` plays every sound again, on top of each other).

Quick test: open /boosts and activate one (only the boost sound, no click on top), click an info item (silent), click a button with no money (only the error sound), open /socials and click Discord (only the link sound).

## Round 20 (keyall placeholders, permission and placeholder lists)

Nothing to change: install the new jar. `/vexcore permissions` and `/vexcore placeholders` work at once (the texts come from the jar's `globalmessages.yml` until you copy the `list-*` keys into yours to change them), and `permissions.txt` / `placeholders.txt` appear in the plugin folder. For `%vexcore_keyall_at%` in another time zone, copy `placeholder:` from the bundled `features/server/keyall.yml`. Your existing `/vexcore` help text doesn't list the two new commands; copy `core-help` from the bundled `globalmessages.yml` if you want it to.

## Round 19 (sounds, boosts, chat hover, name tags, social folder, op bypass)

Build with JDK 21: `mvn clean package`, then install `target/VexCore-1.0.0.jar` (keep `target/proguard-mapping.txt`). Back up the `plugins/VexCore` folder first: this update moves files.

1. **Files move on first start.** The small features' folders are moved into shared folders: `features/discord/config.yml` becomes `features/social/discord.yml`, `features/rules/gui/rules.yml` becomes `features/social/gui/rules.yml`, and the same for `teleport/`, `toggles/`, `pvp/`, `staff/`, `server/` and `utility/` (the list is at the top of the new `config.yml`). Your settings are kept. The console says what was moved; if it warns that an old folder is "no longer read", something (a `.bak` file) is left in it: move what you need and delete the folder.
2. **config.yml is not overwritten.** Nothing is required: the new `sounds:` and `op-bypass:` settings use their defaults (quiet, ops skip the chat filter). To see and edit them, copy the `sounds:` and `op-bypass:` blocks from the bundled `config.yml`, or rename yours to `config.yml.bak`, restart, and copy your `features:` switches back. New features (`socials`, `links`, `broadcast`) count as on when missing.
3. **Sounds:** nothing to do. For the old behaviour: `sounds.ticking: true` (every countdown second) and `sounds.click-on-messages: true` (the message click).
4. **Name tags** switch to `mode: FOLLOW` automatically (the key is new). Optional in `features/server/nametags.yml`: `y-offset: 0.3`, `teleport-duration: 2`, `hide-own: true`, `hide-empty-lines: true`; remove `line-gap`. If you liked the PacketEvents riding, set `mode: RIDE`.
5. **Chat:** copy `hover.staff` and `keep-format-on-top` from the bundled `features/chat/config.yml` to change them (they default to on). Give moderators `vexcore.chat.manage` (ops have it). If another chat plugin should format the chat instead, set `keep-format-on-top: false`.
6. **Boosts:** optional: copy `one-at-a-time`, `status.none`, the `already-active` / `already-running` messages and sounds from the bundled `features/boosts/config.yml`, and the `active` item (slot 4, border `0-3, 5-10, 16-35`) from `gui/boosts.yml`. The rule works without them.
7. **Social:** set your links in `features/social/discord.yml`, `store.yml`, `apply.yml`; turn on extra links in `links.yml`; review `broadcast.yml` (every 5 minutes; `broadcast: false` in config.yml turns it off). `features/server/commandwhitelist.yml` is not overwritten: add `socials, social, links` (and the names of the links you turn on) to the default group.

Quick test: `/spawn` (one pling, then quiet until the teleport), hit a player (no ticking while tagged). `/boosts`: activate speed, buy the reset, try strength (refused, "one boost at a time"). Chat with an op and a normal account: both see the profile card on the name; the op's card ends with "CLICK to manage" and a click opens `/punish`. Walk, sprint, go through a nether portal and `/spawn` from another world: the tag stays on the head, lines stacked. As op, swear and post a link in chat (not blocked). `/socials`, `/website` after enabling it.

## Round 18 (warps, player warps, all-time alt links, giveaway cancel, banner)

Build with JDK 21: `mvn clean package`, then install `target/VexCore-1.0.0.jar` (keep `target/proguard-mapping.txt`). New files (`features/warps`, `features/pwarps`) are created on start, new database tables and columns too. Both warp features are on by default.

1. **Warps:** stand somewhere and `/setwarp shop` with the icon item in your hand. Optional: `per-warp-permission: true` in `features/warps/config.yml`.
2. **Player warps:** review `features/pwarps/config.yml`: `cost` (100k, +100k per warp), `default-slots: 3`, `blocked-worlds`, `refund-percent`. Give paid ranks `vexcore.pwarps.5` (or any number) in LuckPerms, or list groups under `permission-slots`.
3. **Command whitelist:** add `warp, warps, pwarp, pwarps, pw` to the default group in `features/commandwhitelist/config.yml`, and `setwarp, delwarp` to the staff group (existing files are not overwritten).
4. **Alt links** are on and all-time. If a school, café or family network links too many players, add that IP to `exempt-ips` or give the players `vexcore.ipprotection.bypass`. To copy the new `link:` settings into your file (optional; defaults apply), see the bundled `features/ipprotection/config.yml`. The `/alts` lines gain `%link%`; merge the new `alts-*` messages and `words:` if you want it shown.
5. **Giveaways:** existing `features/giveaway/config.yml` keeps working; merge (or rename to `.bak`) to get the new messages: `won-title` (the join title), `*-while-away`, `cancel-*`, `cancelled*`, `forcecancel-*`, and the rules `owner-can-cancel` / `owner-cancel-with-entries`. Staff need `vexcore.giveaway.forcecancel` (op by default).
6. **Banner:** `startup-banner: SIMPLE` in `config.yml` if your console shows `?` or boxes.

Quick test: `/setwarp test`, `/warps`, `/warp test`. `/pwarp set myshop` (100k taken), again with another name (200k), `/pwarp` shows both; `/pwarp cost`; a 4th warp is refused without `vexcore.pwarps.4`. Enter a giveaway with an alt that once joined from your IP (refused, also after a restart). Start a giveaway, `/giveaway cancel`; start another, `/giveaway forcecancel <you> test`. Win a giveaway while offline (short duration, second account enters, log it off): on join, the CONGRATS title shows.

## Round 17 (chat filter word lists)

1. `features/chatfilter/blocked.yml` is **not** overwritten on existing servers. To get the new lists: copy your `CUSTOM` patterns somewhere, rename `blocked.yml` to `blocked.yml.bak`, restart (or `/vexcore reload`), paste your CUSTOM patterns back and run `/chatfilter reload`.
2. Review the rules: set `SEXUAL` to `action: CANCEL` for a younger server, turn `MILD` on (`enabled: true`) for a family-friendly one, and add words to `exceptions:` if something innocent is caught on your server. `/chatfilter test <message>` shows which rule catches a message.
3. The `symbols` check is on by default; copy the `symbols:` block and message from the bundled `features/chatfilter/config.yml` to change it.
4. Slurs and hate give 3 strikes each, so with the default `strikes.punishments` one hit mutes for 5 minutes. Change it per rule with `strikes:`.

### Giveaways (money or items)

Existing giveaway files are not overwritten. They keep working, and money giveaways already work with them. For the new text and buttons, rename these to `.bak` and let VexCore regenerate them, or copy the changes over:
- `features/giveaway/config.yml`: `winner`, `you-won`, `no-entries` gain `%prize%` (keep your durations/rules).
- `features/giveaway/gui/create.yml`: `back` at slot 36; `border` becomes `37-39, 41-44`.
- `features/giveaway/gui/preview.yml`: `money-slot: 22` and `templates.money`.
- `features/giveaway/gui/main.yml`: the Prize line in `templates.entered`.

Quick test: `/gws` → CREATE → MONEY, type `1000`, START; a second account enters; after the timer the winner takes it from Your Prizes and the balance rises by $1,000. Right click the giveaway and the claim to see the money preview. Then CREATE → ITEMS, put in items, press BACK: the items are back in your inventory.

## Round 16 (punishments, staff mode, essentials, votes, events, streaks)

Build with JDK 21: `mvn clean package`, then install `target/VexCore-1.0.0.jar`. New feature folders are created on start; existing files are not overwritten.

1. **Command clashes.** VexCore now registers `/ban`, `/kick`, `/mute`, `/warn`, `/history`, `/fly`, `/heal`, `/feed`, `/god`, `/speed`, `/invsee`, `/clear(inventory)`, `/vote` and more. If another plugin (EssentialsX, LiteBans, a vote plugin) should keep one, set `enabled: false` for it in `commands.yml`, or turn the whole feature off in `config.yml` (`punishments`, `staffessentials`, `votes`...). Running two punishment plugins at once is not recommended.
2. **Punishments:** set `appeal`, `server-name` and `max-temporary` in `features/punishments/config.yml`; review `reasons` (the /punish menu ladders) and `warnings.actions`. IP bans need `features.ipprotection` (and IP forwarding behind a proxy). Existing bans in other plugins are not imported.
3. **Staff mode:** give staff `vexcore.staffmode`. Adjust the tools in `features/staffmode/config.yml`.
4. **Votes:** install NuVotifier, then list your sites under `sites:` with the exact `service` name each sends (watch the NuVotifier console line on a test vote). Set rewards and the party.
5. **Events:** stand on your KOTH hill and run `/event koth set 5`. Tune `auto.interval`, `auto.minimum-players` and rewards.
6. **Daily streak:** copy `streak:` and the new messages from the bundled `features/daily/config.yml`, and the Streak lines into `gui/daily.yml` (or rename both to `.bak`).
7. **Keyall:** copy `align-to-clock` and `bossbar` from the bundled `features/keyall/config.yml` if you want them visible in your file (they default to on).
8. Existing `features/commandwhitelist/config.yml`: add `vote, votes, votetop, voteparty, vp, event, events` to the default group and the staff commands (see the bundled file) to the staff group.

Quick test: `/staff` on and off (inventory back?), stop the server while in staff mode and rejoin; `/ban <alt> 1m test` then rejoin with the alt; `/mute <alt> 1m` and chat; `/punish <alt>` twice with the same reason (duration climbs); `/fakevote <you>`; `/event start chatgame`; claim `/daily` on two days in a row (or `/daily reset` between tests).

## Round 15 (invest rate and cap)

1. In `features/invest/config.yml` replace `income.per` and `income.per-add` with `income.rate-per-second: 0.00001` (the same return as the old defaults) and add `max-limit: 100000000`. Until you do, your old values keep working and the cap is 100M.
2. For the new menu lines, copy the Income/Return/Server cap lines from the bundled `gui/invest.yml`.

## Round 14 (IP protection, paged homes)

Build with JDK 21: `mvn clean package`, then install `target/VexCore-1.0.0.jar`.

1. **Proxy servers (Velocity/BungeeCord):** player IP forwarding must be on, otherwise every player seems to share the proxy's address and IP protection would treat the whole server as one network. Check with `/alts <you>` on two different accounts from two different networks: they must not list each other. If you can't forward IPs, set `features.ipprotection: false`.
2. `features/ipprotection/config.yml` is created on start. Review `max-accounts-per-ip` (1), `protect:` and `kill-farming`. Give `vexcore.ipprotection.bypass` to trusted households (e.g. siblings), or list school/café IPs under `exempt-ips`.
3. Keep `data/ipprotection.yml` (the salt). Losing it only means networks are recognised afresh.
4. Homes: `features/home/gui/homes.yml` is not overwritten. For pages, add the `previous-page` (48) and `next-page` (50) items and the `page` sound from the bundled file, and add `[%page%/%pages%]` to the title, or rename your file to `.bak` to regenerate it. Then raise `max-homes` in `features/home/config.yml` and optionally add `permission-homes` and `show-locked-homes`.
5. Invest: copy `words:` from the bundled `features/invest/config.yml` and the `This account earns: %earning%` line into `gui/invest.yml` if you kept your old files.

Quick test (two accounts on the same network): only one earns invest income and gets keyall keys; the second can't `/invest add`, can't enter a giveaway the first entered, can't claim a kill/playtime/daily reward the first claimed, gets no GG money after the first; killing one with the other doesn't raise kills. `/alts <name>` lists the other. Homes: set `max-homes: 28`, give yourself `vexcore.home.20`, open `/home` and page through.

## Round 13 (invest, chat filter)

Build with JDK 21: `mvn clean package` (runs the tests, including the new ones), then install `target/VexCore-1.0.0.jar`. The database upgrades itself.

1. `features/invest/config.yml` is not overwritten. Copy `withdraw:`, `offline-income:`, `top:` and the new messages/sounds from the bundled file (or rename yours to `.bak`). Missing settings use the defaults anyway, but the new messages only appear once they are in the file.
2. For the new buttons, copy `withdraw` (slot 14) and `top` (slot 22) and the extra info lines into `features/invest/gui/invest.yml`, or regenerate it.
3. `features/chatfilter/config.yml`: copy `allowed:` (put in **your** domains and Discord invite), `join-delay-seconds:`, `strikes:` and the new messages. Without `allowed:` your own links stay blocked as before.
4. Optional in `blocked.yml`: per-rule `strikes:` and `commands:` (see the comment at the top of the bundled file).
5. Set `strikes.punishments` to taste. The built-in mute needs no other plugin; add your punishment plugin's commands under `commands:` if you prefer those.

Quick test: `/chatfilter test join yourserver.com` (after adding it to `allowed`) says no rule catches it; swear three times on a test account and check the 5 minute mute and the staff alert; `/chatfilter clear <player>`. `/invest withdraw 1000`, `/invest top`; log out for 5+ minutes with money invested and check the "while you were away" message.

## Round 12 (phantoms, fixes)

Build first with JDK 21: `mvn clean package`, then install `target/VexCore-1.0.0.jar`.

1. `features.phantoms` counts as on when missing; add `phantoms: true` under `features:` in `config.yml` to make it visible. `features/phantoms/config.yml` is created on start.
2. Existing `features/settings/gui/settings.yml` is not overwritten. Copy the `phantoms:` template from the bundled file (it sits right before `joinmessages:`), or rename your file to `.bak` to regenerate it. If you change `radius`, also change "50 blocks" in that button's lore.
3. Existing `features/commandwhitelist/config.yml`: add `phantoms, phantomtoggle` to the default group.
4. Nothing else needs changing; the date-pattern fix and the speed-ups apply automatically.

Quick test: turn on DESPAWN PHANTOMS in /settings, stay awake 3+ in-game nights (or `/summon phantom` near you) and check they vanish within 2 seconds; turn it off and they stay.

## Round 11


This source has **not been compiled yet**. Build it first with JDK 21: `mvn clean package` (or `mvn clean package -Ddev` for a readable jar), then install `target/VexCore-1.0.0.jar`. If the build stops at `TextDialogInput.MultilineOptions` in `features/reports/ReportDialog.java`, delete that one `if (lines > 1) input.multiline(...)` line; the reason box becomes one line and everything else works.

1. In `config.yml` add `reports: true`, `invrollback: true` and `tebex: true` under `features:` (missing keys already count as on).
2. Reports: set `webhook.url` (and `enabled: true`) in `features/reports/config.yml`, optionally `webhook-resolved` too. Set `server-name`. To ping a role, put `<@&ROLE_ID>` in `content` and the id in `ping-roles`. Staff need `vexcore.reports`, `vexcore.reports.notify` and `vexcore.reports.manage` (all op by default).
3. Rollback: staff need `vexcore.invrollback` and `vexcore.invrollback.restore`. Adjust `interval-minutes` and `keep` per trigger in `features/invrollback/config.yml`. The database grows with the number of players times 4 x `keep` backups.
4. Tebex: put your secret key in `features/tebex/config.yml: secret` (Tebex panel > Integrations > Game Servers). Edit `goals.list` (targets and reward commands) or set `goals.source: community`. Set `purchase.gg-wave.money` for the GG reward. For instant announcements, add `tebexpurchase {username} {price} {packageName}` as a package command (check the exact variable names in your Tebex panel) and set `poll-payments: false`.
5. Existing installs: `features/commandwhitelist/config.yml` is not overwritten. Add `report, goal, goals, storegoal` to the default group and `reports, invrollback, rollback, invrb, restoreinv` to the staff group. `features/ggwave/config.yml` is not overwritten either: add `money: 0` and the `rewarded` message/sound from the bundled file if you want them.

Test on a staging server: a report through the dialog and through `/report <player> <reason>`, staff alert click, both heads, `/report resolve`, Discord embed; rollback of each trigger, restore and preview; `/tebexpurchase <you> 5 Test` for the head art, boss bar and GG payout; one real Tebex check with the secret key.

## Round 10

The installable plugin is `target/VexCore-1.0.0.jar` (obfuscated). Stop the server, back up the existing VexCore folder, replace the old JAR, make the changes below, then start the server. Keep the source and ProGuard mapping private; only upload the installable JAR to your server's plugins folder.

Existing files are preserved. Missing settings use bundled defaults in memory; new feature files are created automatically. Existing lists and explicitly disabled features keep their old values. Do not delete the data folder or database.

## Required changes for an existing installation

1. In `config.yml`, enable `features.commandwhitelist: true` and `features.commandroutes: true` if you want strict blocking and the command routes. Enable `features.ranktrial: true`. Obsolete `features.smallcaps` has no effect and may be removed.
2. In `features/commandwhitelist/config.yml`, set `allow-vexcore-commands: false` for strict lists, and copy/review the updated `groups` lists from the bundled config. Add commands from your other plugins and any renamed VexCore commands/aliases. Add `sounds.blocked: {sound: "entity.villager.no", volume: 1.0, pitch: 1.0}` to customize the rejection sound. Staff with `vexcore.commandwhitelist.bypass` are exempt. This controls backend Paper commands; configure your Velocity command filter separately.
3. For the updated giveaway text/layout, rename these files to `.bak` and let VexCore regenerate them, or merge their changes:
   - `features/giveaway/gui/main.yml`
   - `features/giveaway/gui/claims.yml`
   - `features/giveaway/config.yml` (preserve your durations/rules if merging)
   The new `gui/type.yml` and `gui/money.yml` are created automatically. Giveaway texts and templates use `%prize%` for both money and item counts. Old `%items%` templates would display zero for a money prize. Older item giveaways remain readable; do not downgrade to an earlier JAR while money giveaways/claims exist.
4. Configure `features/ranktrial/config.yml`: put actual LuckPerms group names under `allowed-ranks`. Default maximum is 30d; default modifier `deny` refuses duplicate temporary grants. Examples: `/ranktrial Steve vip 1h` and `/ranktrial Steve mvp 7d`. The target must be online or cached by the server. LuckPerms confirms the outcome in console.
5. Optional settings to copy into your files for editing:
   - `globalmessages.yml`: `default-sound: {enabled: true, sound: "ui.button.click", volume: 0.6, pitch: 1.2}`. Existing feature/message sounds take priority; `sounds.<key>: ""` keeps that message silent. Empty message strings stay disabled, including sound.
   - `features/kits/config.yml`: `cooldowns-enabled: true`; each kit accepts `cooldown-enabled: true/false` alongside its duration. Setting either switch false disables that kit's cooldown. One-time kits still remain one-time.
   - `features/duel/config.yml`: `start.distance`, `start.min-distance`, `start.countdown-sound`, `start.go-sound`, `start.freeze-movement`, `messages.accepted`, `sounds.accepted`.
   - `features/commandroutes/config.yml`: edit `messages.plugins` and `sounds.plugins`.
   - Remove any custom shortcut overriding `help` or `tutorial` in `commands.yml`; both commands now route to your configured guide command. If you renamed `guide`, the routes follow its configured name.
6. The old small-caps settings button becomes hidden automatically because its toggle no longer exists. You may remove its template and old files. The new mob button says BLOCK MOB SPAWNING; ON blocks configured spawns. Defaults block natural hostile spawns within 50 blocks, preserving spawners, pets/breeding, villagers and iron golems. Configure `features/mobtoggle/config.yml` to change this scope.

## Earlier menu changes in this ZIP

If you have not regenerated the files from the previous handoff, rename these to `.bak` too:

- `features/economy/gui/baltop.yml`
- `features/economy/gui/payhistory.yml`
- `features/rtp/gui/rtp.yml`
- `features/rtp/config.yml` (merge world names/radii if customized)
- `features/home/gui/homes.yml`

## Validation and remaining live checks

The source compiles on JDK 21 against Paper API 1.21.10. All 11 Maven regression tests pass and ProGuard 7.6.1 produces the included JAR. YAML duplicate keys and menu slot bounds were checked. API compatibility and GUI behavior on your exact Paper 1.21.11/Folia server have not been live-tested here.

Before using it with your players, test two accounts for public/team/private ignores and mention pings; one duel with a wager plus one cancelled duel; item/money giveaways, claims, a reconnect and an orderly restart; a cooldown claim and disabled cooldown; rank expiry; tab/blocked commands; and both mob-toggle entry points. MySQL and external Vault-provider behavior need testing on your own setup.

Normal settings, balances, homes, claims, kit cooldowns, team data and stats use the existing database paths. The changes strengthen orderly shutdown/reload handling; they are not a claim of crash-proof exactly-once item/economy transactions. A database failure is logged and still needs administrator attention.

## Rebuild

Use JDK 21 and Maven: `mvn clean package`. The default build runs the tests and ProGuard. `mvn clean package -Ddev` produces a readable development JAR. Use JDK 21 for ProGuard 7.6.1; newer JDK module class formats may be unsupported.

LuckPerms command reference: https://luckperms.net/wiki/Parent-Commands
