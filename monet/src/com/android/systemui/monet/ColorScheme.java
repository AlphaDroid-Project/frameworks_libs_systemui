/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.monet;


import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.app.WallpaperColors;
import android.content.theming.ThemeStyle;
import android.graphics.Color;

import com.android.internal.graphics.ColorUtils;

import com.google.ux.material.libmonet.dynamiccolor.DynamicScheme;
import com.google.ux.material.libmonet.hct.Hct;
import com.google.ux.material.libmonet.scheme.SchemeContent;
import com.google.ux.material.libmonet.scheme.SchemeExpressive;
import com.google.ux.material.libmonet.scheme.SchemeFruitSalad;
import com.google.ux.material.libmonet.scheme.SchemeMonochrome;
import com.google.ux.material.libmonet.scheme.SchemeNeutral;
import com.google.ux.material.libmonet.scheme.SchemeRainbow;
import com.google.ux.material.libmonet.scheme.SchemeTonalSpot;
import com.google.ux.material.libmonet.scheme.SchemeVibrant;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds libmonet {@link DynamicScheme} instances for SystemUI theme overlays, including wallpaper
 * seed selection and optional {@link MonetParams} tuning (chroma, luminance contrast, neutral tint).
 *
 * @deprecated Prefer {@code com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors}
 * for direct palette access; this class remains the SystemUI integration point for seeds and
 * overlay-specific adjustments.
 */
@Deprecated
public class ColorScheme {
    public static final int GOOGLE_BLUE = 0xFF1b6ef3;
    private static final float ACCENT1_CHROMA = 48.0f;
    private static final int MIN_CHROMA = 5;
    private static final double MIN_FACTOR = 0.05;
    private static final double MAX_FACTOR = 2.0;
    private static final double NEUTRAL_CHROMA_CAP_STRONG = 12.0;
    private static final double NEUTRAL_VARIANT_CHROMA_CAP_STRONG = 16.0;
    private static final double NEUTRAL_CHROMA_CAP_NORMAL = 16.0;
    private static final double NEUTRAL_VARIANT_CHROMA_CAP_NORMAL = 22.0;

    @ColorInt
    private final int mSeed;
    private final boolean mIsDark;
    @ThemeStyle.Type
    private final int mStyle;
    private final DynamicScheme mMaterialScheme;
    private final TonalPalette mAccent1;
    private final TonalPalette mAccent2;
    private final TonalPalette mAccent3;
    private final TonalPalette mNeutral1;
    private final TonalPalette mNeutral2;
    private final TonalPalette mError;
    private final Hct mProposedSeedHct;


    public ColorScheme(@ColorInt int seed, boolean isDark, @ThemeStyle.Type int style,
            double contrastLevel) {
        this(seed, isDark, style, contrastLevel, MonetParams.DEFAULT);
    }

    public ColorScheme(@ColorInt int seed, boolean isDark, @ThemeStyle.Type int style,
            double contrastLevel, @Nullable MonetParams params) {
        this.mSeed = seed;
        this.mIsDark = isDark;
        this.mStyle = style;

        mProposedSeedHct = Hct.fromInt(
                seed == Color.TRANSPARENT
                        ? GOOGLE_BLUE
                        : (style != ThemeStyle.CONTENT
                                && Hct.fromInt(seed).getChroma() < 5
                                ? GOOGLE_BLUE
                                : seed));
        final MonetParams safeParams = sanitizeParams(params);
        final Hct seedHct = mProposedSeedHct;

        DynamicScheme baseScheme = switch (style) {
            case ThemeStyle.SPRITZ -> new SchemeNeutral(seedHct, isDark, contrastLevel);
            case ThemeStyle.TONAL_SPOT -> new SchemeTonalSpot(seedHct, isDark, contrastLevel);
            case ThemeStyle.VIBRANT -> new SchemeVibrant(seedHct, isDark, contrastLevel);
            case ThemeStyle.EXPRESSIVE -> new SchemeExpressive(seedHct, isDark, contrastLevel);
            case ThemeStyle.RAINBOW -> new SchemeRainbow(seedHct, isDark, contrastLevel);
            case ThemeStyle.FRUIT_SALAD -> new SchemeFruitSalad(seedHct, isDark, contrastLevel);
            case ThemeStyle.CONTENT -> new SchemeContent(seedHct, isDark, contrastLevel);
            case ThemeStyle.MONOCHROMATIC -> new SchemeMonochrome(seedHct, isDark, contrastLevel);
            // SystemUI Schemes
            case ThemeStyle.CLOCK -> new SchemeClock(seedHct, isDark, contrastLevel);
            case ThemeStyle.CLOCK_VIBRANT -> new SchemeClockVibrant(seedHct, isDark, contrastLevel);
            default -> throw new IllegalArgumentException("Unknown style: " + style);
        };
        baseScheme = withLuminanceContrast(baseScheme, isDark, contrastLevel,
                safeParams.luminanceShift);
        mMaterialScheme = buildAdjustedScheme(baseScheme, seedHct, style, safeParams);

        mAccent1 = new TonalPalette(mMaterialScheme.primaryPalette);
        mAccent2 = new TonalPalette(mMaterialScheme.secondaryPalette);
        mAccent3 = new TonalPalette(mMaterialScheme.tertiaryPalette);
        mNeutral1 = new TonalPalette(mMaterialScheme.neutralPalette);
        mNeutral2 = new TonalPalette(mMaterialScheme.neutralVariantPalette);
        mError = new TonalPalette(mMaterialScheme.errorPalette);
    }

