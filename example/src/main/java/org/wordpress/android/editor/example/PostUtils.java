package org.wordpress.android.editor.example;

import android.text.TextUtils;

import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.HtmlUtils;

import java.text.BreakIterator;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PostUtils {
    private static final int MAX_EXCERPT_LEN = 150;

    private static final HashSet<String> mShortcodeTable = new HashSet<>();

    /*
     * collapses shortcodes in the passed post content, stripping anything between the
     * shortcode name and the closing brace
     * ex: collapseShortcodes("[gallery ids="1206,1205,1191"]") -> "[gallery]"
     */
    public static String collapseShortcodes(final String postContent) {
        // speed things up by skipping regex if content doesn't contain a brace
        if (postContent == null || !postContent.contains("[")) {
            return postContent;
        }

        String shortCode;
        Pattern p = Pattern.compile("(\\[ *([^ ]+) [^\\[\\]]*\\])");
        Matcher m = p.matcher(postContent);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            shortCode = m.group(2);
            if (isKnownShortcode(shortCode)) {
                m.appendReplacement(sb, "[" + shortCode + "]");
            } else {
                AppLog.d(AppLog.T.POSTS, "unknown shortcode - " + shortCode);
            }
        }
        m.appendTail(sb);

        return sb.toString();
    }

    private static boolean isKnownShortcode(String shortCode) {
        if (shortCode == null) return false;

        // populate on first use
        if (mShortcodeTable.size() == 0) {
            // default shortcodes
            mShortcodeTable.add("audio");
            mShortcodeTable.add("caption");
            mShortcodeTable.add("embed");
            mShortcodeTable.add("gallery");
            mShortcodeTable.add("playlist");
            mShortcodeTable.add("video");
            mShortcodeTable.add("wp_caption");
            // audio/video
            mShortcodeTable.add("dailymotion");
            mShortcodeTable.add("flickr");
            mShortcodeTable.add("hulu");
            mShortcodeTable.add("kickstarter");
            mShortcodeTable.add("soundcloud");
            mShortcodeTable.add("vimeo");
            mShortcodeTable.add("vine");
            mShortcodeTable.add("wpvideo");
            mShortcodeTable.add("youtube");
            // images and documents
            mShortcodeTable.add("instagram");
            mShortcodeTable.add("scribd");
            mShortcodeTable.add("slideshare");
            mShortcodeTable.add("slideshow");
            mShortcodeTable.add("presentation");
            mShortcodeTable.add("googleapps");
            mShortcodeTable.add("office");
            // other
            mShortcodeTable.add("googlemaps");
            mShortcodeTable.add("polldaddy");
            mShortcodeTable.add("recipe");
            mShortcodeTable.add("sitemap");
            mShortcodeTable.add("twitter-timeline");
            mShortcodeTable.add("upcomingevents");
        }

        return mShortcodeTable.contains(shortCode);
    }



    /*
     * Java's string.trim() doesn't handle non-breaking space chars (#160), which may appear at the
     * end of post content - work around this by converting them to standard spaces before trimming
     */
    private static final String NBSP = String.valueOf((char) 160);

    private static String trimEx(final String s) {
        return s.replace(NBSP, " ").trim();
    }

    private static String makeExcerpt(String description) {
        if (TextUtils.isEmpty(description)) {
            return null;
        }

        String s = HtmlUtils.fastStripHtml(description);
        if (s.length() < MAX_EXCERPT_LEN) {
            return trimEx(s);
        }

        StringBuilder result = new StringBuilder();
        BreakIterator wordIterator = BreakIterator.getWordInstance();
        wordIterator.setText(s);
        int start = wordIterator.first();
        int end = wordIterator.next();
        int totalLen = 0;
        while (end != BreakIterator.DONE) {
            String word = s.substring(start, end);
            result.append(word);
            totalLen += word.length();
            if (totalLen >= MAX_EXCERPT_LEN) {
                break;
            }
            start = end;
            end = wordIterator.next();
        }

        if (totalLen == 0) {
            return null;
        }
        return trimEx(result.toString()) + "...";
    }

}
