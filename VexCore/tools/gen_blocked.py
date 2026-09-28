"""Builds features/chatfilter/blocked.yml. Run from the project root:
   python3 tools/gen_blocked.py  (keeps ADVERTISING, LINKS, SCAM and CUSTOM from the current file)."""
import re, sys, yaml

B = r"(?<![\p{L}\p{N}])"      # word start
E = r"(?![\p{L}\p{N}])"       # word end
SUF = r"(?:s|es|ed|er|ers|ing|in|y|z|a)?"
W = r"[\p{L}\p{N}]"

def letters(word):
    return "".join(re.escape(c) + "+" for c in word)

def bounded(word, suffix=SUF):
    """The whole word (repeated letters and the usual endings allowed)."""
    return B + letters(word) + suffix + E

def anywhere(word):
    """Anywhere inside a word: for roots no innocent word contains (clusterfuck, bullshit)."""
    return B + W + "*?" + letters(word) + W + "*"

def phrase(regex):
    """Several words; the folded text has single spaces between words."""
    return B + regex + E

def space(*parts):
    return r"\s*".join(parts)

rules = {}

# ── Slurs ────────────────────────────────────────────────────────────────────
slurs_bounded = [
    # racial / ethnic
    "nigga", "niga", "nigg", "nigr", "nig", "niglet", "negro", "jigaboo", "jiggaboo", "sambo", "darkie", "darky",
    "coon", "golliwog", "sheboon", "spic", "spick", "wetback", "beaner", "kike", "kyke", "heeb", "hymie",
    "chink", "chinky", "gook", "zipperhead", "jap", "raghead", "towelhead", "paki", "wog", "wop", "dago",
    "polack", "redskin", "injun", "squaw", "halfbreed", "abbo", "boong", "gyppo",
    # homophobic / transphobic
    "faggot", "fagot", "fag", "faggy", "dyke", "lesbo", "tranny", "trannie", "shemale",
    # ableist
    "retard", "retarded", "tard", "spaz", "mongoloid",
]
# Short roots only take plurals: the generic endings turn "spic"+"y" into "spicy" and "tard"+"y" into "tardy".
SLUR_SUF = "(?:s|z|es)?"
slur_suffix = {"nigga": "(?:s|z|h|hs)?", "nigg": "(?:a|as|az|ah|er|ers|uh)?", "retard": "(?:s|ed|ation)?", "spic": "(?:s|k|ks)?",
               "spaz": "(?:s|es|ed|ing|y|tic|tics)?", "fag": "(?:s|z|got|gots|gy)?"}
slur_patterns = [bounded(w, slur_suffix.get(w, SLUR_SUF)) for w in slurs_bounded]
slur_patterns += [
    # the worst ones inside any word (sandn*gger, fuckingn*gger) - but not "snigger"
    r"(?<!s)n+i+g+g+(?:e+r+|a+h*|u+h+)" + W + "*",
    W + "*?f+a+g+g+o+t+" + W + "*",
    phrase(space("porch", "monkey") + "s?"),
    phrase(space("jungle", "bunn(?:y|ies)")),
    phrase(space("camel", "jockey") + "s?"),
    phrase(space("sand", "n+i+g+g+e+r+") + "s?"),
    phrase(space("tar", "bab(?:y|ies)")),
    phrase(space("slant", "eyes?")),
]
rules["SLURS"] = dict(
    comment="Racial, ethnic, homophobic, transphobic and ableist slurs. Always dropped.",
    action="CANCEL", alert=True, bypass="", strikes=3, patterns=slur_patterns)