    /**
     * Applies {@link MonetParams} on top of the libmonet base scheme: chroma scaling, optional
     * fidelity-based primary rebuild, neutral damping on aggressive styles, and optional
     * hue-aligned neutral tint when {@link MonetParams#tintBackground} is set.
     */
    private static DynamicScheme buildAdjustedScheme(
            DynamicScheme base, Hct seedHct, @ThemeStyle.Type int style, MonetParams params) {
        final boolean applyAccentExpansion = params.chromaFactor < 1.0;
        final com.google.ux.material.libmonet.palettes.TonalPalette primaryBase = params.fidelity
                ? com.google.ux.material.libmonet.palettes.TonalPalette
                        .fromHueAndChroma(seedHct.getHue(), seedHct.getChroma())
                : base.primaryPalette;
        final com.google.ux.material.libmonet.palettes.TonalPalette primary =
                adjustChroma(primaryBase, params.chromaFactor);
        final com.google.ux.material.libmonet.palettes.TonalPalette secondary = applyAccentExpansion
                ? adjustChroma(base.secondaryPalette, expansionFactor(params.chromaFactor))
                : base.secondaryPalette;
        final com.google.ux.material.libmonet.palettes.TonalPalette tertiary = applyAccentExpansion
                ? adjustChroma(base.tertiaryPalette, expansionFactor(params.chromaFactor))
                : base.tertiaryPalette;
        final com.google.ux.material.libmonet.palettes.TonalPalette error = applyAccentExpansion
                ? adjustChroma(base.errorPalette, expansionFactor(params.chromaFactor))
                : base.errorPalette;

        final com.google.ux.material.libmonet.palettes.TonalPalette neutral;
        final com.google.ux.material.libmonet.palettes.TonalPalette neutralVariant;
        // Monochrome is gray-by-design; Rainbow uses multi-hue scheme — custom neutral tint fights both.
        final boolean useCustomNeutralTint =
                params.tintBackground && allowsCustomNeutralTint(style);
        if (useCustomNeutralTint) {
            final double tintHue = params.bgColor != 0
                    ? Hct.fromInt(params.bgColor).getHue()
                    : seedHct.getHue();
            final boolean aggressive = isAggressiveStyle(style);
            final double neutralCap = aggressive ? NEUTRAL_CHROMA_CAP_STRONG : NEUTRAL_CHROMA_CAP_NORMAL;
            final double neutralVariantCap = aggressive
                    ? NEUTRAL_VARIANT_CHROMA_CAP_STRONG : NEUTRAL_VARIANT_CHROMA_CAP_NORMAL;
            neutral = com.google.ux.material.libmonet.palettes.TonalPalette.fromHueAndChroma(tintHue,
                    Math.min(base.neutralPalette.getChroma(), neutralCap));
            neutralVariant = com.google.ux.material.libmonet.palettes.TonalPalette.fromHueAndChroma(tintHue,
                    Math.min(base.neutralVariantPalette.getChroma(), neutralVariantCap));
        } else {
            neutral = dampNeutralForStyle(base.neutralPalette, style);
            neutralVariant = dampNeutralForStyle(base.neutralVariantPalette, style);
        }

        return new DynamicScheme(
                base.sourceColorHct,
                base.variant,
                base.isDark,
                base.contrastLevel,
                base.platform,
                base.specVersion,
                primary,
                secondary,
                tertiary,
                neutral,
                neutralVariant,
                Optional.of(error));
    }

