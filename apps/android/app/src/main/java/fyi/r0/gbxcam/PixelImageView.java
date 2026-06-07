package fyi.r0.gbxcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

final class PixelImageView extends ImageView {
    PixelImageView(Context context) {
        super(context);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable drawable = getDrawable();
        if (drawable != null) {
            drawable.setFilterBitmap(shouldFilter(drawable));
            drawable.setDither(false);
        }
        super.onDraw(canvas);
    }

    private boolean shouldFilter(Drawable drawable) {
        int sourceWidth = drawable.getIntrinsicWidth();
        int sourceHeight = drawable.getIntrinsicHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return true;
        }
        int targetWidth = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int targetHeight = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        return targetWidth < sourceWidth || targetHeight < sourceHeight;
    }
}