# ── Hate and extremism ───────────────────────────────────────────────────────
# blacks/whites only in the plural: "kill the black team" is PvP talk.
groups = r"(jews?|blacks|muslims?|gays?|trans|asians?|mexicans?|whites|christians?|arabs?|n+i+g+g+e+r+s?|k+i+k+e+s?|f+a+g+g*o*t*s?)"
rules["HATE"] = dict(
    comment="Hate speech and extremist slogans. Always dropped.",
    action="CANCEL", alert=True, bypass="", strikes=3, patterns=[
        phrase(space("heil", "hitler")), phrase(space("sieg", "heil")), phrase(space("white", "power")),
        phrase(space("white", "pride", "world", "wide")),
        r"(?<![\d.])14\s*[/\\|-]\s*88(?![\d.])", r"(?<![\d.$])14\s+88(?![\d.])",
        phrase(space("gas", "the", groups)),
        phrase(space("kill", r"(all\s*)?(the\s*)?" + groups)),
        phrase(space(groups, r"(should|must|need\s*to|deserve\s*to)", r"(die|burn|be\s*killed|be\s*gassed|hang)")),
        phrase(space("holocaust", r"(was\s*)?(fake|a\s*hoax|a\s*lie|never\s*happened|didn\s*t\s*happen)")),
        phrase(space("race", "war") + "s?"),
        bounded("kkk", "") ,
        phrase(space("hang", r"(all\s*)?(the\s*)?" + groups)),
    ])

# ── Threats and self-harm encouragement ─────────────────────────────────────
you = r"(you|u|ya|yu)"
yourself = r"(your|ur|yo|you|u)\s*(self|selves|slf)"
rules["THREATS"] = dict(
    comment="Telling people to hurt themselves, doxxing, swatting and DDoS threats. PvP talk like \"I'll kill you\" is fine.",
    action="CANCEL", alert=True, bypass="", strikes=2, patterns=[
        bounded("kys", ""), bounded("kms", ""),
        phrase(space("k+i+l+l+", yourself)),
        phrase(space(r"(hang|neck|shoot|end|off|rope|unalive|stab|drown)", yourself)),
        phrase(space(r"(go|pls|please)", "die")),
        phrase(space("hope", you, r"(die|get\s*cancer|get\s*hit\s*by\s*a\s*(car|bus|truck))")),
        phrase(space("drink", "bleach")),
        phrase(space(r"(get|go)\s*cancer")),
        phrase(space("commit", r"(die|suicide|sudoku|toaster\s*bath)")),
        phrase(space(r"(i\s*will|ill|i\s*ll|im\s*gonna|i\s*m\s*gonna|i\s*am\s*going\s*to|gonna)", r"(dox+|swat|ddos)", you)),
        phrase(space(r"(dox+|swat|ddos)(ed|ing)?", r"(you|u|ur|your)")),
        phrase(space(r"(kill|find)", you, "irl")),
        phrase(space("i", r"know\s*where", you, "live")),
    ])

# ── Sexual violence ──────────────────────────────────────────────────────────
rules["SEXUAL_VIOLENCE"] = dict(
    comment="Rape, paedophilia and child abuse. Always dropped.",
    action="CANCEL", alert=True, bypass="", strikes=2, patterns=[
        bounded("rape"), bounded("raping", ""), bounded("rapist"), bounded("raped", ""), bounded("rapey", ""),
        bounded("pedo"), bounded("pedophile"), bounded("pedophilia", ""), bounded("paedo"), bounded("paedophile"),
        bounded("molest"), bounded("molester"), bounded("loli"), bounded("lolicon", ""), bounded("shota"), bounded("shotacon", ""),
        phrase(space("child", r"(porn|p+o+r+n+|abuse)")), bounded("childporn", ""),
    ])

# ── Profanity ────────────────────────────────────────────────────────────────
prof_any = ["fuck", "fvck", "phuck", "phuk", "fucc", "shit", "sh1t", "bitch", "biatch", "whore", "slut",
            "cocksucker", "motherfucker", "asshole", "arsehole", "dickhead", "douchebag", "twat"]
prof_bounded = ["fuk", "fck", "fuq", "fk", "fkn", "fking", "fkin", "effing", "mf", "mofo", "shite", "sht", "ass", "arse",
                "azz", "bastard", "cunt", "cunty", "dick", "cock", "pussy", "wanker", "wank", "prick", "bollocks",
                "bellend", "knobhead", "tosser", "douche", "skank", "thot", "jerkoff", "jackoff", "dumbass", "jackass",
                "fatass", "smartass", "badass", "hitler", "nazi", "retardo", "stfu", "gtfo", "stfd"]