    private static com.google.ux.material.libmonet.palettes.TonalPalette dampNeutralForStyle(
            com.google.ux.material.libmonet.palettes.TonalPalette base, @ThemeStyle.Type int style) {
        if (!isAggressiveStyle(style)) {
            return base;
        }
        return com.google.ux.material.libmonet.palettes.TonalPalette.fromHueAndChroma(base.getHue(),
                Math.min(base.getChroma(), NEUTRAL_CHROMA_CAP_STRONG));
    }

    private static boolean isAggressiveStyle(@ThemeStyle.Type int style) {
        return style == ThemeStyle.VIBRANT
                || style == ThemeStyle.EXPRESSIVE
                || style == ThemeStyle.RAINBOW
                || style == ThemeStyle.FRUIT_SALAD
                || style == ThemeStyle.CLOCK_VIBRANT;
    }

    /** Monochrome and Rainbow ignore user neutral tint so the style’s intended neutrals stay intact. */
    private static boolean allowsCustomNeutralTint(@ThemeStyle.Type int style) {
        return style != ThemeStyle.MONOCHROMATIC && style != ThemeStyle.RAINBOW;
    }

    private static com.google.ux.material.libmonet.palettes.TonalPalette adjustChroma(
            com.google.ux.material.libmonet.palettes.TonalPalette base, double factor) {
        final double adjusted = Math.max(0.0, Math.min(130.0, base.getChroma() * factor));
        return com.google.ux.material.libmonet.palettes.TonalPalette.fromHueAndChroma(
                base.getHue(), adjusted);
    }

    private static double expansionFactor(double factor) {
        return 1.0 + ((factor - 1.0) * 0.8);
    }

    private static MonetParams sanitizeParams(MonetParams params) {
        if (params == null) {
            return MonetParams.DEFAULT;
        }
        return new MonetParams(
                clampFactor(params.chromaFactor),
                clampShift(params.luminanceShift),
                params.tintBackground,
                params.bgColor,
                params.fidelity);
    }

    private static double clampFactor(double factor) {
        if (Double.isNaN(factor) || Double.isInfinite(factor)) {
            return 1.0;
        }
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
    }

    private static double clampShift(double shift) {
        if (Double.isNaN(shift) || Double.isInfinite(shift)) {
            return 0.0;
        }
        return Math.max(-35.0, Math.min(35.0, shift));
    }

    /**
     * Maps Monet "luminance" to {@link DynamicScheme#contrastLevel} for every theme style.
     * Shifting only the seed HCT tone (the old approach) is subtle for most schemes and useless for
     * Monochrome (libmonet ignores seed tone there). Contrast tracks through Material dynamic colors
     * so the slider visibly lightens or darkens surfaces for all styles.
     */
    private static DynamicScheme withLuminanceContrast(
            DynamicScheme base,
            boolean isDark,
            double contrastLevel,
            double luminanceShift) {
        if (luminanceShift == 0.0) {
            return base;
        }
        // sanitizeParams clampShift keeps luminanceShift in [-35, 35]; map into contrast [-1, 1].
        final double delta = (luminanceShift / 35.0) * 0.55;
        final double adjusted =
                Math.max(-1.0, Math.min(1.0, contrastLevel + delta));
        return DynamicScheme.from(base, isDark, adjusted);
    }

    public ColorScheme(@ColorInt int seed, boolean darkTheme) {
        this(seed, darkTheme, ThemeStyle.TONAL_SPOT);
    }

    public ColorScheme(@ColorInt int seed, boolean darkTheme, @ThemeStyle.Type int style) {
        this(seed, darkTheme, style, 0.0);
    }

    public ColorScheme(@Nullable WallpaperColors wallpaperColors, boolean darkTheme,
            @ThemeStyle.Type int style) {
        this(getSeedColor(wallpaperColors, style != ThemeStyle.CONTENT), darkTheme, style);
    }

    public ColorScheme(@Nullable WallpaperColors wallpaperColors, boolean darkTheme) {
        this(wallpaperColors, darkTheme, ThemeStyle.TONAL_SPOT);
    }

    public int getBackgroundColor() {
        return ColorUtils.setAlphaComponent(mIsDark
                ? mNeutral1.getS700()
                : mNeutral1.getS10(), 0xFF);
    }

    public int getAccentColor() {
        return ColorUtils.setAlphaComponent(mIsDark
                ? mAccent1.getS100()
                : mAccent1.getS500(), 0xFF);
    }

    public double getSeedTone() {
        return 1000d - mProposedSeedHct.getTone() * 10d;
    }

    public int getSeed() {
        return mSeed;
    }

