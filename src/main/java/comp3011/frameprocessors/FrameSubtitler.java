/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 *
 * AI acknowledgement: the Java2D text-outline technique and the first draft of this class were written with help
 * from Claude (Anthropic). The line-splitting rules and the structure are what the assignment brief asks for.
 */
package comp3011.frameprocessors;

import org.bytedeco.javacv.Frame;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

// -S effect: draws the frame's subtitle (2 x 42 chars max, white, black outline) into a transparent strip.
// The strip sits on its own layer above the video, not in the pixels, so toggling off is instant.
public class FrameSubtitler extends FrameProcessor {
    private static final int MAX_LINE_CHARS = 42;
    private static final int MAX_LINES = 2;

    // Reuse the last strip while the text is the same; immutable, so safe to share, and lets the FX thread use ==.
    private final AtomicReference<SubtitleOverlay> lastOverlay = new AtomicReference<>();

    public FrameSubtitler() {
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        lastOverlay.set(null); // a new video may have a different size, so the old strip no longer fits
    }

    // Leaves the pixels alone, so no pixel format checks needed.
    @Override
    public void process(Frame frame, InfoFrame info) {
        Subtitle subs = info.subs();
        if (subs == null || subs.text().isBlank()) {
            return; // nothing is being said at this moment, so the overlay stays null
        }

        info.setSubtitleOverlay(overlayFor(subs.text()));
    }

    // ---- Step 1: split the text into lines ----

    // One line if it fits in 42 characters, otherwise two. Package-private so it can be unit tested.
    static List<String> splitIntoLines(String text) {
        text = text.trim();
        if (text.length() <= MAX_LINE_CHARS) {
            return List.of(text);
        }

        // Prefer breaking after punctuation, otherwise at any space. Both options keep each line within 42 characters.
        int space = findBreak(text, true);
        if (space == -1) {
            space = findBreak(text, false);
        }
        if (space == -1) {
            // No usable space (e.g. one enormous word): cut hard rather than draw off the edge of the screen.
            String first = text.substring(0, MAX_LINE_CHARS);
            String rest = text.substring(MAX_LINE_CHARS).trim();
            return List.of(first, rest.substring(0, Math.min(rest.length(), MAX_LINE_CHARS)));
        }
        return List.of(text.substring(0, space), text.substring(space + 1));
    }

    // Space nearest the middle where both halves fit (optionally only after punctuation), or -1.
    private static int findBreak(String text, boolean requirePunctuation) {
        int middle = text.length() / 2;
        int best = -1;
        for (int i = 1; i < text.length() - 1; i++) {
            if (text.charAt(i) != ' ') {
                continue;
            }
            boolean bothHalvesFit = i <= MAX_LINE_CHARS && text.length() - i - 1 <= MAX_LINE_CHARS;
            boolean afterPunctuation = ".,!?;:".indexOf(text.charAt(i - 1)) >= 0;
            if (!bothHalvesFit || (requirePunctuation && !afterPunctuation)) {
                continue;
            }
            if (best == -1 || Math.abs(i - middle) < Math.abs(best - middle)) {
                best = i;
            }
        }
        return best;
    }

    // ---- Step 2: draw the lines into a transparent strip ----

    private SubtitleOverlay overlayFor(String text) {
        SubtitleOverlay cached = lastOverlay.get();
        if (cached == null || !cached.text().equals(text)) {
            cached = drawOverlay(text);
            lastOverlay.set(cached);
        }
        return cached;
    }

    private SubtitleOverlay drawOverlay(String text) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();

        // scale with the video; the width term keeps 42 chars on screen for narrow videos
        float fontSize = Math.min(height / 22f, width / 26f);
        int lineHeight = Math.round(fontSize * 1.2f);
        int bottomMargin = Math.round(fontSize * 0.8f);
        int stripHeight = lineHeight * MAX_LINES + bottomMargin;

        BufferedImage strip = new BufferedImage(width, stripHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = strip.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(fontSize / 6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(fontSize);

            List<String> lines = splitIntoLines(text);
            for (int i = 0; i < lines.size(); i++) {
                // The last line sits just above the bottom margin and an earlier line stacks above it.
                int linesBelow = lines.size() - 1 - i;
                float baseline = stripHeight - bottomMargin - linesBelow * lineHeight;

                GlyphVector glyphs = font.createGlyphVector(g.getFontRenderContext(), lines.get(i));
                float left = (float) ((width - glyphs.getLogicalBounds().getWidth()) / 2.0); // centred
                Shape letters = glyphs.getOutline(left, baseline);

                g.setColor(Color.BLACK);
                g.draw(letters); // the outline: a thick black stroke along the edge of each letter
                g.setColor(Color.WHITE);
                g.fill(letters); // the white letters on top, so only the outer half of the stroke shows
            }
        } finally {
            g.dispose();
        }

        // The strip's own pixel array, row by row. Nothing draws into it after this, so it is safe to share.
        int[] argb = ((DataBufferInt) strip.getRaster().getDataBuffer()).getData();
        return new SubtitleOverlay(text, stripHeight, argb);
    }
}