# "cock"+"y" = cocky, "prick"+"ed" = pricked, "dick"+"er" = dicker (to haggle).
prof_suffix = {"cock": "(?:s|er|ers)?", "prick": "(?:s)?", "dick": "(?:s|ing|ed|head|heads)?", "ass": "(?:es|hat|hats|wipe)?",
               "fk": "(?:s|n|ing|in|ed)?", "mf": "(?:s|er|ers|ing)?", "sht": "(?:s|ty)?"}
rules["PROFANITY"] = dict(
    comment="Swearing. The message still goes out, with the words masked.",
    action="REPLACE", alert=False, bypass="", strikes=1,
    patterns=[anywhere(w) for w in prof_any] + [bounded(w, prof_suffix.get(w, SUF)) for w in prof_bounded] + [
        phrase(r"(bull|horse|dip|chicken|dog|bat|ape|jack|dumb)\s*(shit|sh1t)\w*"),
        phrase(space("son", "of", "a", r"(bitch|b\*+ch)")),
    ])

# ── Sexual content ───────────────────────────────────────────────────────────
sex_any = ["porn", "hentai", "masturbat", "cumshot", "creampie", "gangbang", "bukkake", "deepthroat", "blowjob",
           "handjob", "rimjob", "onlyfans", "dildo"]
sex_bounded = ["sex", "sexy", "sexting", "cum", "jizz", "boob", "boobs", "boobies", "tits", "titty", "titties", "nudes",
               "nude", "horny", "penis", "vagina", "clit", "boner", "orgasm", "fap", "milf", "anal", "semen", "nsfw",
               "rule34", "r34", "thicc", "cuck"]
# "cum"+"in" = cumin, "boob"+"y" = booby trap.
sex_suffix = {"cum": "(?:s|shot|shots|ming|med|slut)?", "boob": "(?:s|ies)?", "sex": "(?:y|es|ual|ually|ting)?", "anal": "(?:s)?",
              "fap": "(?:s|ping|ped)?", "cuck": "(?:s|ed|old|olds)?", "nude": "(?:s)?", "clit": "(?:s|oris)?"}
rules["SEXUAL"] = dict(
    comment="Sexual words. Masked like profanity; switch action to CANCEL for a stricter (younger) server.",
    action="REPLACE", alert=False, bypass="", strikes=1,
    patterns=[anywhere(w) for w in sex_any] + [bounded(w, sex_suffix.get(w, SUF)) for w in sex_bounded] + [
        phrase(space("send", r"(nudes|pics|noods)")),
        phrase(space(r"(suck|lick|eat)", r"(my|ur|your)", r"(dick|cock|pussy|balls|ass|d)")),
    ])

# ── Mild ─────────────────────────────────────────────────────────────────────
mild = ["damn", "dammit", "goddamn", "hell", "crap", "crappy", "piss", "pissed", "pissing", "wtf", "wth", "omfg",
        "bugger", "bloody", "arsed", "frick", "frigging"]
rules["MILD"] = dict(
    comment="Mild words (damn, hell, wtf...). Off by default; turn on for a family-friendly server.",
    action="REPLACE", alert=False, bypass="", strikes=0, enabled=False,
    patterns=[bounded(w) for w in mild])

# ── Personal information ─────────────────────────────────────────────────────
rules["PERSONAL_INFO"] = dict(
    comment="E-mail addresses and phone numbers, so players can't dox each other (or themselves).",
    action="CANCEL", alert=True, bypass="vexcore.chatfilter.bypass.personalinfo", strikes=0, patterns=[
        r"(?<![\p{L}\p{N}._%+-])[\p{L}\p{N}._%+-]{1,64}@[\p{L}\p{N}-]{1,63}(\.[\p{L}\p{N}-]{1,63})*\.[\p{L}]{2,24}(?![\p{L}\p{N}])",
        r"(?<![\p{N}])\+?\(?\d{3}\)?[\s.-]\d{3}[\s.-]\d{4}(?![\p{N}])",
        r"(?<![\p{N}+])\+\d{1,3}[\s.-]?\d{2,4}[\s.-]?\d{3,4}[\s.-]?\d{3,4}(?![\p{N}])",
    ])

# ── Write ────────────────────────────────────────────────────────────────────
path = "src/main/resources/features/chatfilter/blocked.yml"
with open(path) as fh:
    old = yaml.safe_load(fh)["rules"]
