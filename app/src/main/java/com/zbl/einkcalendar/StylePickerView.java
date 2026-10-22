package com.zbl.einkcalendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Full-screen gallery of poster layouts. Every thumbnail is the real renderer
 * scaled down, so what the user sees is exactly what the dashboard will show.
 *
 * Three pages: two pages of six portrait layouts and one page of the four
 * landscape layouts, whose thumbnails keep their wide aspect ratio.
 */
class StylePickerView extends View {

    interface Listener {
        void onStylePicked(int style);

        void onCancel();
    }

    private static final int PER_PAGE = 6;
    private static final int PAGES = 3;

    private static final int INK = 0xFF000000;
    private static final int GRAY = 0xFF666666;
    private static final int PAPER = 0xFFFFFFFF;
    private static final int HAIR = 0xFFBBBBBB;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PosterRenderer preview = new PosterRenderer();
    private final Listener listener;
    private final int selected;

    private final RectF[] cells = new RectF[PER_PAGE];
    private final RectF[] landCells = new RectF[PER_PAGE];
    private final RectF prevButton = new RectF();
    private final RectF nextButton = new RectF();
    private final RectF cancelButton = new RectF();

    private int page;

    private float cellW;
    private float cellH;
    private float margin;
    private float gridTop;
    private float labelSize;

    private float downX;
    private float downY;

    StylePickerView(Context context, int selected, Listener listener) {
        super(context);
        this.selected = selected;
        this.listener = listener;
        for (int i = 0; i < PER_PAGE; i++) {
            cells[i] = new RectF();
            landCells[i] = new RectF();
        }
        preview.fillToday();
        line.setStyle(Paint.Style.STROKE);
        // Open on the page that holds the current layout.
        page = selected >= PosterRenderer.FIRST_LANDSCAPE ? 2 : selected / PER_PAGE;
        page = Math.max(0, Math.min(PAGES - 1, page));
        setBackgroundColor(PAPER);
    }

