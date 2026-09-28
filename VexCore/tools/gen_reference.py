"""Builds PERMISSIONS-AND-PLACEHOLDERS.md from plugin.yml, commands.yml and reference.yml.
   Run from the project root: python3 tools/gen_reference.py
   The plugin itself lists the same in game (/vexcore permissions, /vexcore placeholders) and
   writes permissions.txt and placeholders.txt into its folder on every start."""
import re, yaml

RES = "src/main/resources/"
WHO = {"true": "everyone", "op": "op", "false": "nobody", "!op": "not op", "notop": "not op"}


def permissions():
    """(section, node, default, description) in plugin.yml order, sections from its comment lines."""
    out, section, node, info = [], "General", None, {}
    in_perms = False

    def flush():
        if node:
            out.append((section, node, WHO.get(str(info.get("default", "op")).lower(), str(info.get("default"))),
                        info.get("description", "")))

    for line in open(RES + "plugin.yml", encoding="utf-8"):
        if line.startswith("permissions:"):
            in_perms = True
            continue
        if not in_perms:
            continue
        head = re.match(r"^  # ── (.+?) ─", line)
        if head:
            flush(); node, info = None, {}
            section = head.group(1).strip()
            continue
        key = re.match(r"^  ([a-z0-9_.\-]+):\s*$", line)
        if key:
            flush()
            node, info = key.group(1), {}
            continue
        value = re.match(r"^    (description|default):\s*(.*)$", line)
        if value and node:
            info[value.group(1)] = yaml.safe_load(value.group(2)) if value.group(2) else ""
    flush()
    return out


def cell(text):
    return str(text).replace("|", "\\|")


def main():
    ref = yaml.safe_load(open(RES + "reference.yml", encoding="utf-8"))
    commands = yaml.safe_load(open(RES + "commands.yml", encoding="utf-8"))["commands"]
    lines = [
        "# VexCore permissions and placeholders",
        "",
        "Built from `plugin.yml`, `commands.yml` and `reference.yml` by `tools/gen_reference.py`.",
        "",
        "In game (needs `vexcore.admin`): `/vexcore permissions [search] [page]` and `/vexcore placeholders [search] [page]`.",
        "Click an entry to copy it. The plugin also writes `permissions.txt` and `placeholders.txt` into",
        "`plugins/VexCore/` on every start and `/vexcore reload`, with your renamed commands and your own link",
        "commands included.",
        "",
        "**Who has it** is who gets the permission without a permissions plugin: *everyone*, *op* (operators)",
        "or *nobody* (only players or groups you give it to, for example with LuckPerms).",
        "",
        "## Permissions",
    ]
    section = None
    for sec, node, who, desc in permissions():
        if sec != section:
            section = sec
            lines += ["", "### " + sec, "", "| Permission | Who has it | What it does |", "|---|---|---|"]
        lines.append(f"| `{node}` | {who} | {cell(desc)} |")
    lines += ["", "### Made from your settings", "", "| Permission | Who has it | What it does |", "|---|---|---|"]
    for p in ref.get("permission-patterns", []):
        lines.append(f"| `{p['node']}` | {p['default']} | {cell(p['description'])} |")

    lines += ["", "## Commands and their permissions", "",
              "Rename, alias, re-permission or turn off any of these in `commands.yml`.", "",
              "| Command | Aliases | Permission | What it does |", "|---|---|---|---|"]
    for cid, c in commands.items():
        c = c or {}
        if c.get("enabled", True) is False:
            continue
        aliases = ", ".join("/" + a for a in c.get("aliases", []) or [])
        usage = str(c.get("usage", "/%command%")).replace("%command%", str(c.get("name", cid)))
        lines.append(f"| `{cell(usage)}` | {cell(aliases)} | `{c.get('permission', 'vexcore.' + cid)}` | {cell(c.get('description', ''))} |")

    lines += ["", "## Placeholders", "",
              "Every one works in every VexCore file (messages, menus, scoreboard, name tags) even without",
              "PlaceholderAPI, and everywhere else (TAB, holograms, other plugins) through PlaceholderAPI.",
              "`[x]` is a part you fill in.", ""]
    for feature, entries in ref.get("placeholders", {}).items():
        lines += ["### " + feature, "", "| Placeholder | What it shows |", "|---|---|"]
        for e in entries:
            lines.append(f"| `{e['placeholder']}` | {cell(e['description'])} |")
        lines.append("")
    open("PERMISSIONS-AND-PLACEHOLDERS.md", "w", encoding="utf-8").write("\n".join(lines).rstrip() + "\n")
    print("PERMISSIONS-AND-PLACEHOLDERS.md:", sum(1 for l in lines if l.startswith("| `")), "rows")


if __name__ == "__main__":
    main()
