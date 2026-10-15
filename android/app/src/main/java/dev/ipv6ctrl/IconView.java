package dev.ipv6ctrl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Lucide-style 24-unit vectors; notices are included in assets/licenses.txt. */
final class IconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String name;
    IconView(Context context, String name, int color) {
        super(context);
        this.name = name;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        float side = Math.min(getWidth(), getHeight());
        float size = Math.min(side, 24 * getResources().getDisplayMetrics().density);
        canvas.translate((getWidth()-size)/2, (getHeight()-size)/2);
        canvas.scale(size / 24f, size / 24f);
        switch (name) {
            case "scope" -> { canvas.drawRoundRect(3,3,13,13,2,2,paint); canvas.drawRoundRect(11,11,21,21,2,2,paint); }
            case "pulse" -> {
                Path path = new Path(); path.moveTo(2,12); path.lineTo(7,12); path.lineTo(10,4);
                path.lineTo(14,20); path.lineTo(17,12); path.lineTo(22,12); canvas.drawPath(path,paint);
            }
            case "sun" -> {
                canvas.drawCircle(12,12,4,paint);
                for (int angle=0;angle<360;angle+=45) {
                    canvas.save();canvas.rotate(angle,12,12);canvas.drawLine(12,1,12,3,paint);canvas.restore();
                }
            }
            default -> { canvas.drawCircle(12,12,9,paint);canvas.drawLine(12,6,12,12,paint);canvas.drawLine(12,12,16,14,paint); }
        }
        canvas.restore();
    }
}
