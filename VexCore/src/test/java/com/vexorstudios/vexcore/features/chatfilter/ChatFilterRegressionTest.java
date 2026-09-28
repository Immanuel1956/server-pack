package com.vexorstudios.vexcore.features.chatfilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChatFilterRegressionTest {
    ChatFilterFeature filter;
    Method first;
    @BeforeEach void loadRealRules() throws Exception {
        filter = new ChatFilterFeature();
        var file = ChatFilterFeature.class.getDeclaredField("rulesFile"); file.setAccessible(true);
        file.set(filter, new File("src/main/resources/features/chatfilter/blocked.yml"));
        var load = ChatFilterFeature.class.getDeclaredMethod("loadRules"); load.setAccessible(true); load.invoke(filter);
        assertTrue(filter.problems().isEmpty(), filter.problems().toString());
        first = ChatFilterFeature.class.getDeclaredMethod("firstRule", Player.class, String.class); first.setAccessible(true);
    }
    @SuppressWarnings("unchecked") Map.Entry<ChatFilterFeature.Rule, List<int[]>> hit(String text) throws Exception {
        return (Map.Entry<ChatFilterFeature.Rule, List<int[]>>) first.invoke(filter, null, text);
    }
    @Test void swearsAreReplacedAcrossObfuscations() throws Exception {
        for (String text : List.of("fuck", "f u c k", "f.u.c.k", "fυck", "ＦＵＣＫ", "f\u200Bu\u200Bc\u200Bk", "fuuuck")) {
            var hit = hit(text); assertNotNull(hit, text); assertEquals(ChatFilterFeature.Action.REPLACE, hit.getKey().action(), text);
        }
    }
    @Test void cancelsOverrideReplacement() throws Exception {
        for (String text : List.of("fuck visit example.com", "discord.gg/abc", "127.0.0.1", "example [dot] com")) {
            var hit = hit(text); assertNotNull(hit, text); assertEquals(ChatFilterFeature.Action.CANCEL, hit.getKey().action(), text);
        }
    }
    @Test void ordinaryWordsDoNotTripSubstrings() throws Exception {
        for (String text : List.of("hello everyone", "class assignment", "grass blocks", "who read the book", "Scunthorpe", "I like this server"))
            assertNull(hit(text), text);
    }
    @Test void replacementMasksEverySwearAndPreservesSurroundings() throws Exception {
        String raw = "hello fuck and shit goodbye";
        var result = hit(raw); assertNotNull(result);
        var mask = ChatFilterFeature.class.getDeclaredMethod("mask", String.class, List.class); mask.setAccessible(true);
        assertEquals("hello *** and *** goodbye", mask.invoke(filter, raw, result.getValue()));
    }

    @SuppressWarnings("unchecked")
    Map.Entry<ChatFilterFeature.Rule, List<int[]>> hitAllowing(String text, String... allowed) throws Exception {
        var field = ChatFilterFeature.class.getDeclaredField("allowed"); field.setAccessible(true);
        List<java.util.regex.Pattern> list = new java.util.ArrayList<>();
        for (String a : allowed) list.add(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(a), java.util.regex.Pattern.CASE_INSENSITIVE));
        field.set(filter, list);
        var strip = ChatFilterFeature.class.getDeclaredMethod("withoutAllowed", String.class); strip.setAccessible(true);
        return (Map.Entry<ChatFilterFeature.Rule, List<int[]>>) first.invoke(filter, null, strip.invoke(filter, text));
    }
    @Test void allowedLinksPassButOthersStillBlock() throws Exception {
        assertNull(hitAllowing("join play.myserver.com now", "play.myserver.com"));
        assertNull(hitAllowing("our discord: DISCORD.GG/MYSERVER", "discord.gg/myserver"));
        var other = hitAllowing("join play.myserver.com or other.com", "play.myserver.com");
        assertNotNull(other); assertEquals(ChatFilterFeature.Action.CANCEL, other.getKey().action());
    }
    @Test void allowedTextKeepsMaskPositions() throws Exception {
        String raw = "myserver.com fuck";
        var hit = hitAllowing(raw, "myserver.com"); assertNotNull(hit);
        var mask = ChatFilterFeature.class.getDeclaredMethod("mask", String.class, List.class); mask.setAccessible(true);
        assertEquals("myserver.com ***", mask.invoke(filter, raw, hit.getValue()));
    }
    @Test void rulesCarryStrikeDefaults() throws Exception {
        var hit = hit("fuck"); assertNotNull(hit);
        assertEquals(1, hit.getKey().strikes());
        assertTrue(hit.getKey().commands().isEmpty());
    }

    @SuppressWarnings("unchecked")
    Map.Entry<ChatFilterFeature.Rule, List<int[]>> scrubbed(String text) throws Exception {
        var strip = ChatFilterFeature.class.getDeclaredMethod("withoutAllowed", String.class); strip.setAccessible(true);
        return (Map.Entry<ChatFilterFeature.Rule, List<int[]>>) first.invoke(filter, null, strip.invoke(filter, text));
    }
    @Test void slursHateAndThreatsAreDropped() throws Exception {
        for (String[] c : new String[][]{{"SLURS", "n1gger"}, {"SLURS", "sandnigger"}, {"SLURS", "f4ggot"}, {"SLURS", "ur a retard"},
                {"HATE", "heil hitler"}, {"HATE", "14/88"}, {"HATE", "gas the jews"}, {"THREATS", "k y s"}, {"THREATS", "kill urself"},
                {"THREATS", "ill dox you"}, {"SEXUAL_VIOLENCE", "got raped"}, {"PERSONAL_INFO", "bob.smith@gmail.com"}}) {
            var hit = scrubbed(c[1]); assertNotNull(hit, c[1]); assertEquals(c[0], hit.getKey().name(), c[1]);
            assertEquals(ChatFilterFeature.Action.CANCEL, hit.getKey().action(), c[1]);
        }
    }
    @Test void numbersAloneNeverMatch() throws Exception {
        // Leetspeak reads 455 as "ass" and 7175 as "tits": a hit needs a real letter.
        for (String text : List.of("selling 455 diamonds", "tp 7175 64 -200", "price 5318008", "$14.88 each", "1488 cobble"))
            assertNull(scrubbed(text), text);
        assertNotNull(scrubbed("5h1t"), "leetspeak with letters is still caught");
    }
    @Test void innocentWordsAndPvpTalkStayClean() throws Exception {
        for (String text : List.of("shitake mushrooms", "cocktail", "dont be cocky", "you are tardy", "add cumin", "booby trap",
                "spicy food", "therapist", "raccoon", "Nigeria", "snigger", "Pakistan", "flame retardant", "diamond hoe", "Moby Dick",
                "kill the black team", "I will kill you in the duel", "if you just die you respawn", "Fukushima", "Sussex"))
            assertNull(scrubbed(text), text);
    }
    @Test void symbolFloodIsMeasured() {
        assertTrue(ChatFilterFeature.symbolPercent("▓▓▓▓▓▓▓▓▓▓ hi") > 60);
        assertTrue(ChatFilterFeature.symbolPercent("hello there, how are you?") < 20);
    }
}
