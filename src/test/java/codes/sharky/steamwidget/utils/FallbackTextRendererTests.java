package codes.sharky.steamwidget.utils;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FallbackTextRendererTests {

    private static Graphics2D graphics() {
        Graphics2D g = new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB).createGraphics();
        g.setFont(new Font("ARIAL", Font.PLAIN, 40));
        return g;
    }

    private static int[] render(Font font, String text, boolean withFallback) {
        BufferedImage image = new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setFont(font);
        if (withFallback) {
            FallbackTextRenderer.drawString(g, text, 10, 70);
        } else {
            g.drawString(text, 10, 70);
        }
        g.dispose();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Test
    void latinTextIsMeasuredAndDrawnWithThePrimaryFont() {
        Graphics2D g = graphics();
        assertEquals(g.getFontMetrics().stringWidth("Steam Widget"), FallbackTextRenderer.stringWidth(g, "Steam Widget"));
        assertArrayEquals(render(g.getFont(), "Steam Widget", false), render(g.getFont(), "Steam Widget", true));
    }

    @Test
    void emptyTextHasNoWidth() {
        Graphics2D g = graphics();
        assertEquals(0f, FallbackTextRenderer.stringWidth(g, ""));
        assertEquals(0f, FallbackTextRenderer.stringWidth(g, null));
        assertDoesNotThrow(() -> FallbackTextRenderer.drawString(g, null, 0, 0));
    }

    @Test
    void textThatFitsIsNotShortened() {
        assertEquals("Slay the Spire", FallbackTextRenderer.fitToWidth(graphics(), "Slay the Spire", 1000));
    }

    @Test
    void blankTextIsReplacedWithADash() {
        assertEquals("-", FallbackTextRenderer.fitToWidth(graphics(), null, 1000));
        assertEquals("-", FallbackTextRenderer.fitToWidth(graphics(), "  ", 1000));
    }

    @Test
    void longTextIsShortenedWithAnEllipsisToFitTheWidth() {
        Graphics2D g = graphics();
        String fitted = FallbackTextRenderer.fitToWidth(g, "Counter-Strike: Condition Zero Deleted Scenes", 300);

        assertTrue(fitted.endsWith("..."));
        assertTrue(FallbackTextRenderer.stringWidth(g, fitted) <= 300);
    }

    @Test
    void shorteningNeverSplitsASurrogatePair() {
        // U+20BB7 is outside the BMP and stored as two chars (a surrogate pair)
        String text = "𠮷".repeat(30);
        String fitted = FallbackTextRenderer.fitToWidth(graphics(), text, 200);
        String withoutEllipsis = fitted.substring(0, fitted.length() - "...".length());

        assertTrue(fitted.endsWith("..."));
        assertEquals(0, withoutEllipsis.length() % 2);
        assertTrue(withoutEllipsis.codePoints().allMatch(cp -> cp == 0x20BB7));
    }

    @Test
    void nonLatinTextIsDrawnWithAFallbackFontWhenThePrimaryFontLacksIt() {
        Font primary = new Font("ARIAL", Font.PLAIN, 40);
        // Only meaningful where the primary font has no Japanese glyphs and another font does
        assumeFalse(primary.canDisplay('あ'), "primary font already displays Japanese");
        assumeTrue(Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts())
                .anyMatch(font -> font.canDisplay('あ')), "no installed font displays Japanese");

        assertFalse(Arrays.equals(render(primary, "あいう", false), render(primary, "あいう", true)));
    }

    @Test
    void scriptCoverageCheckDoesNotFail() {
        assertDoesNotThrow(FallbackTextRenderer::checkScriptCoverage);
    }
}
