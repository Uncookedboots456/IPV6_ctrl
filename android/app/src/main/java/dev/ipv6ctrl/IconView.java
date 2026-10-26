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
    private float maxDp = 24;
    // Large glyphs keep a ~3dp line instead of scaling the 24dp 2-unit stroke linearly.
    IconView maxSize(float dp) { maxDp = dp; paint.setStrokeWidth(Math.min(2f, 72f / dp)); return this; }
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
        float size = Math.min(side, maxDp * getResources().getDisplayMetrics().density);
        canvas.translate((getWidth()-size)/2, (getHeight()-size)/2);
        canvas.scale(size / 24f, size / 24f);
        switch (name) {
            case "back" -> {
                Path path=new Path();path.moveTo(12,5);path.lineTo(5,12);path.lineTo(12,19);canvas.drawPath(path,paint);canvas.drawLine(5,12,20,12,paint);
            }
            case "shield", "shield-check", "shield-alert" -> {
                Path path=new Path();path.moveTo(12,3);path.lineTo(21,7);path.lineTo(21,12);path.cubicTo(21,17,17,20,12,22);
                path.cubicTo(7,20,3,17,3,12);path.lineTo(3,7);path.close();canvas.drawPath(path,paint);
                if (name.equals("shield-check")) {
                    Path check=new Path();check.moveTo(8.5f,12.5f);check.lineTo(11,15);check.lineTo(15.5f,10);canvas.drawPath(check,paint);
                } else if (name.equals("shield-alert")) {
                    canvas.drawLine(12,8.5f,12,12.5f,paint);canvas.drawPoint(12,16,paint);
                }
            }
            case "globe" -> {
                canvas.drawCircle(12,12,10,paint);canvas.drawOval(7.5f,2,16.5f,22,paint);canvas.drawLine(2,12,22,12,paint);
            }
            case "refresh" -> {
                canvas.drawArc(3,3,21,21,180,135,false,paint);
                Path a=new Path();a.moveTo(18.36f,5.64f);a.lineTo(21,8);a.moveTo(21,3);a.lineTo(21,8);a.lineTo(16,8);canvas.drawPath(a,paint);
                canvas.drawArc(3,3,21,21,0,135,false,paint);
                Path b=new Path();b.moveTo(5.64f,18.36f);b.lineTo(3,16);b.moveTo(8,16);b.lineTo(3,16);b.lineTo(3,21);canvas.drawPath(b,paint);
            }
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
