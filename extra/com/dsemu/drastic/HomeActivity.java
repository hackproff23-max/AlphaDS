package com.dsemu.drastic;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Tela inicial do AlphaDS: grade de jogos + barra de icones embaixo. */
public class HomeActivity extends Activity {
    private static final int REQ_TREE = 11;
    private static final int GLOW = 0xFF3D8BFF;

    private static class Game {
        Uri uri;
        String title;
        Bitmap shown;
    }

    private final List<Game> games = new ArrayList<Game>();
    private BaseAdapter adapter;
    private TextView empty;
    private SharedPreferences prefs;
    private float d;
    private int iconPx;

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.layoutInDisplayCutoutMode = 1; // SHORT_EDGES: desenha ate a borda, atras do recorte da camera
            getWindow().setAttributes(wlp);
        }
        goImmersive();
        d = getResources().getDisplayMetrics().density;
        iconPx = dp(96);
        prefs = getSharedPreferences("alphads_home", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFECECF0);
        root.setPadding(0, dp(14), 0, 0);

        TextView title = new TextView(this);
        title.setText("AlphaDS");
        title.setTextColor(0xFF222222);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(20), dp(12), dp(20), dp(4));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(14));
        grid.setHorizontalSpacing(dp(6));
        grid.setPadding(dp(14), dp(10), dp(14), dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new GameAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                launch(games.get(pos));
            }
        });
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        empty = new TextView(this);
        empty.setText("Toque na pasta amarela para escolher a pasta dos seus jogos");
        empty.setTextColor(0xFF666666);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), 0, dp(32), 0);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildDock(grid), dockParams());
        setContentView(root);
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    private LinearLayout.LayoutParams dockParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(10), dp(4), dp(10), dp(12));
        return lp;
    }

    private View buildDock(final GridView grid) {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(6), dp(8), dp(6), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEEF7F7F9);
        bg.setCornerRadius(dp(40));
        dock.setBackgroundDrawable(bg);

        // casinha: sair
        dock.addView(slot(IconView.HOME, 0xFFFFFFFF, 0xFF111111, new View.OnClickListener() {
            public void onClick(View v) {
                finish(); // casinha = sair do app
            }
        }));
        // vermelho: textos
        dock.addView(slot(IconView.TEXT, 0xFFE53935, 0xFFFFFFFF,
                soon("Opcoes de texto: tamanho, fonte, contorno, cor de fundo, sombra e background")));
        // amarelo: escolher pasta com todos os jogos
        dock.addView(slot(IconView.FOLDER, 0xFFFFA000, 0xFFFFFFFF, new View.OnClickListener() {
            public void onClick(View v) {
                pickFolder();
            }
        }));
        // azul: shaders
        dock.addView(slot(IconView.SHADER, 0xFF1565C0, 0xFFFFFFFF, soon("Shaders")));
        // verde: video
        dock.addView(slot(IconView.VIDEO, 0xFF2E9E3E, 0xFFFFFFFF, soon("Opcoes de video")));
        // branco: controle
        dock.addView(slot(IconView.PAD, 0xFFFFFFFF, 0xFF3A3A3C, soon("Controle")));
        // cinza: configuracoes
        dock.addView(slot(IconView.GEAR, 0xFF3A3A3C, 0xFFFFFFFF, soon("Configuracoes")));
        return dock;
    }

    private View.OnClickListener soon(final String what) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(HomeActivity.this, what + " - em breve", Toast.LENGTH_SHORT).show();
            }
        };
    }

    /** Espaco igual para cada botao; o botao em si e sempre um circulo perfeito. */
    private View slot(int type, int bg, int fg, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        IconView v = new IconView(this, type, bg, fg);
        v.setOnClickListener(l);
        pressScale(v);
        f.addView(v, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        return f;
    }

    private void pressScale(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(90).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                }
                return false;
            }
        });
    }

    // ---------- grade ----------

    private class GameAdapter extends BaseAdapter {
        public int getCount() {
            return games.size();
        }

        public Object getItem(int i) {
            return games.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View convert, ViewGroup parent) {
            Game g = games.get(pos);
            LinearLayout cell = new LinearLayout(HomeActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(6), dp(6), dp(6), dp(6));
            cell.setBackgroundDrawable(glowDrawable());
            cell.setClickable(false);

            ImageView img = new ImageView(HomeActivity.this);
            img.setImageBitmap(g.shown);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(img, new LinearLayout.LayoutParams(iconPx, iconPx));

            TextView name = new TextView(HomeActivity.this);
            name.setText(g.title);
            name.setTextColor(0xFF222222);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(8), dp(4), dp(8), dp(4));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0xFFFFFFFF);
            pill.setCornerRadius(dp(14));
            name.setBackgroundDrawable(pill);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(iconPx, -2);
            nlp.topMargin = dp(6);
            cell.addView(name, nlp);

            // brilho + aumento ao encostar o dedo
            cell.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    int a = e.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN) {
                        v.setPressed(true);
                        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start();
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        v.setPressed(false);
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    }
                    return false;
                }
            });
            return cell;
        }
    }

    private StateListDrawable glowDrawable() {
        GradientDrawable outer = new GradientDrawable();
        outer.setColor(0x443D8BFF);
        outer.setCornerRadius(dp(26));
        GradientDrawable inner = new GradientDrawable();
        inner.setColor(0x1A3D8BFF);
        inner.setStroke(dp(3), GLOW);
        inner.setCornerRadius(dp(22));
        LayerDrawable glow = new LayerDrawable(new android.graphics.drawable.Drawable[]{outer, inner});
        glow.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, glow);
        s.addState(new int[]{android.R.attr.state_focused}, glow);
        s.addState(new int[]{android.R.attr.state_selected}, glow);
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    // ---------- pasta / varredura ----------

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            prefs.edit().putString("tree", tree.toString()).commit();
            scan();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (games.isEmpty()) scan();
    }

    private void scan() {
        final String saved = prefs.getString("tree", null);
        if (saved == null) {
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setText("Procurando jogos...");
        empty.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            public void run() {
                final List<Game> found = new ArrayList<Game>();
                try {
                    Uri tree = Uri.parse(saved);
                    walk(tree, DocumentsContract.getTreeDocumentId(tree), found, 0);
                } catch (Exception ignored) {
                }
                Collections.sort(found, new Comparator<Game>() {
                    public int compare(Game a, Game b) {
                        return a.title.compareToIgnoreCase(b.title);
                    }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        games.clear();
                        games.addAll(found);
                        adapter.notifyDataSetChanged();
                        if (games.isEmpty()) {
                            empty.setText("Nenhum jogo .nds encontrado nessa pasta");
                            empty.setVisibility(View.VISIBLE);
                        } else {
                            empty.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }).start();
    }

    private void walk(Uri tree, String docId, List<Game> out, int depth) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 3) walk(tree, id, out, depth + 1);
                } else if (name != null && name.toLowerCase().endsWith(".nds")) {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    Game g = readGame(doc, name);
                    if (g != null) out.add(g);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    // ---------- leitura do banner do .nds ----------

    private static byte[] readAt(FileChannel ch, long pos, int len) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(len);
        ch.position(pos);
        while (bb.hasRemaining()) {
            if (ch.read(bb) < 0) break;
        }
        return bb.array();
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private Game readGame(Uri doc, String fileName) {
        String title = fileName.substring(0, fileName.length() - 4);
        Bitmap icon = null;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = getContentResolver().openFileDescriptor(doc, "r");
            if (pfd != null) {
                FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                FileChannel ch = fis.getChannel();
                byte[] h = readAt(ch, 0, 0x70);
                int bo = le32(h, 0x68);
                if (bo > 0) {
                    byte[] b = readAt(ch, bo & 0xFFFFFFFFL, 0x840);
                    String t = readTitle(b, 0x340);
                    if (t.length() == 0) t = readTitle(b, 0x240);
                    if (t.length() > 0) title = t;
                    icon = decodeIcon(b);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
        Game g = new Game();
        g.uri = doc;
        g.title = title;
        g.shown = rounded(icon);
        return g;
    }

    private static String readTitle(byte[] b, int off) {
        try {
            String s = new String(b, off, 0x100, "UTF-16LE");
            int z = s.indexOf('\u0000');
            if (z >= 0) s = s.substring(0, z);
            String[] lines = s.split("\n");
            if (lines.length >= 3) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lines.length - 1; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(lines[i].trim());
                }
                return sb.toString().trim();
            }
            return lines[0].trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Bitmap decodeIcon(byte[] b) {
        int[] pal = new int[16];
        for (int i = 1; i < 16; i++) {
            int v = le16(b, 0x220 + i * 2);
            int r = (v & 31) * 255 / 31;
            int g = ((v >> 5) & 31) * 255 / 31;
            int bl = ((v >> 10) & 31) * 255 / 31;
            pal[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        int[] px = new int[32 * 32];
        for (int ty = 0; ty < 4; ty++) {
            for (int tx = 0; tx < 4; tx++) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int off = 0x20 + (ty * 4 + tx) * 32 + y * 4 + x / 2;
                        int bt = b[off] & 0xFF;
                        int idx = (x & 1) == 0 ? (bt & 15) : (bt >> 4);
                        px[(ty * 8 + y) * 32 + tx * 8 + x] = pal[idx];
                    }
                }
            }
        }
        return Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
    }

    private Bitmap rounded(Bitmap src) {
        int s = iconPx;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        RectF r = new RectF(0, 0, s, s);
        float rad = dp(20);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(src == null ? 0xFF2A2A30 : 0xFFFFFFFF);
        cv.drawRoundRect(r, rad, rad, bg);
        if (src != null) {
            Bitmap big = Bitmap.createScaledBitmap(src, s, s, false);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new BitmapShader(big, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            cv.drawRoundRect(r, rad, rad, p);
        }
        return out;
    }

    // ---------- abrir jogo ----------

    private void launch(Game g) {
        Intent i = new Intent(Intent.ACTION_VIEW, g.uri);
        i.setComponent(new ComponentName(this, "com.dsemu.drastic.LoadingActivity"));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    // ---------- icones simples desenhados em codigo ----------

    private static class IconView extends View {
        static final int HOME = 0, TEXT = 1, FOLDER = 2, SHADER = 3, VIDEO = 4, PAD = 5, GEAR = 6;
        private final int type, bg, fg;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        IconView(Context c, int type, int bg, int fg) {
            super(c);
            this.type = type;
            this.bg = bg;
            this.fg = fg;
        }

        private void star(float cx, float cy, float big, float small) {
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(-90 + i * 45);
                float rad = (i % 2 == 0) ? big : small;
                float x = cx + (float) Math.cos(a) * rad;
                float y = cy + (float) Math.sin(a) * rad;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            p.setTypeface(Typeface.DEFAULT);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setColor(bg);
            cv.drawCircle(w / 2f, h / 2f, s / 2f, p);

            cv.save();
            cv.translate((w - s) / 2f, (h - s) / 2f);
            cv.scale(s / 100f, s / 100f);
            path.reset();
            p.setColor(fg);

            switch (type) {
                case HOME:
                    path.moveTo(50, 26);
                    path.lineTo(75, 48);
                    path.lineTo(67, 48);
                    path.lineTo(67, 73);
                    path.lineTo(55, 73);
                    path.lineTo(55, 58);
                    path.lineTo(45, 58);
                    path.lineTo(45, 73);
                    path.lineTo(33, 73);
                    path.lineTo(33, 48);
                    path.lineTo(25, 48);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                case TEXT:
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    p.setTextSize(38);
                    p.setTextAlign(Paint.Align.CENTER);
                    cv.drawText("Aa", 50, 50 - (p.ascent() + p.descent()) / 2f, p);
                    break;
                case FOLDER:
                    r.set(25, 29, 47, 43);
                    cv.drawRoundRect(r, 4, 4, p);
                    r.set(25, 36, 75, 70);
                    cv.drawRoundRect(r, 6, 6, p);
                    p.setColor(bg);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(3);
                    cv.drawLine(25, 46, 75, 46, p);
                    break;
                case SHADER:
                    star(47, 53, 25, 6);
                    cv.drawPath(path, p);
                    path.reset();
                    star(71, 29, 10, 2.5f);
                    cv.drawPath(path, p);
                    path.reset();
                    star(28, 29, 6, 1.5f);
                    cv.drawPath(path, p);
                    break;
                case VIDEO:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(6);
                    r.set(26, 30, 74, 62);
                    cv.drawRoundRect(r, 8, 8, p);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    cv.drawLine(40, 72, 60, 72, p);
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(45, 38);
                    path.lineTo(45, 54);
                    path.lineTo(59, 46);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                case PAD:
                    r.set(21, 33, 79, 62);
                    cv.drawRoundRect(r, 14, 14, p);
                    cv.drawCircle(32, 59, 10, p);
                    cv.drawCircle(68, 59, 10, p);
                    p.setColor(bg);
                    r.set(30, 44, 42, 50);
                    cv.drawRect(r, p);
                    r.set(33, 41, 39, 53);
                    cv.drawRect(r, p);
                    cv.drawCircle(62, 43, 3.5f, p);
                    cv.drawCircle(69, 50, 3.5f, p);
                    break;
                case GEAR:
                    for (int i = 0; i < 8; i++) {
                        cv.save();
                        cv.rotate(i * 45, 50, 50);
                        r.set(45, 23, 55, 37);
                        cv.drawRoundRect(r, 2, 2, p);
                        cv.restore();
                    }
                    cv.drawCircle(50, 50, 20, p);
                    p.setColor(bg);
                    cv.drawCircle(50, 50, 8, p);
                    break;
                default:
                    break;
            }
            cv.restore();
        }
    }
}