    @ThemeStyle.Type
    public int getStyle() {
        return mStyle;
    }

    public DynamicScheme getMaterialScheme() {
        return mMaterialScheme;
    }

    public TonalPalette getAccent1() {
        return mAccent1;
    }

    public TonalPalette getAccent2() {
        return mAccent2;
    }

    public TonalPalette getAccent3() {
        return mAccent3;
    }

    public TonalPalette getNeutral1() {
        return mNeutral1;
    }

    public TonalPalette getNeutral2() {
        return mNeutral2;
    }

    public TonalPalette getError() {
        return mError;
    }

    @Override
    public String toString() {
        return "ColorScheme {\n"
                + "  seed color: " + stringForColor(mSeed) + "\n"
                + "  style: " + mStyle + "\n"
                + "  palettes: \n"
                + "  " + humanReadable("PRIMARY", mAccent1.allShades) + "\n"
                + "  " + humanReadable("SECONDARY", mAccent2.allShades) + "\n"
                + "  " + humanReadable("TERTIARY", mAccent3.allShades) + "\n"
                + "  " + humanReadable("NEUTRAL", mNeutral1.allShades) + "\n"
                + "  " + humanReadable("NEUTRAL VARIANT", mNeutral2.allShades) + "\n"
                + "}";
    }

    /**
     * Identifies a color to create a color scheme from.
     *
     * @param wallpaperColors Colors extracted from an image via quantization.
     * @param filter          If false, allow colors that have low chroma, creating grayscale
     *                        themes.
     * @return ARGB int representing the color
     */
    @ColorInt
    public static int getSeedColor(@Nullable WallpaperColors wallpaperColors, boolean filter) {
        return getSeedColors(wallpaperColors, filter).get(0);
    }

    /**
     * Identifies a color to create a color scheme from. Defaults {@code filter} to {@code true}.
     *
     * @param wallpaperColors Colors extracted from an image via quantization.
     * @return ARGB int representing the color
     */
    public static int getSeedColor(@Nullable WallpaperColors wallpaperColors) {
        return getSeedColor(wallpaperColors, true);
    }

