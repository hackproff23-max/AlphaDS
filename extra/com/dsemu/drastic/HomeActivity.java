package com.dsemu.drastic;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
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
    private static boolean splashDone = false;
    private float d;
    private int iconPx;
    private FrameLayout rootFrame;

    // estilo do texto dos jogos (menu do botao "Aa" vermelho)
    private static final int DEF_TXT_SIZE = 12;           // sp
    private static final int DEF_TXT_OUTLINE = 0;         // dp
    private static final int DEF_TXT_COLOR = 0xFF222222;
    private static final int DEF_TXT_OUTLINE_COLOR = 0xFF000000;
    private static final int DEF_TXT_BG = 0xFFFFFFFF;

    private static final int[] TXT_COLORS = {
            0xFF000000, 0xFF444444, 0xFF888888, 0xFFCCCCCC, 0xFFFFFFFF, 0xFF795548,
            0xFFE53935, 0xFFFF7043, 0xFFFFA000, 0xFFFDD835, 0xFF2E9E3E, 0xFF00897B,
            0xFF1565C0, 0xFF3D8BFF, 0xFF8E24AA, 0xFFD81B60
    };

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

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF000000);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        rootFrame = frame;
        if (!splashDone) {
            splashDone = true;
            addSplash(frame);
        }
        setContentView(frame);
    }

    /** Mesma tela de carregamento do jogo, mostrada ao abrir o app. */
    private void addSplash(FrameLayout frame) {
        final LinearLayout splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(0xFF000000);
        splash.setClickable(true);

        Typeface tf = Typeface.DEFAULT_BOLD;
        try {
            tf = Typeface.createFromAsset(getAssets(), "fonts/BebasKai.ttf");
        } catch (Exception ignored) {
        }

        ImageView logo = new ImageView(this);
        try {
            logo.setImageBitmap(BitmapFactory.decodeStream(getAssets().open("logo.png")));
        } catch (Exception ignored) {
        }
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(140), dp(140));
        llp.bottomMargin = dp(24);
        splash.addView(logo, llp);

        final TextView name = new TextView(this);
        name.setTypeface(tf);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        splash.addView(name, new LinearLayout.LayoutParams(-2, -2));

        final TextView load = new TextView(this);
        load.setTypeface(tf);
        load.setTextColor(0xFF9E9E9E);
        load.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        load.setPadding(0, dp(2), 0, 0);
        splash.addView(load, new LinearLayout.LayoutParams(-2, -2));

        frame.addView(splash, new FrameLayout.LayoutParams(-1, -1));

        final Handler h = new Handler(Looper.getMainLooper());
        final String n = "alphaDS";
        final String c = "Carregando...";
        h.post(new Runnable() {
            int i = 0;

            public void run() {
                if (i <= n.length()) {
                    name.setText(n.substring(0, i));
                    i++;
                    h.postDelayed(this, 80);
                } else if (i <= n.length() + c.length() + 1) {
                    load.setText(c.substring(0, i - n.length() - 1));
                    i++;
                    h.postDelayed(this, 40);
                } else {
                    splash.animate().setStartDelay(250).alpha(0f).setDuration(250)
                            .withEndAction(new Runnable() {
                                public void run() {
                                    splash.setVisibility(View.GONE);
                                }
                            }).start();
                }
            }
        });
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
        dock.addView(slot(IconView.TEXT, 0xFFE53935, 0xFFFFFFFF, new View.OnClickListener() {
            public void onClick(View v) {
                showTextMenu();
            }
        }));
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

            StyledTextView name = new StyledTextView(HomeActivity.this);
            name.setText(g.title);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(8), dp(4), dp(8), dp(4));
            applyTextStyle(name);
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

    // ---------- opcoes de texto (botao vermelho "Aa") ----------

    private int txtSize() {
        return prefs.getInt("txt_size", DEF_TXT_SIZE);
    }

    private int txtOutline() {
        return prefs.getInt("txt_outline", DEF_TXT_OUTLINE);
    }

    private boolean txtShadow() {
        return prefs.getBoolean("txt_shadow", false);
    }

    private int txtColor() {
        return prefs.getInt("txt_color", DEF_TXT_COLOR);
    }

    private int txtOutlineColor() {
        return prefs.getInt("txt_outline_color", DEF_TXT_OUTLINE_COLOR);
    }

    private int txtBg() {
        return prefs.getInt("txt_bg", DEF_TXT_BG);
    }

    /** Aplica o estilo salvo a um rotulo de jogo. */
    private void applyTextStyle(StyledTextView tv) {
        tv.setTextStyle(txtSize(), txtColor(), txtOutline(), txtOutlineColor(), txtShadow());
        int bg = txtBg();
        if (Color.alpha(bg) == 0) {
            tv.setBackgroundDrawable(null);
        } else {
            tv.setBackgroundDrawable(textPill(bg));
        }
    }

    private GradientDrawable textPill(int bgColor) {
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(bgColor);
        pill.setCornerRadius(dp(14));
        return pill;
    }

    private void stylePreview(StyledTextView tv, int[] size, int[] outline, boolean[] shadow,
                              int[] color, int[] ocolor, int[] bg) {
        tv.setTextStyle(size[0], color[0], outline[0], ocolor[0], shadow[0]);
        if (Color.alpha(bg[0]) == 0) {
            tv.setBackgroundDrawable(null);
        } else {
            tv.setBackgroundDrawable(textPill(bg[0]));
        }
    }

    /** Salva as opcoes atuais e atualiza os rotulos da grade. */
    private void persistText(int[] size, int[] outline, boolean[] shadow,
                             int[] color, int[] ocolor, int[] bg) {
        prefs.edit()
                .putInt("txt_size", size[0])
                .putInt("txt_outline", outline[0])
                .putBoolean("txt_shadow", shadow[0])
                .putInt("txt_color", color[0])
                .putInt("txt_outline_color", ocolor[0])
                .putInt("txt_bg", bg[0])
                .apply();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private TextView menuLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF55555C);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView menuValue() {
        TextView t = new TextView(this);
        t.setTextColor(0xFF3D8BFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pillButton(String s, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(24));
        t.setBackgroundDrawable(g);
        return t;
    }

    private LinearLayout.LayoutParams cwlp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        return lp;
    }

    /** Abre o menu de opcoes de texto sobre a tela inicial. */
    private void showTextMenu() {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x66000000);
        overlay.setClickable(true);

        final Runnable closer = new Runnable() {
            public void run() {
                rootFrame.removeView(overlay);
            }
        };
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
            }
        });

        ScrollView panel = new ScrollView(this);
        panel.setVerticalScrollBarEnabled(false);
        panel.setFillViewport(true);
        panel.setClickable(true);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFFF7F7F9);
        pbg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        panel.setBackgroundDrawable(pbg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(20));
        panel.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // cabecalho
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Opcoes de texto");
        title.setTextColor(0xFF222222);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("X");
        x.setTextColor(0xFF666666);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setTypeface(Typeface.DEFAULT_BOLD);
        x.setGravity(Gravity.CENTER);
        x.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable xbg = new GradientDrawable();
        xbg.setColor(0xFFE8E8EC);
        xbg.setCornerRadius(dp(16));
        x.setBackgroundDrawable(xbg);
        x.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
            }
        });
        head.addView(x, new LinearLayout.LayoutParams(-2, -2));
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // previa
        TextView pvLabel = menuLabel("Previa");
        pvLabel.setPadding(0, dp(12), 0, dp(4));
        content.addView(pvLabel, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout prevArea = new FrameLayout(this);
        GradientDrawable prevBg = new GradientDrawable();
        prevBg.setColor(0xFF2A2A30);
        prevBg.setCornerRadius(dp(12));
        prevArea.setBackgroundDrawable(prevBg);
        prevArea.setPadding(dp(10), dp(16), dp(10), dp(16));
        final StyledTextView preview = new StyledTextView(this);
        preview.setText("Texto de exemplo");
        preview.setGravity(Gravity.CENTER);
        prevArea.addView(preview, new FrameLayout.LayoutParams(-1, -2));
        content.addView(prevArea, new LinearLayout.LayoutParams(-1, -2));

        // estado de trabalho (arranjos de 1 posicao para mudar dentro dos listeners)
        final int[] size = new int[]{txtSize()};
        final int[] outline = new int[]{txtOutline()};
        final boolean[] shadow = new boolean[]{txtShadow()};
        final int[] color = new int[]{txtColor()};
        final int[] ocolor = new int[]{txtOutlineColor()};
        final int[] bg = new int[]{txtBg()};

        final TextView sizeVal = menuValue();
        sizeVal.setText(size[0] + " sp");
        final TextView outlineVal = menuValue();
        outlineVal.setText(outline[0] + " dp");

        final SeekBar sbSize = new SeekBar(this);
        sbSize.setMax(20); // 8..28 sp
        sbSize.setProgress(size[0] - 8);
        final SeekBar sbOutline = new SeekBar(this);
        sbOutline.setMax(8); // 0..8 dp
        sbOutline.setProgress(outline[0]);
        final Switch swShadow = new Switch(this);
        swShadow.setText("Sombra");
        swShadow.setTextColor(0xFF222222);
        swShadow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        swShadow.setChecked(shadow[0]);

        final Runnable onChange = new Runnable() {
            public void run() {
                stylePreview(preview, size, outline, shadow, color, ocolor, bg);
                persistText(size, outline, shadow, color, ocolor, bg);
            }
        };

        final SwatchRow colorRow = new SwatchRow(color, onChange);
        final SwatchRow ocolorRow = new SwatchRow(ocolor, onChange);
        final SwatchRow bgRow = new SwatchRow(bg, onChange);
        for (int i = 0; i < TXT_COLORS.length; i++) {
            colorRow.add(TXT_COLORS[i]);
            ocolorRow.add(TXT_COLORS[i]);
        }
        bgRow.add(0x00000000); // primeira opcao: fundo transparente
        for (int i = 0; i < TXT_COLORS.length; i++) {
            bgRow.add(TXT_COLORS[i]);
        }

        sbSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                size[0] = p + 8;
                sizeVal.setText(size[0] + " sp");
                onChange.run();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        sbOutline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                outline[0] = p;
                outlineVal.setText(outline[0] + " dp");
                onChange.run();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        swShadow.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                shadow[0] = checked;
                onChange.run();
            }
        });

        // tamanho de texto
        LinearLayout rowSize = new LinearLayout(this);
        rowSize.setOrientation(LinearLayout.HORIZONTAL);
        rowSize.setGravity(Gravity.CENTER_VERTICAL);
        rowSize.setPadding(0, dp(12), 0, 0);
        rowSize.addView(menuLabel("Tamanho de texto"), new LinearLayout.LayoutParams(0, -2, 1f));
        rowSize.addView(sizeVal, new LinearLayout.LayoutParams(-2, -2));
        content.addView(rowSize, new LinearLayout.LayoutParams(-1, -2));
        content.addView(sbSize, new LinearLayout.LayoutParams(-1, -2));

        // tamanho do contorno
        LinearLayout rowOutline = new LinearLayout(this);
        rowOutline.setOrientation(LinearLayout.HORIZONTAL);
        rowOutline.setGravity(Gravity.CENTER_VERTICAL);
        rowOutline.setPadding(0, dp(10), 0, 0);
        rowOutline.addView(menuLabel("Tamanho do contorno"), new LinearLayout.LayoutParams(0, -2, 1f));
        rowOutline.addView(outlineVal, new LinearLayout.LayoutParams(-2, -2));
        content.addView(rowOutline, new LinearLayout.LayoutParams(-1, -2));
        content.addView(sbOutline, new LinearLayout.LayoutParams(-1, -2));

        // sombra
        LinearLayout rowShadow = new LinearLayout(this);
        rowShadow.setOrientation(LinearLayout.HORIZONTAL);
        rowShadow.setGravity(Gravity.CENTER_VERTICAL);
        rowShadow.setPadding(0, dp(10), 0, 0);
        rowShadow.addView(menuLabel("Sombra"), new LinearLayout.LayoutParams(0, -2, 1f));
        rowShadow.addView(swShadow, new LinearLayout.LayoutParams(-2, -2));
        content.addView(rowShadow, new LinearLayout.LayoutParams(-1, -2));

        // cores
        TextView lblColor = menuLabel("Cor da letra");
        lblColor.setPadding(0, dp(14), 0, dp(6));
        content.addView(lblColor, new LinearLayout.LayoutParams(-1, -2));
        content.addView(colorRow.build(), cwlp(0));

        TextView lblOcolor = menuLabel("Cor do contorno");
        lblOcolor.setPadding(0, dp(14), 0, dp(6));
        content.addView(lblOcolor, new LinearLayout.LayoutParams(-1, -2));
        content.addView(ocolorRow.build(), cwlp(0));

        TextView lblBg = menuLabel("Cor do fundo");
        lblBg.setPadding(0, dp(14), 0, dp(6));
        content.addView(lblBg, new LinearLayout.LayoutParams(-1, -2));
        content.addView(bgRow.build(), cwlp(0));
        TextView hintBg = new TextView(this);
        hintBg.setText("Na cor do fundo, a primeira opcao deixa o fundo transparente");
        hintBg.setTextColor(0xFF9A9AA2);
        hintBg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hintBg.setPadding(0, dp(4), 0, 0);
        content.addView(hintBg, new LinearLayout.LayoutParams(-1, -2));

        // botoes
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = pillButton("Padrao", 0xFFE8E8EC, 0xFF333338);
        TextView closeBtn = pillButton("Fechar", 0xFF3D8BFF, 0xFFFFFFFF);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.rightMargin = dp(8);
        btns.addView(reset, rlp);
        btns.addView(closeBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(btns, cwlp(16));

        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                size[0] = DEF_TXT_SIZE;
                outline[0] = DEF_TXT_OUTLINE;
                shadow[0] = false;
                color[0] = DEF_TXT_COLOR;
                ocolor[0] = DEF_TXT_OUTLINE_COLOR;
                bg[0] = DEF_TXT_BG;
                sbSize.setProgress(DEF_TXT_SIZE - 8);
                sbOutline.setProgress(DEF_TXT_OUTLINE);
                swShadow.setChecked(false);
                sizeVal.setText(size[0] + " sp");
                outlineVal.setText(outline[0] + " dp");
                colorRow.refresh();
                ocolorRow.refresh();
                bgRow.refresh();
                onChange.run();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
            }
        });

        // estado inicial da previa e dos aneis de selecao
        stylePreview(preview, size, outline, shadow, color, ocolor, bg);
        colorRow.refresh();
        ocolorRow.refresh();
        bgRow.refresh();

        overlay.addView(panel, new FrameLayout.LayoutParams(-1,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.86f), Gravity.BOTTOM));
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Linha rolavel de circulos de cor; marca com um anel a cor escolhida. */
    private class SwatchRow {
        private final HorizontalScrollView holder = new HorizontalScrollView(HomeActivity.this);
        private final LinearLayout box = new LinearLayout(HomeActivity.this);
        private final List<GradientDrawable> bgs = new ArrayList<GradientDrawable>();
        private final List<Integer> cols = new ArrayList<Integer>();
        private final int[] current;
        private final Runnable cb;

        SwatchRow(int[] cur, Runnable callback) {
            current = cur;
            cb = callback;
            holder.setHorizontalScrollBarEnabled(false);
            box.setOrientation(LinearLayout.HORIZONTAL);
            holder.addView(box, new FrameLayout.LayoutParams(-2, -2));
        }

        SwatchRow add(int c) {
            View v = new View(HomeActivity.this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.alpha(c) == 0 ? 0xFFFFFFFF : c);
            if (Color.alpha(c) == 0) {
                g.setStroke(dp(2), 0xFFBDBDBD);
            }
            v.setBackgroundDrawable(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.rightMargin = dp(8);
            box.addView(v, lp);
            final int idx = cols.size();
            cols.add(Integer.valueOf(c));
            bgs.add(g);
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View vv) {
                    current[0] = cols.get(idx).intValue();
                    refresh();
                    cb.run();
                }
            });
            return this;
        }

        View build() {
            return holder;
        }

        void refresh() {
            for (int i = 0; i < bgs.size(); i++) {
                GradientDrawable g = bgs.get(i);
                int c = cols.get(i).intValue();
                if (c == current[0]) {
                    g.setStroke(dp(3), 0xFF3D8BFF);
                } else if (Color.alpha(c) == 0) {
                    g.setStroke(dp(2), 0xFFBDBDBD);
                } else {
                    g.setStroke(0, 0);
                }
            }
        }
    }

    /** TextView que desenha contorno e sombra no texto (nomes dos jogos e previa). */
    private class StyledTextView extends TextView {
        private int outlineDp;
        private int outlineColor;
        private boolean shadowOn;

        StyledTextView(Context c) {
            super(c);
        }

        void setTextStyle(int sizeSp, int color, int outline, int ocolor, boolean shadow) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            setTextColor(color);
            outlineDp = outline;
            outlineColor = ocolor;
            shadowOn = shadow;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (outlineDp <= 0 && !shadowOn) {
                super.onDraw(cv);
                return;
            }
            TextPaint tp = getPaint();
            Paint.Style keepStyle = tp.getStyle();
            int keepColor = tp.getColor();
            float dens = getResources().getDisplayMetrics().density;
            if (outlineDp > 0) {
                tp.setStyle(Paint.Style.STROKE);
                tp.setStrokeWidth(outlineDp * 2f * dens); // 2x: metade fica embaixo do preenchimento
                tp.setColor(outlineColor);
                if (shadowOn) {
                    tp.setShadowLayer(3f * dens, dens, 2f * dens, 0x99000000);
                }
                super.onDraw(cv); // passada do contorno
                tp.clearShadowLayer();
                tp.setStyle(keepStyle);
                tp.setStrokeWidth(0f);
                tp.setColor(keepColor);
            } else if (shadowOn) {
                tp.setShadowLayer(3f * dens, dens, 2f * dens, 0x99000000);
            }
            super.onDraw(cv); // preenchimento
            tp.clearShadowLayer();
        }
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
