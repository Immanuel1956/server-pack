package com.vexorstudios.vexcore.core;

/** Normalizes incoming styled letters for matching chat triggers. */
public final class StyledText {

    private static final String FROM = "abcdefghijklmnopqrstuvwxyz";
    private static final String TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    private StyledText() {
    }

    /** "ɢɢ" -> "gg": small caps back to plain letters, for matching what someone typed. */
    public static String plain(String text) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int index = TO.indexOf(c);
            out.append(index >= 0 ? FROM.charAt(index) : c);
        }
        return out.toString();
    }
}
