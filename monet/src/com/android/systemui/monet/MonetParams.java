/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.monet;

public final class MonetParams {
    public final double chromaFactor;
    public final double luminanceShift;
    public final boolean tintBackground;
    public final int bgColor;
    public final boolean fidelity;

    public static final MonetParams DEFAULT = new MonetParams(1.0, 0.0, false, 0, false);

    public MonetParams(
            double chromaFactor,
            double luminanceShift,
            boolean tintBackground,
            int bgColor,
            boolean fidelity) {
        this.chromaFactor = chromaFactor;
        this.luminanceShift = luminanceShift;
        this.tintBackground = tintBackground;
        this.bgColor = bgColor;
        this.fidelity = fidelity;
    }

    public boolean isDefault() {
        return chromaFactor == 1.0
                && luminanceShift == 0.0
                && !tintBackground
                && !fidelity;
    }

    @Override
    public String toString() {
        return "MonetParams{"
                + "chromaFactor=" + chromaFactor
                + ", luminanceShift=" + luminanceShift
                + ", tintBackground=" + tintBackground
                + ", bgColor=#" + Integer.toHexString(bgColor)
                + ", fidelity=" + fidelity
                + '}';
    }
}
