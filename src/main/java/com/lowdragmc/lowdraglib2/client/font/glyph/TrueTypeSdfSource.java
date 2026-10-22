package com.lowdragmc.lowdraglib2.client.font.glyph;

import com.lowdragmc.lowdraglib2.LDLib2;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Rasterizes TrueType/OpenType outlines and turns them into signed distance fields.
 * <p>
 * Rasterization uses STB TrueType ({@link STBTruetype}), which is bundled natively with Minecraft 1.20.1 (LWJGL 3.3.1).
 * <p>
 * Metrics mirror vanilla's {@link com.mojang.blaze3d.font.TrueTypeGlyphProvider}: the font is scaled so one em
 * maps to {@code emPixels} device pixels and every metric is divided by the oversample factor to land back in
 * the 9 pixel design space, so advances stay identical to what the vanilla renderer produced.
 */
@OnlyIn(Dist.CLIENT)
public class TrueTypeSdfSource implements GlyphSource {
    /**
     * Padding around each glyph, also the distance in pixels the field spreads over.
     */
    private static final int PADDING = 6;
    /**
     * How close {@code size * oversample} has to be to a whole pixel size for the exact size raster path to be
     * able to map one texel to one device pixel.
     */
    private static final float EXACT_TOLERANCE = 0.01f;

    @Nullable
    private ByteBuffer fontData;
    @Nullable
    private STBTTFontinfo fontInfo;
    private final float size;
    private final int emPixels;
    private final float oversample;
    private final float shiftX;
    private final float shiftY;
    private final IntSet skip = new IntArraySet();

    private TrueTypeSdfSource(ByteBuffer fontData, STBTTFontinfo fontInfo, float size, int emPixels,
                              float shiftX, float shiftY, String skip) {
        this.fontData = fontData;
        this.fontInfo = fontInfo;
        this.size = size;
        this.emPixels = emPixels;
        this.oversample = emPixels / size;
        this.shiftX = shiftX;
        this.shiftY = shiftY;
        skip.codePoints().forEach(this.skip::add);
    }

    /**
     * @param emPixels pixel height one em is rasterized at, higher keeps corners sharper at large font sizes
     */
    @Nullable
    public static TrueTypeSdfSource load(ResourceManager resourceManager, TrueTypeGlyphProviderDefinition definition,
                                         int emPixels) {
        var file = definition.location().withPrefix("font/");
        ByteBuffer data = null;
        STBTTFontinfo fontInfo = null;
        try (InputStream stream = resourceManager.open(file)) {
            var bytes = stream.readAllBytes();
            data = MemoryUtil.memAlloc(bytes.length);
            data.put(bytes).flip();
            fontInfo = STBTTFontinfo.create();
            if (!STBTruetype.stbtt_InitFont(fontInfo, data)) {
                fontInfo.free();
                MemoryUtil.memFree(data);
                LDLib2.LOGGER.error("Failed to initialize font {}", file);
                return null;
            }
            return new TrueTypeSdfSource(data, fontInfo, definition.size(), emPixels,
                    definition.shift().x(), definition.shift().y(), definition.skip());
        } catch (IOException | RuntimeException e) {
            if (fontInfo != null) {
                fontInfo.free();
            }
            if (data != null) {
                MemoryUtil.memFree(data);
            }
            LDLib2.LOGGER.error("Failed to load font {}", file, e);
            return null;
        }
    }

    @Nullable
    @Override
    public synchronized GlyphMetrics metrics(int codepoint) {
        var index = glyphIndex(codepoint);
        if (index == 0 || fontInfo == null) {
            return null;
        }
        float scale = STBTruetype.stbtt_ScaleForPixelHeight(fontInfo, emPixels);
        try (var stack = MemoryStack.stackPush()) {
            var pAdvance = stack.mallocInt(1);
            var pLsb = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(fontInfo, index, pAdvance, pLsb);
            float advance = pAdvance.get(0) * scale;
            return GlyphMetrics.of(advance / oversample);
        }
    }

