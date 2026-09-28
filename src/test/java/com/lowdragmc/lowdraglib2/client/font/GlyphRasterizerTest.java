package com.lowdragmc.lowdraglib2.client.font;

import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the choice of rasterizer for {@link com.lowdragmc.lowdraglib2.client.font.glyph.TrueTypeSdfSource}.
 * <p>
 * stb_truetype's rasterizer writes past the end of its own output buffer at small em sizes, which corrupts the
 * native heap and kills the JVM a few allocations later. That is not something a library can ship, so glyphs
 * are rendered with FreeType instead. This test walks the bundled font at every em size the font engine can
 * ask for; if FreeType ever regressed the same way, the test JVM would die here instead of in someone's game.
 */
public class GlyphRasterizerTest {
    private static final Path FONT = Path.of("src/main/resources/assets/ldlib2/font/jetbrains_mono_bold.ttf");
    /**
     * The distance field reference size plus every line height the small size raster buckets use.
     */
    private static final int[] EM_SIZES = {6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 48};

    @Test
    void freeTypeRasterizesEveryGlyphAtEverySizeUsedByTheFontEngine() throws Exception {
        var bytes = Files.readAllBytes(FONT);
        var data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();

        long library;
        try (var stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            assertEquals(0, FreeType.FT_Init_FreeType(pointer), "FT_Init_FreeType");
            library = pointer.get();
        }

        FT_Face face;
        try (var stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            assertEquals(0, FreeType.FT_New_Memory_Face(library, data, 0L, pointer), "FT_New_Memory_Face");
            face = FT_Face.create(pointer.get());
        }
        assertEquals(0, FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE), "FT_Select_Charmap");

        var rendered = 0;
        for (var em : EM_SIZES) {
            assertEquals(0, FreeType.FT_Set_Pixel_Sizes(face, em, em), "FT_Set_Pixel_Sizes " + em);
            for (int codepoint = 0; codepoint < 0x10000; codepoint++) {
                var index = FreeType.FT_Get_Char_Index(face, codepoint);
                if (index == 0) continue;
                if (FreeType.FT_Load_Glyph(face, index, FreeType.FT_LOAD_RENDER) != 0) continue;
                var slot = face.glyph();
                if (slot == null) continue;
                var bitmap = slot.bitmap();
                var width = bitmap.width();
                var height = bitmap.rows();
                if (width <= 0 || height <= 0) continue;
                // read the pixels out the same way the font engine does, so a bad pitch would show up here
                var stride = Math.abs(bitmap.pitch());
                var buffer = bitmap.buffer(stride * height);
                if (buffer == null) continue;
                var coverage = new byte[width * height];
                for (int row = 0; row < height; row++) {
                    buffer.position(row * stride);
                    buffer.get(coverage, row * width, width);
                }
                rendered++;
            }
        }

        FreeType.FT_Done_Face(face);
        FreeType.FT_Done_Library(library);
        MemoryUtil.memFree(data);
        assertTrue(rendered > 1000, "expected the font to render a few thousand glyph/size pairs, got " + rendered);
    }

    /**
     * Explicitly exercises the small em sizes where the rasterizer is most
     * likely to expose native-memory bugs.
     *
     * <p>The important part of this test is that every size completes without
     * corrupting the native heap or causing the JVM to crash.</p>
     */
    @Test
    void freeTypeHandlesSmallRasterSizes() throws Exception {
        assertTrue(Files.exists(FONT), "Font file does not exist: " + FONT);

        var bytes = Files.readAllBytes(FONT);
        var data = MemoryUtil.memAlloc(bytes.length);

        try {
            data.put(bytes).flip();

            long library;

            try (var stack = MemoryStack.stackPush()) {
                var pointer = stack.mallocPointer(1);

                assertEquals(
                        0,
                        FreeType.FT_Init_FreeType(pointer),
                        "FT_Init_FreeType"
                );

                library = pointer.get(0);
            }

            try {
                FT_Face face;

                try (var stack = MemoryStack.stackPush()) {
                    var pointer = stack.mallocPointer(1);

                    assertEquals(
                            0,
                            FreeType.FT_New_Memory_Face(
                                    library,
                                    data,
                                    0L,
                                    pointer
                            ),
                            "FT_New_Memory_Face"
                    );

                    face = FT_Face.create(pointer.get(0));
                }

                try {
                    assertEquals(
                            0,
                            FreeType.FT_Select_Charmap(
                                    face,
                                    FreeType.FT_ENCODING_UNICODE
                            ),
                            "FT_Select_Charmap"
                    );

                    for (int em = 6; em <= 18; em++) {
                        assertEquals(
                                0,
                                FreeType.FT_Set_Pixel_Sizes(face, em, em),
                                "FT_Set_Pixel_Sizes " + em
                        );

                        int rendered = 0;

                        for (int codepoint = 0;
                             codepoint < 0x10000;
                             codepoint++) {

                            int glyphIndex =
                                    FreeType.FT_Get_Char_Index(face, codepoint);

                            if (glyphIndex == 0) {
                                continue;
                            }

                            if (FreeType.FT_Load_Glyph(
                                    face,
                                    glyphIndex,
                                    FreeType.FT_LOAD_RENDER
                            ) != 0) {
                                continue;
                            }

                            var slot = face.glyph();
                            if (slot == null) {
                                continue;
                            }

                            var bitmap = slot.bitmap();

                            if (bitmap.width() <= 0 || bitmap.rows() <= 0) {
                                continue;
                            }

                            int stride = Math.abs(bitmap.pitch());
                            var buffer =
                                    bitmap.buffer(stride * bitmap.rows());

                            if (buffer == null) {
                                continue;
                            }

                            /*
                             * Force an actual read of the native bitmap.
                             * A native-buffer overrun will therefore be much
                             * more likely to surface during this test.
                             */
                            for (int row = 0; row < bitmap.rows(); row++) {
                                buffer.position(row * stride);

                                for (int x = 0; x < bitmap.width(); x++) {
                                    buffer.get();
                                }
                            }

                            rendered++;
                        }

                        assertTrue(
                                rendered > 0,
                                "FreeType rendered no glyphs at " + em + "px"
                        );
                    }
                } finally {
                    FreeType.FT_Done_Face(face);
                }
            } finally {
                FreeType.FT_Done_Library(library);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

}