    /**
     * Filters and ranks colors from WallpaperColors.
     *
     * @param wallpaperColors Colors extracted from an image via quantization, or {@code null} to
     *                        use the default seed ({@link #GOOGLE_BLUE}).
     * @param filter          If false, allow colors that have low chroma, creating grayscale
     *                        themes.
     * @return List of ARGB ints, ordered from highest scoring to lowest. Non-empty.
     */
    public static List<Integer> getSeedColors(@Nullable WallpaperColors wallpaperColors,
            boolean filter) {
        if (wallpaperColors == null) {
            return List.of(GOOGLE_BLUE);
        }
        double totalPopulation = wallpaperColors.getAllColors().values().stream().mapToInt(
                Integer::intValue).sum();
        boolean totalPopulationMeaningless = (totalPopulation == 0.0);

        if (totalPopulationMeaningless) {
            // WallpaperColors with a population of 0 indicate the colors didn't come from
            // quantization. Instead of scoring, trust the ordering of the provided primary
            // secondary/tertiary colors.
            //
            // In this case, the colors are usually from a Live Wallpaper.
            List<Integer> distinctColors = wallpaperColors.getMainColors().stream()
                    .map(Color::toArgb)
                    .distinct()
                    .filter(color -> !filter || Hct.fromInt(color).getChroma() >= MIN_CHROMA)
                    .collect(Collectors.toList());
            if (distinctColors.isEmpty()) {
                return List.of(GOOGLE_BLUE);
            }
            return distinctColors;
        }

        Map<Integer, Double> intToProportion = wallpaperColors.getAllColors().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().doubleValue() / totalPopulation));
        Map<Integer, Hct> intToHct = wallpaperColors.getAllColors().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Hct.fromInt(entry.getKey())));

        // Get an array with 360 slots. A slot contains the percentage of colors with that hue.
        List<Double> hueProportions = huePopulations(intToHct, intToProportion, filter);
        // Map each color to the percentage of the image with its hue.
        Map<Integer, Double> intToHueProportion = wallpaperColors.getAllColors().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    Hct hct = intToHct.get(entry.getKey());
                    int hue = (int) Math.round(hct.getHue());
                    double proportion = 0.0;
                    for (int i = hue - 15; i <= hue + 15; i++) {
                        proportion += hueProportions.get(wrapDegrees(i));
                    }
                    return proportion;
                }));
        // Remove any inappropriate seed colors. For example, low chroma colors look grayscale
        // raising their chroma will turn them to a much louder color that may not have been
        // in the image.
        Map<Integer, Hct> filteredIntToHct = filter
                ? intToHct
                .entrySet()
                .stream()
                .filter(entry -> {
                    Hct hct = entry.getValue();
                    double proportion = intToHueProportion.get(entry.getKey());
                    return hct.getChroma() >= MIN_CHROMA && proportion > 0.01;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                : intToHct;
        // Sort the colors by score, from high to low.
        List<Map.Entry<Integer, Double>> intToScore = filteredIntToHct.entrySet().stream()
                .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(),
                        score(entry.getValue(), intToHueProportion.get(entry.getKey()))))
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        // Go through the colors, from high score to low score.
        // If the color is distinct in hue from colors picked so far, pick the color.
        // Iteratively decrease the amount of hue distinctness required, thus ensuring we
        // maximize difference between colors.
        int minimumHueDistance = 15;
        List<Integer> seeds = new ArrayList<>();
        for (int i = 90; i >= minimumHueDistance; i--) {
            seeds.clear();
            for (Map.Entry<Integer, Double> entry : intToScore) {
                int currentColor = entry.getKey();
                int finalI = i;
                boolean existingSeedNearby = seeds.stream().anyMatch(seed -> {
                    double hueA = intToHct.get(currentColor).getHue();
                    double hueB = intToHct.get(seed).getHue();
                    return hueDiff(hueA, hueB) < finalI;
                });
                if (existingSeedNearby) {
                    continue;
                }
                seeds.add(currentColor);
                if (seeds.size() >= 4) {
                    break;
                }
            }
            if (!seeds.isEmpty()) {
                break;
            }
        }

        if (seeds.isEmpty()) {
            seeds.add(GOOGLE_BLUE);
        }

        return seeds;
    }

    /**
     * Filters and ranks colors from WallpaperColors. Defaults {@code filter} to {@code true}.
     *
     * @param wallpaperColors Colors extracted from an image via quantization.
     * @return List of ARGB ints, ordered from highest scoring to lowest. Non-empty.
     */
    public static List<Integer> getSeedColors(@Nullable WallpaperColors wallpaperColors) {
        return getSeedColors(wallpaperColors, true);
    }

    private static int wrapDegrees(int degrees) {
        if (degrees < 0) {
            return (degrees % 360) + 360;
        } else if (degrees >= 360) {
            return degrees % 360;
        } else {
            return degrees;
        }
    }

    private static double hueDiff(double a, double b) {
        double diff = Math.abs(a - b);
        if (diff > 180f) {
            // 0 and 360 are the same hue. If hue difference is greater than 180, subtract from 360
            // to account for the circularity.
            diff = 360f - diff;
        }
        return diff;
    }

    private static String stringForColor(int color) {
        int width = 4;
        Hct hct = Hct.fromInt(color);
        String h = "H" + String.format("%" + width + "s", Math.round(hct.getHue()));
        String c = "C" + String.format("%" + width + "s", Math.round(hct.getChroma()));
        String t = "T" + String.format("%" + width + "s", Math.round(hct.getTone()));
        String hex = Integer.toHexString(color & 0xffffff).toUpperCase();
        return h + c + t + " = #" + hex;
    }

    private static String humanReadable(String paletteName, List<Integer> colors) {
        return paletteName + "\n"
                + colors
                .stream()
                .map(ColorScheme::stringForColor)
                .collect(Collectors.joining("\n"));
    }

    private static double score(Hct hct, double proportion) {
        double proportionScore = 0.7 * 100.0 * proportion;
        double chromaScore = hct.getChroma() < ACCENT1_CHROMA
                ? 0.1 * (hct.getChroma() - ACCENT1_CHROMA)
                : 0.3 * (hct.getChroma() - ACCENT1_CHROMA);
        return chromaScore + proportionScore;
    }

    private static List<Double> huePopulations(Map<Integer, Hct> hctByColor,
            Map<Integer, Double> populationByColor, boolean filter) {
        List<Double> huePopulation = new ArrayList<>(Collections.nCopies(360, 0.0));

        for (Map.Entry<Integer, Double> entry : populationByColor.entrySet()) {
            double population = entry.getValue();
            Hct hct = hctByColor.get(entry.getKey());
            int hue = (int) Math.round(hct.getHue()) % 360;
            if (filter && hct.getChroma() <= MIN_CHROMA) {
                continue;
            }
            huePopulation.set(hue, huePopulation.get(hue) + population);
        }

        return huePopulation;
    }
}
