package com.custom.tvlauncher;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * Liquid glass clock text inspired by Apple TV / iOS lock screen and glassOS-tv:
 * Translucent specular gradient fill fading from bright at top to subtle translucency at bottom,
 * with a highlighted top rim and soft drop shadow.
 */
public class GlassClockView extends TextView {

    private LinearGradient fill;
    private LinearGradient rim;
    private int shaderHeight = -1;
    private Shader shadow;
    private int shadowShaderAlpha = -1;
    private int shadowAlpha = 0x66;

    public GlassClockView(Context context) {
        super(context);
        init();
    }

    public GlassClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GlassClockView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setTextColor(0xFFFFFFFF);
        setIncludeFontPadding(false);
        setFontFeatureSettings("tnum");
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public void setShadowStrength(int percent) {
        shadowAlpha = Math.round(0xCC * Math.max(0, Math.min(100, percent)) / 100f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int h = getHeight();
        if (h <= 0) {
            super.onDraw(canvas);
            return;
        }

        if (h != shaderHeight) {
            shaderHeight = h;
            float top = getPaddingTop();
            float bottom = h - getPaddingBottom();
            fill = new LinearGradient(0, top, 0, bottom,
                    new int[]{0xF2FFFFFF, 0xB8FFFFFF, 0x85FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP);
            rim = new LinearGradient(0, top, 0, bottom,
                    new int[]{0xCCFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.28f, 1f},
                    Shader.TileMode.CLAMP);
        }

        TextPaint p = getPaint();
        p.setStyle(Paint.Style.FILL);

        // Pass 1: Soft deep drop shadow under the text
        if (shadowAlpha > 0) {
            if (shadow == null || shadowShaderAlpha != shadowAlpha) {
                shadowShaderAlpha = shadowAlpha;
                shadow = new LinearGradient(0, 0, 0, 1,
                        shadowAlpha << 24, shadowAlpha << 24,
                        Shader.TileMode.CLAMP);
            }
            p.setShader(shadow);
            p.setMaskFilter(new BlurMaskFilter(Math.max(1f, getTextSize() / 14f), BlurMaskFilter.Blur.NORMAL));
            canvas.save();
            canvas.translate(0, getTextSize() / 32f);
            super.onDraw(canvas);
            canvas.restore();
            p.setMaskFilter(null);
        }

        // Pass 2: Liquid glass body fill with halo
        p.setShader(fill);
        p.setShadowLayer(getTextSize() / 8f, 0, 0, 0x33FFFFFF);
        super.onDraw(canvas);

        // Pass 3: Specular top edge catch light
        p.clearShadowLayer();
        p.setShader(rim);
        float lift = Math.max(1f, getTextSize() / 100f);
        canvas.save();
        canvas.translate(0, -lift);
        super.onDraw(canvas);
        canvas.restore();
        p.setShader(null);
    }
}
