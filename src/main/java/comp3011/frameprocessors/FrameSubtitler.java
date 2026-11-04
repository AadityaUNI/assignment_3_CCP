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
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders speech-to-text subtitles onto each frame. Selected with -S / --subtitle-frames, and applied in the effect
 * chain like any other frame processor.
 *
 * <p>
 * The model puts the subtitle for the frame's time into {@link InfoFrame#subs()} (null when there is none). This class
 * only draws it: split the text into at most two lines of at most 42 characters, draw them white with a black outline
 * into a transparent strip the width of the video, then blend that strip onto the bottom of the frame.
 * </p>
 */
public class FrameSubtitler extends FrameProcessor {
    private static final int MAX_LINE_CHARS = 42;
    private static final int MAX_LINES = 2;

    // The strip is the same for every frame that shows the same text, so keep the last one instead of redrawing it
    // 30 times a second. Overlay is immutable, so sharing it between worker threads is safe. If two workers miss at
    // the same moment they both draw an identical strip, which is harmless.
    private final AtomicReference<Overlay> lastOverlay = new AtomicReference<>();

    public FrameSubtitler() {
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        lastOverlay.set(null); // a new video may have a different size, so the old strip no longer fits
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameSubtitler requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameSubtitler requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameSubtitler requires at least three image channels");
        }

        Subtitle subs = info.subs();
        if (subs == null || subs.text().isBlank()) {
            return; // nothing is being said at this moment
        }

        blendOntoFrame(pixels, overlayFor(subs.text()));
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

    // Finds the space nearest the middle of the text where both halves fit on a line, or -1 if there is none.
    // With requirePunctuation, only spaces that come straight after . , ! ? ; : count.
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

    private Overlay overlayFor(String text) {
        Overlay cached = lastOverlay.get();
        if (cached == null || !cached.text().equals(text)) {
            cached = drawOverlay(text);
            lastOverlay.set(cached);
        }
        return cached;
    }

    private Overlay drawOverlay(String text) {
        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();

        // Size everything from the video so subtitles look the same on small and large videos. The width term stops
        // 42 characters running off the edge of a narrow video.
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

        int[] argb = ((DataBufferInt) strip.getRaster().getDataBuffer()).getData();
        return new Overlay(text, stripHeight, argb);
    }

    // ---- Step 3: blend the strip onto the bottom of the frame ----

    private void blendOntoFrame(ByteBuffer pixels, Overlay overlay) {
        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();
        int top = videoInfo.imageHeight() - overlay.height();

        for (int y = 0; y < overlay.height(); y++) {
            int rowStart = (top + y) * stride;
            for (int x = 0; x < width; x++) {
                int argb = overlay.argb()[y * width + x];
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    continue; // transparent: leave the video pixel alone
                }
                int base = rowStart + x * channels;
                // The video is BGR byte order; the strip is ARGB.
                mix(pixels, base, argb & 0xFF, alpha);               // blue
                mix(pixels, base + 1, (argb >> 8) & 0xFF, alpha);    // green
                mix(pixels, base + 2, (argb >> 16) & 0xFF, alpha);   // red
            }
        }
    }

    // Standard alpha blend: alpha of the subtitle colour over the rest of the video colour.
    private void mix(ByteBuffer pixels, int index, int subtitleValue, int alpha) {
        int videoValue = Byte.toUnsignedInt(pixels.get(index));
        pixels.put(index, (byte) ((subtitleValue * alpha + videoValue * (255 - alpha)) / 255));
    }

    // The drawn strip for one piece of text. Never modified after it is built.
    private record Overlay(String text, int height, int[] argb) {
    }
}