    /** Battery level drawn inside every thumbnail. */
    void setBattery(int percent, boolean charging) {
        preview.batteryPercent = percent;
        preview.batteryCharging = charging;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        margin = w * 0.045f;
        gridTop = h * 0.145f;
        labelSize = Math.max(32f, w * 0.030f);

        float gap = w * 0.026f;
        float labelH = w * 0.050f;
        float rowGap = h * 0.026f;

        float btnH = h * 0.062f;
        float btnW = w * 0.24f;
        float btnY = h - margin - btnH;

        cellW = (w - 2f * margin - 2f * gap) / 3f;
        cellH = cellW * 1448f / 1072f;

        for (int i = 0; i < 6; i++) {
            int row = i / 3;
            int col = i % 3;
            float x = margin + col * (cellW + gap);
            float y = gridTop + row * (cellH + labelH + rowGap);
            cells[i].set(x, y, x + cellW, y + cellH);
        }

        // Landscape page: two columns of wide thumbnails, centred in the space
        // between the header rule and the buttons.
        float landW = (w - 2f * margin - gap) / 2f;
        float landH = landW * 1072f / 1448f;
        float contentH = 2f * landH + 2f * labelH + rowGap;
        float offset = Math.max(0f, (btnY - gridTop - contentH) / 2f);
        for (int i = 0; i < 4; i++) {
            int row = i / 2;
            int col = i % 2;
            float x = margin + col * (landW + gap);
            float y = gridTop + offset + row * (landH + labelH + rowGap);
            landCells[i].set(x, y, x + landW, y + landH);
        }

        cancelButton.set((w - btnW) / 2f, btnY, (w + btnW) / 2f, btnY + btnH);
        prevButton.set(margin, btnY, margin + btnW, btnY + btnH);
        nextButton.set(w - margin - btnW, btnY, w - margin, btnY + btnH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w * 0.040f);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("显示样式", margin, h * 0.065f, paint);

        paint.setColor(GRAY);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(Math.max(32f, w * 0.030f));
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("第 " + (page + 1) + " / " + PAGES + " 页", w - margin, h * 0.064f, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(labelSize);
        canvas.drawText(page == 2
                        ? "横版：整机转向，滑动方向随之改变"
                        : "点按缩略图切换，返回后立即生效",
                margin, h * 0.104f, paint);

        line.setStrokeWidth(w * 0.002f);
        line.setColor(HAIR);
        canvas.drawLine(margin, h * 0.118f, w - margin, h * 0.118f, line);

        if (page == 2) {
            for (int i = 0; i < 4; i++) {
                int style = PosterRenderer.FIRST_LANDSCAPE + i;
                if (style < PosterRenderer.STYLE_COUNT) {
                    drawThumb(canvas, landCells[i], style, style == selected);
                }
            }
        } else {
            for (int i = 0; i < PER_PAGE; i++) {
                int style = page * PER_PAGE + i;
                if (style < PosterRenderer.FIRST_LANDSCAPE) {
                    drawThumb(canvas, cells[i], style, style == selected);
                }
            }
        }

        drawButton(canvas, cancelButton, "取消");
        drawButton(canvas, prevButton, "‹ 上一页");
        drawButton(canvas, nextButton, "下一页 ›");
    }

    private void drawThumb(Canvas canvas, RectF box, int style, boolean current) {
        boolean land = PosterRenderer.isLandscape(style);
        int dw = land ? PosterRenderer.REF_LAND_W : PosterRenderer.REF_W;
        int dh = land ? PosterRenderer.REF_LAND_H : PosterRenderer.REF_H;

        canvas.save();
        canvas.clipRect(box);
        canvas.translate(box.left, box.top);
        canvas.scale(box.width() / dw, box.height() / dh);
        preview.draw(canvas, dw, dh, style);
        canvas.restore();

        line.setColor(INK);
        line.setStrokeWidth(current ? box.width() * 0.022f : box.width() * 0.006f);
        float inset = line.getStrokeWidth() / 2f;
        canvas.drawRect(box.left + inset, box.top + inset,
                box.right - inset, box.bottom - inset, line);

        paint.setTextSize(labelSize);
        paint.setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        paint.setColor(INK);
        paint.setTextAlign(Paint.Align.LEFT);
        float base = box.bottom + labelSize * 1.15f;
        String name = PosterRenderer.styleName(style);
        canvas.drawText(name, box.left, base, paint);

        if (current) {
            paint.setTypeface(Typeface.DEFAULT);
            paint.setColor(GRAY);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("使用中", box.right, base, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawButton(Canvas canvas, RectF box, String label) {
        line.setColor(INK);
        line.setStrokeWidth(cellW * 0.006f);
        canvas.drawRect(box, line);
        paint.setColor(INK);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(box.width() * 0.15f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, box.centerX(), box.centerY() + box.height() * 0.18f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.abs(event.getX() - downX) > 40f || Math.abs(event.getY() - downY) > 40f) {
                    return true;
                }
                handleTap(event.getX(), event.getY());
                return true;
            default:
                return true;
        }
    }

    private void handleTap(float x, float y) {
        if (prevButton.contains(x, y)) {
            page = (page + PAGES - 1) % PAGES;
            invalidate();
            return;
        }
        if (nextButton.contains(x, y)) {
            page = (page + 1) % PAGES;
            invalidate();
            return;
        }
        if (cancelButton.contains(x, y)) {
            listener.onCancel();
            return;
        }
        // Generous hit area: the thumbnail plus its caption.
        if (page == 2) {
            for (int i = 0; i < 4; i++) {
                int style = PosterRenderer.FIRST_LANDSCAPE + i;
                if (style >= PosterRenderer.STYLE_COUNT) {
                    continue;
                }
                RectF box = landCells[i];
                if (x >= box.left && x <= box.right
                        && y >= box.top && y <= box.bottom + labelSize * 1.6f) {
                    listener.onStylePicked(style);
                    return;
                }
            }
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int style = page * PER_PAGE + i;
            if (style >= PosterRenderer.FIRST_LANDSCAPE) {
                continue;
            }
            RectF box = cells[i];
            if (x >= box.left && x <= box.right
                    && y >= box.top && y <= box.bottom + labelSize * 1.6f) {
                listener.onStylePicked(style);
                return;
            }
        }
    }
}