    @Nullable
    @Override
    public synchronized RawGlyph glyph(int codepoint) {
        var index = glyphIndex(codepoint);
        if (index == 0) {
            return null;
        }
        var rendered = render(index, emPixels);
        if (rendered == null) {
            return null;
        }
        if (rendered.coverage() == null) {
            return RawGlyph.space(rendered.advance() / oversample);
        }

        var sdf = DistanceTransform.fromCoverage(rendered.coverage(), rendered.width(), rendered.height(),
                PADDING, PADDING);
        var designPadding = PADDING / oversample;
        var left = rendered.bearingLeft() / oversample + shiftX - designPadding;
        var top = 7f - rendered.bearingTop() / oversample + shiftY - designPadding;
        var width = rendered.width() + PADDING * 2;
        var height = rendered.height() + PADDING * 2;
        return RawGlyph.of(sdf, width, height, left, top,
                left + width / oversample, top + height / oversample, rendered.advance() / oversample);
    }

    @Nullable
    @Override
    public synchronized RawGlyph rasterGlyph(int codepoint, float oversample, boolean exactOnly) {
        var index = glyphIndex(codepoint);
        if (index == 0) {
            return null;
        }
        var exactPixelSize = size * oversample;
        var pixelSize = Math.round(exactPixelSize);
        if (pixelSize <= 0) {
            return null;
        }
        if (exactOnly && Math.abs(exactPixelSize - pixelSize) / pixelSize > EXACT_TOLERANCE) {
            return null;
        }
        var metrics = metrics(codepoint);
        if (metrics == null) {
            return null;
        }
        var rendered = render(index, pixelSize);
        if (rendered == null) {
            return null;
        }
        if (rendered.coverage() == null) {
            return RawGlyph.space(metrics.advance());
        }
        var left = rendered.bearingLeft() / oversample + shiftX;
        var top = 7f - rendered.bearingTop() / oversample + shiftY;
        return RawGlyph.of(rendered.coverage(), rendered.width(), rendered.height(), left, top,
                left + rendered.width() / oversample, top + rendered.height() / oversample, metrics.advance());
    }

    /**
     * @param coverage 8 bit grayscale rows, or null when the glyph has no bitmap at all (a space)
     */
    private record Rendered(@Nullable byte[] coverage, int width, int height,
                            int bearingLeft, int bearingTop, float advance) {
    }

    @Nullable
    private Rendered render(int glyphIndex, int pixelSize) {
        var info = this.fontInfo;
        if (info == null) {
            return null;
        }
        float scale = STBTruetype.stbtt_ScaleForPixelHeight(info, pixelSize);
        float advance;
        int minX, minY, width, height;
        try (var stack = MemoryStack.stackPush()) {
            var pAdvance = stack.mallocInt(1);
            var pLsb = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphHMetrics(info, glyphIndex, pAdvance, pLsb);
            advance = pAdvance.get(0) * scale;

            var x0 = stack.mallocInt(1);
            var y0 = stack.mallocInt(1);
            var x1 = stack.mallocInt(1);
            var y1 = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphBitmapBox(info, glyphIndex, scale, scale, x0, y0, x1, y1);
            minX = x0.get(0);
            minY = y0.get(0);
            width = x1.get(0) - minX;
            height = y1.get(0) - minY;
        }

        if (width <= 0 || height <= 0) {
            return new Rendered(null, 0, 0, 0, 0, advance);
        }

        var bitmap = MemoryUtil.memAlloc(width * height);
        try {
            STBTruetype.stbtt_MakeGlyphBitmap(info, bitmap, width, height, width, scale, scale, glyphIndex);
            var coverage = new byte[width * height];
            bitmap.get(coverage);
            return new Rendered(coverage, width, height, minX, -minY, advance);
        } finally {
            MemoryUtil.memFree(bitmap);
        }
    }

    private int glyphIndex(int codepoint) {
        var info = this.fontInfo;
        if (info == null || skip.contains(codepoint)) {
            return 0;
        }
        return STBTruetype.stbtt_FindGlyphIndex(info, codepoint);
    }

    @Override
    public synchronized void close() {
        if (fontInfo != null) {
            fontInfo.free();
            fontInfo = null;
        }
        if (fontData != null) {
            MemoryUtil.memFree(fontData);
            fontData = null;
        }
    }
}
