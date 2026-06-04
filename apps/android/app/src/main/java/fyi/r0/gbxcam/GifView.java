package fyi.r0.gbxcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.os.SystemClock;
import android.view.View;

final class GifView extends View {
    private final Movie movie;
    private long startMs;

    GifView(Context context, int rawResId) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        movie = Movie.decodeStream(context.getResources().openRawResource(rawResId));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (movie == null) return;
        long now = SystemClock.uptimeMillis();
        if (startMs == 0) startMs = now;
        int duration = movie.duration();
        if (duration > 0) movie.setTime((int) ((now - startMs) % duration));
        float scale = Math.min(
                getWidth() > 0 ? (float) getWidth() / movie.width() : 1f,
                getHeight() > 0 ? (float) getHeight() / movie.height() : 1f);
        float dx = (getWidth() - movie.width() * scale) / 2f;
        float dy = (getHeight() - movie.height() * scale) / 2f;
        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        movie.draw(canvas, 0, 0);
        canvas.restore();
        invalidate();
    }
}
