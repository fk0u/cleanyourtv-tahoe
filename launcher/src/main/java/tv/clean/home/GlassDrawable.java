package tv.clean.home;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Liquid-glass material without real-time blur (too costly on a 1 GB stick):
 *  tinted body, a soft sheen on the upper half, and a rim that is bright on top and fades down. */
final class GlassDrawable extends Drawable {
    final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
            rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    float radius, stroke;
    int fill;

    GlassDrawable(float radius, int fill, float density) {
        this.radius = radius; this.fill = fill; this.stroke = Math.max(1, density);
        body.setColor(fill);
        rim.setStyle(Paint.Style.STROKE); rim.setStrokeWidth(stroke);
    }

    GlassDrawable fill(int c) { fill = c; body.setColor(c); invalidateSelf(); return this; }

    @Override protected void onBoundsChange(Rect b) {
        r.set(b); r.inset(stroke / 2, stroke / 2);
        if (r.height() <= 0) return;
        sheen.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{0x8CFFFFFF, 0x26FFFFFF, 0x14FFFFFF}, new float[]{0, 0.35f, 1}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        c.drawRoundRect(r, radius, radius, body);
        c.drawRoundRect(r, radius, radius, sheen);
        c.drawRoundRect(r, radius, radius, rim);
    }

    @Override public void getOutline(Outline o) { o.setRoundRect(getBounds(), radius); }
    @Override public void setAlpha(int a) { body.setAlpha(a); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
