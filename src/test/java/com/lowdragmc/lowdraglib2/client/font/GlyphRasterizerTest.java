package com.lowdragmc.lowdraglib2.client.font;

import org.junit.jupiter.api.Test;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the choice of rasterizer for {@link com.lowdragmc.lowdraglib2.client.font.glyph.TrueTypeSdfSource}.
 * <p>
 * This test walks the bundled font at every em size the font engine can ask for to ensure STB TrueType
 * rasterizes without memory corruption or crashes.
 */
public class GlyphRasterizerTest {
    private static final Path FONT = Path.of("src/main/resources/assets/ldlib2/font/jetbrains_mono_bold.ttf");
    /**
     * The distance field reference size plus every line height the small size raster buckets use.
     */
    private static final int[] EM_SIZES = {6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 48};

    @Test
    void stbTrueTypeRasterizesEveryGlyphAtEverySizeUsedByTheFontEngine() throws Exception {
        var bytes = Files.readAllBytes(FONT);
        var data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();

        var fontInfo = STBTTFontinfo.create();
        assertTrue(STBTruetype.stbtt_InitFont(fontInfo, data), "stbtt_InitFont");

        var rendered = 0;
        for (var em : EM_SIZES) {
            float scale = STBTruetype.stbtt_ScaleForPixelHeight(fontInfo, em);
            for (int codepoint = 0; codepoint < 0x10000; codepoint++) {
                var index = STBTruetype.stbtt_FindGlyphIndex(fontInfo, codepoint);
                if (index == 0) continue;
                int width, height;
                try (var stack = MemoryStack.stackPush()) {
                    var x0 = stack.mallocInt(1);
                    var y0 = stack.mallocInt(1);
                    var x1 = stack.mallocInt(1);
                    var y1 = stack.mallocInt(1);
                    STBTruetype.stbtt_GetGlyphBitmapBox(fontInfo, index, scale, scale, x0, y0, x1, y1);
                    width = x1.get(0) - x0.get(0);
                    height = y1.get(0) - y0.get(0);
                }
                if (width <= 0 || height <= 0) continue;

                var bitmap = MemoryUtil.memAlloc(width * height);
                try {
                    STBTruetype.stbtt_MakeGlyphBitmap(fontInfo, bitmap, width, height, width, scale, scale, index);
                    var coverage = new byte[width * height];
                    bitmap.get(coverage);
                    rendered++;
                } finally {
                    MemoryUtil.memFree(bitmap);
                }
            }
        }

        fontInfo.free();
        MemoryUtil.memFree(data);
        assertTrue(rendered > 1000, "expected the font to render a few thousand glyph/size pairs, got " + rendered);
    }
}
