package codes.sharky.steamwidget.utils;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws and measures text with per-character font fallback.
 *
 * <p>The widget renders text with a primary font (Arial). Arial has no glyphs for Japanese, Chinese,
 * Korean, Thai, Arabic and many other scripts, so profile and game names in those scripts used to be
 * rendered as empty boxes. This class splits a string into runs: characters the primary font can
 * display keep the primary font, every other character is drawn with the first fallback font that
 * has a glyph for it.</p>
 *
 * <p>Fallback fonts are looked up in this order:</p>
 * <ol>
 *     <li>fonts bundled on the classpath under {@code fonts/} (listed in {@code fonts/fallback.txt}),</li>
 *     <li>fonts in the directory given by the {@code widget.fonts.dir} system property or the
 *     {@code WIDGET_FONTS_DIR} environment variable,</li>
 *     <li>every font installed on the system (e.g. {@code fonts-noto-cjk} on Debian/Ubuntu).</li>
 * </ol>
 * <p>The font found for a character is cached, so the system-wide search only happens once per
 * character.</p>
 */
public final class FallbackTextRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackTextRenderer.class);

    private static final String BUNDLED_FONT_LIST = "fonts/fallback.txt";
    private static final String FONTS_DIR_PROPERTY = "widget.fonts.dir";
    private static final String FONTS_DIR_ENV = "WIDGET_FONTS_DIR";

    /** Sentinel for "no installed font can display this code point". */
    private static final Font NONE = new Font(Font.DIALOG, Font.PLAIN, 1);

    private static volatile List<Font> fallbackFonts;
    private static final Map<Integer, Font> FONT_FOR_CODE_POINT = new ConcurrentHashMap<>();
    private static final Map<String, Font> BOLD_FACE_FOR_FAMILY = new ConcurrentHashMap<>();

    /** One sample character per script checked by {@link #checkScriptCoverage()}. */
    private static final Map<String, String> SCRIPT_SAMPLES = new LinkedHashMap<>();
    static {
        SCRIPT_SAMPLES.put("Japanese", "あ");
        SCRIPT_SAMPLES.put("Chinese", "中");
        SCRIPT_SAMPLES.put("Korean", "한");
        SCRIPT_SAMPLES.put("Thai", "ก");
    }

    private FallbackTextRenderer() {}

    /**
     * Loads the fallback fonts and logs which common non-Latin scripts can be rendered. Logs a
     * warning listing the scripts no font can display, since text in those scripts will still be
     * drawn as empty boxes until a suitable font is installed (see DEVELOPMENT.md).
     */
    public static void checkScriptCoverage() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> sample : SCRIPT_SAMPLES.entrySet()) {
            if (FONT_FOR_CODE_POINT.computeIfAbsent(sample.getValue().codePointAt(0), FallbackTextRenderer::findFallback) == NONE) {
                missing.add(sample.getKey());
            }
        }
        if (missing.isEmpty()) {
            LOG.info("Fallback fonts cover {} text", String.join(", ", SCRIPT_SAMPLES.keySet()));
        } else {
            LOG.warn("No font can display {} text, so it will be rendered as empty boxes in widget images. "
                    + "Install fonts such as fonts-noto-cjk / fonts-noto-core, or set widget.fonts.dir "
                    + "(see \"Fonts for Non-Latin Text\" in DEVELOPMENT.md).", String.join(", ", missing));
        }
    }

    /**
     * Draws {@code text} at the given baseline position using the graphics' current font and paint,
     * switching to a fallback font for characters the current font cannot display.
     */
    public static void drawString(Graphics2D g, String text, float x, float y) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Font primary = g.getFont();
        if (primary.canDisplayUpTo(text) == -1) {
            g.drawString(text, x, y);
            return;
        }
        g.drawString(attributed(text, primary).getIterator(), x, y);
    }

    /**
     * Returns the advance width of {@code text} as it would be drawn by
     * {@link #drawString(Graphics2D, String, float, float)}.
     */
    public static float stringWidth(Graphics2D g, String text) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        Font primary = g.getFont();
        if (primary.canDisplayUpTo(text) == -1) {
            return g.getFontMetrics(primary).stringWidth(text);
        }
        FontRenderContext frc = g.getFontRenderContext();
        return new TextLayout(attributed(text, primary).getIterator(), frc).getAdvance();
    }

    /**
     * Returns {@code text} shortened with a trailing "..." so that it fits into {@code maxWidth}
     * when drawn with the graphics' current font (including fallback fonts). Blank text becomes "-".
     * Whole code points are removed, so surrogate pairs are never split.
     */
    public static String fitToWidth(Graphics2D g, String text, int maxWidth) {
        String fitted = text == null || text.isBlank() ? "-" : text;
        if (stringWidth(g, fitted) <= maxWidth) {
            return fitted;
        }
        while (fitted.codePointCount(0, fitted.length()) > 1 && stringWidth(g, fitted + "...") > maxWidth) {
            fitted = fitted.substring(0, fitted.offsetByCodePoints(fitted.length(), -1));
        }
        return fitted + "...";
    }

    private static AttributedString attributed(String text, Font primary) {
        AttributedString result = new AttributedString(text);
        int start = 0;
        Font runFont = null;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            Font font = fontFor(codePoint, primary);
            if (runFont != null && !font.equals(runFont)) {
                result.addAttribute(TextAttribute.FONT, runFont, start, i);
                start = i;
            }
            runFont = font;
            i += Character.charCount(codePoint);
        }
        if (runFont != null) {
            result.addAttribute(TextAttribute.FONT, runFont, start, text.length());
        }
        return result;
    }

    private static Font fontFor(int codePoint, Font primary) {
        if (primary.canDisplay(codePoint) || Character.isWhitespace(codePoint)) {
            return primary;
        }
        Font base = FONT_FOR_CODE_POINT.computeIfAbsent(codePoint, FallbackTextRenderer::findFallback);
        if (base == NONE) {
            return primary; // nothing can display it; let the primary font draw its "missing" glyph
        }
        if (primary.isBold()) {
            // Use the family's real bold face when there is one (e.g. "Noto Sans CJK JP Bold");
            // deriving BOLD from a regular face does not embolden it on every platform.
            Font bold = BOLD_FACE_FOR_FAMILY.computeIfAbsent(familyOf(base), FallbackTextRenderer::findBoldFace);
            if (bold != NONE && bold.canDisplay(codePoint)) {
                base = bold;
            }
        }
        return base.deriveFont(primary.getStyle(), primary.getSize2D());
    }

    /**
     * Family name in English with a trailing Japanese-style weight ("W3", "W6", ...) removed, so the
     * weights of families such as Hiragino ("Hiragino Sans W3" / "Hiragino Sans W6") match.
     */
    private static String familyOf(Font font) {
        return font.getFamily(Locale.ENGLISH).replaceFirst("[ -]W\\d$", "");
    }

    private static Font findBoldFace(String family) {
        for (Font font : fallbackFonts()) {
            String name = font.getFontName(Locale.ENGLISH).toLowerCase(Locale.ROOT);
            // "Bold" faces, or the W6/W7 weights used by Japanese font families (e.g. Hiragino)
            boolean bold = (name.contains("bold") && !name.contains("semibold") && !name.contains("extrabold"))
                    || name.matches(".*[ -]w[67]$");
            if (familyOf(font).equals(family) && bold && !name.contains("italic")) {
                return font;
            }
        }
        return NONE;
    }

    private static Font findFallback(int codePoint) {
        for (Font font : fallbackFonts()) {
            if (font.canDisplay(codePoint)) {
                return font;
            }
        }
        return NONE;
    }

    private static List<Font> fallbackFonts() {
        List<Font> fonts = fallbackFonts;
        if (fonts == null) {
            synchronized (FallbackTextRenderer.class) {
                fonts = fallbackFonts;
                if (fonts == null) {
                    fonts = loadFallbackFonts();
                    fallbackFonts = fonts;
                }
            }
        }
        return fonts;
    }

    private static List<Font> loadFallbackFonts() {
        List<Font> fonts = new ArrayList<>();
        // Within each group, regular faces come first so a bold/italic/light face is never picked
        // as the base face (bold is chosen separately, see findBoldFace).
        fonts.addAll(sorted(loadBundledFonts(), false));
        fonts.addAll(sorted(loadDirectoryFonts(), false));
        int configured = fonts.size();

        // Installed fonts. Prefer proportional Noto Sans faces (broad script coverage, consistent
        // look), and push monospaced, symbol and emoji faces back so they are only used last.
        List<Font> installed = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts());
        fonts.addAll(sorted(installed, true));

        LOG.info("Text fallback fonts: {} bundled/configured, {} installed", configured, installed.size());
        return List.copyOf(fonts);
    }

    private static List<Font> sorted(List<Font> fonts, boolean preferNotoSans) {
        Comparator<Font> order = Comparator.comparingInt((Font f) -> preferNotoSans ? notoRank(f) : 0)
                .thenComparing(f -> isSpecialPurpose(f))
                .thenComparing(f -> !isRegularFace(f));
        List<Font> result = new ArrayList<>(fonts);
        result.sort(order); // stable: keeps file/listing order otherwise
        return result;
    }

    private static int notoRank(Font font) {
        String family = font.getFamily();
        if (family.startsWith("Noto Sans")) {
            return 0;
        }
        return family.startsWith("Noto") ? 1 : 2;
    }

    private static boolean isSpecialPurpose(Font font) {
        String name = (font.getFamily() + " " + font.getFontName()).toLowerCase();
        return name.contains("mono") || name.contains("nerd") || name.contains("emoji")
                || name.contains("symbol") || name.contains("dingbat") || name.contains("math");
    }

    private static boolean isRegularFace(Font font) {
        String name = font.getFontName().toLowerCase();
        return !(name.contains("bold") || name.contains("italic") || name.contains("oblique")
                || name.contains("light") || name.contains("thin") || name.contains("black")
                || name.contains("medium") || name.contains("semibold") || name.contains("heavy"));
    }

    private static List<Font> loadBundledFonts() {
        List<Font> fonts = new ArrayList<>();
        ClassLoader loader = FallbackTextRenderer.class.getClassLoader();
        try (InputStream list = loader.getResourceAsStream(BUNDLED_FONT_LIST)) {
            if (list == null) {
                return fonts;
            }
            for (String line : new String(list.readAllBytes()).split("\\R")) {
                String name = line.strip();
                if (name.isEmpty() || name.startsWith("#")) {
                    continue;
                }
                try (InputStream in = loader.getResourceAsStream("fonts/" + name)) {
                    if (in == null) {
                        LOG.warn("Bundled fallback font not found: fonts/{}", name);
                        continue;
                    }
                    fonts.addAll(Arrays.asList(Font.createFonts(in)));
                } catch (FontFormatException | IOException e) {
                    LOG.warn("Could not load bundled fallback font fonts/{}", name, e);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not read {}", BUNDLED_FONT_LIST, e);
        }
        return fonts;
    }

    private static List<Font> loadDirectoryFonts() {
        List<Font> fonts = new ArrayList<>();
        String dir = System.getProperty(FONTS_DIR_PROPERTY, System.getenv(FONTS_DIR_ENV));
        if (dir == null || dir.isBlank()) {
            return fonts;
        }
        Path path = Path.of(dir);
        if (!Files.isDirectory(path)) {
            LOG.warn("Fallback font directory does not exist: {}", path);
            return fonts;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path, "*.{ttf,otf,ttc,otc,TTF,OTF,TTC,OTC}")) {
            List<Path> sorted = new ArrayList<>();
            files.forEach(sorted::add);
            sorted.sort(Comparator.naturalOrder());
            for (Path file : sorted) {
                try {
                    fonts.addAll(Arrays.asList(Font.createFonts(file.toFile())));
                } catch (FontFormatException | IOException e) {
                    LOG.warn("Could not load fallback font {}", file, e);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not list fallback font directory {}", path, e);
        }
        return fonts;
    }
}
