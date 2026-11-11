package tv.glass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// The screensaver: the wallpapers (and the picks' photos) full screen with a slow zoom, a big glass clock,
// the weather and what's playing. The clock drifts a little every minute so nothing burns in.
// Home Settings > Screensaver switches between this and Amazon's.
public class Dream extends DreamService {
    FrameLayout root;
    ImageView a, b;
    TextView time, line;
    LinearLayout card;
    List<Object> photos = new ArrayList<>();
    int index;
    float s;
    Typeface bold, regular;

    final Runnable next = new Runnable() {
        @Override public void run() {
            showNext();
            root.postDelayed(this, 20_000);
        }
    };
    final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            root.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Tr.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        s = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        bold = Typeface.createFromAsset(getAssets(), "fonts/Inter-SemiBold.ttf");
        regular = Typeface.createFromAsset(getAssets(), "fonts/Inter-Medium.ttf");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        a = image();
        b = image();
        root.addView(a, full());
        root.addView(b, full());
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0x00000000, 0x99000000}));
        root.addView(shade, full());

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(56), px(36), px(56), px(40));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x661C1C1E);
        g.setCornerRadius(px(44));
        g.setStroke(Math.max(1, px(1.5f)), 0x29FFFFFF);
        card.setBackground(g);
        time = text(bold, 150);
        time.setLetterSpacing(-0.03f);
        card.addView(time);
        line = text(regular, 34);
        line.setTextColor(0xD9FFFFFF);
        card.addView(line);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        cl.leftMargin = px(90);
        cl.bottomMargin = px(90);
        root.addView(card, cl);
        setContentView(root);

        for (String w : HomeScreen.WALLPAPERS) photos.add(w);
        File custom = new File(getFilesDir(), "wallpaper.jpg");
        if (custom.exists()) photos.add(0, custom);
        for (Picks.Pick p : Picks.list) if (p.bitmap != null) photos.add(p.bitmap);
        index = (int) (System.currentTimeMillis() / 20_000 % photos.size());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        Weather.refresh(this, this::update);
        next.run();
        tick.run();
    }

    @Override
    public void onDreamingStopped() {
        root.removeCallbacks(next);
        root.removeCallbacks(tick);
        super.onDreamingStopped();
    }

    void showNext() {
        if (photos.isEmpty()) return;
        Bitmap bm = load(photos.get(index++ % photos.size()));
        if (bm == null) return;
        final ImageView in = a.getAlpha() > 0.5f ? b : a, out = in == a ? b : a;
        in.setImageBitmap(bm);
        in.setScaleX(1.0f);
        in.setScaleY(1.0f);
        in.animate().alpha(1f).setDuration(1500).start();
        in.animate().scaleX(1.08f).scaleY(1.08f).setDuration(21_000).start();
        out.animate().alpha(0f).setDuration(1500).start();
    }

    Bitmap load(Object o) {
        if (o instanceof Bitmap) return (Bitmap) o;
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            opt.inSampleSize = 2;
            try (InputStream in = o instanceof File ? new FileInputStream((File) o) : getAssets().open("wallpapers/" + o + ".jpg")) {
                return BitmapFactory.decodeStream(in, null, opt);
            }
        } catch (Exception e) {
            return null;
        }
    }

    void update() {
        time.setText(android.text.format.DateFormat.format(android.text.format.DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", new java.util.Date()));
        StringBuilder l = new StringBuilder(android.text.format.DateFormat.format("EEEE, MMMM d", new java.util.Date()));
        if (Prefs.on(this, "weather", true) && Weather.has()) {
            l.append("   ").append(Weather.symbol(Weather.code, Sun.isDay(this))).append(" ").append(Weather.deg(Weather.temp))
                    .append(" ").append(Prefs.city(this));
        }
        NowPlaying.Track t = NowPlaying.current(this);
        if (t != null) l.append("\n").append(NowPlaying.line(t));
        line.setText(l);
        // Drift: a few dozen pixels each minute, so the clock never sits still on an OLED screen.
        long m = System.currentTimeMillis() / 60000;
        card.setTranslationX(px((float) (Math.sin(m * 0.7) * 40 + 40)));
        card.setTranslationY(-px((float) (Math.cos(m * 0.5) * 30 + 30)));
    }

    ImageView image() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        return v;
    }

    TextView text(Typeface f, float size) {
        TextView t = new TextView(this);
        t.setTypeface(f);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setIncludeFontPadding(false);
        return t;
    }

    FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    int px(float v) { return Math.round(v * s); }
}