# PERSONAL_INFO first: an e-mail address also looks like a site advert.
order = ["PERSONAL_INFO", "ADVERTISING", "LINKS", "SLURS", "HATE", "THREATS", "SEXUAL_VIOLENCE", "PROFANITY", "SEXUAL", "MILD",
         "SCAM", "CUSTOM"]

def q(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'

exceptions = ["moby dick", "scunthorpe", "shitake", "shitakes", "cocktail", "cocktails", "cockatoo", "cockatoos", "cockpit", "cockpits",
              "peacock", "peacocks", "hancock", "dickens", "dickson", "dickinson", "pussycat", "pussywillow", "analysis",
              "analyst", "sussex", "essex", "middlesex", "therapist", "therapists", "grapes", "cumulative", "cumbersome",
              "document", "specialist", "raccoon", "cocoon", "japan", "japanese", "nigeria", "niger", "snigger",
              "sniggering", "niggle", "niggling", "assassin", "assassins", "hitchcock", "babcock", "woodcock",
              "shuttlecock", "weathercock", "titmouse", "sexton", "sextant", "fukushima", "fukuoka"]

out = []
out.append("""# Chat filter rules. Generated from tools/gen_blocked.py; edit here or there.
#
# A CANCEL hit anywhere stops the message; otherwise every REPLACE hit is masked; a WARN only alerts.
#
# Every rule runs three times: on the raw message; on a folded copy where accents, zalgo,
# invisible characters, look-alike letters from other alphabets (Cyrillic, Greek, fullwidth,
# small caps, fancy fonts) and leetspeak (4 a, 3 e, 1 i, 0 o, $ s, @ a, 7 t, 5 s, 8 b, | i...) are
# turned back into plain letters and every other character becomes a word break; and on a copy
# where short pieces are glued back together, so "f u c k" reads as one word while "who read"
# stays two. A hit made only of digits never counts (prices like 455 or 7175 stay clean).
#
# action   REPLACE masks the hit, CANCEL drops the message, WARN only reports
# alert    sends it to everyone with vexcore.chatfilter.alerts
# bypass   a permission that skips this rule, "" means nobody skips it
# strikes  how many strikes a hit adds (config.yml: strikes), default 1, WARN rules 0
# commands console commands run on every hit (%player% %uuid% %rule%), default none
#
# /chatfilter regex <word> adds a pattern to CUSTOM, /chatfilter remove <word> takes it out,
# /chatfilter test <message> shows which rule catches a message, /chatfilter reload re-reads this.

# Innocent words that contain a caught one. These are never caught, in any rule.
exceptions:""")
for e in exceptions:
    out.append("  - " + q(e))
out.append("\nrules:")
for name in order:
    r = rules.get(name)
    if r is None:
        o = old[name]
        r = dict(action=o.get("action"), alert=o.get("alert", True), bypass=o.get("bypass", ""), patterns=o.get("patterns") or [],
                 enabled=o.get("enabled", True), comment={"ADVERTISING": "Server, site and IP adverts, also written \"site . com\", \"site [dot] com\" and \"1 2 7 . 0 . 0 . 1\".",
                 "LINKS": "Links and Discord invites.", "SCAM": "Free rank, cheap accounts and similar scams.",
                 "CUSTOM": "What /chatfilter regex adds."}[name])
        if name == "CUSTOM": r["strikes"] = 1
    out.append("")
    out.append("  # " + r["comment"])
    out.append(f"  {name}:")
    out.append(f"    enabled: {'true' if r.get('enabled', True) else 'false'}")
    out.append(f"    action: {r['action']}")
    out.append(f"    alert: {'true' if r['alert'] else 'false'}")
    out.append(f"    bypass: {q(r['bypass'])}")
    if "strikes" in r: out.append(f"    strikes: {r['strikes']}")
    if not r["patterns"]:
        out.append("    patterns: []")
    else:
        out.append("    patterns:")
        for p in r["patterns"]:
            out.append("      - " + q(p))
with open(path, "w") as fh:
    fh.write("\n".join(out) + "\n")
total = sum(len(r["patterns"]) for r in rules.values())
print("written", path, "generated patterns:", total)
