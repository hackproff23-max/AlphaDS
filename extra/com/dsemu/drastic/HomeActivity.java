package com.dsemu.drastic;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
        d = getResources().getDisplayMetrics().density;
        iconPx = dp(96);
        prefs = getSharedPreferences("alphads_home", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFECECF0);
        int top = 0;
        int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (rid > 0) top = getResources().getDimensionPixelSize(rid);
        root.setPadding(0, top, 0, 0);

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

    private LinearLayout.LayoutParams dockParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(10), dp(4), dp(10), dp(12));
        return lp;
    }

    private View buildDock(final GridView grid) {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEEF7F7F9);
        bg.setCornerRadius(dp(40));
        dock.setBackgroundDrawable(bg);

        // inicio
        dock.addView(dockButton("\u2302", 0xFFFFFFFF, 0xFF111111, new View.OnClickListener() {
            public void onClick(View v) {
                grid.smoothScrollToPosition(0);
            }
        }));
        // vermelho: textos
        dock.addView(dockButton("Aa", 0xFFE53935, 0xFFFFFFFF, soon("Opcoes de texto: tamanho, fonte, contorno, cor de fundo, sombra e background")));
        // amarelo: escolher pasta com todos os jogos
        dock.addView(dockButton("\uD83D\uDCC1", 0xFFFFA000, 0xFFFFFFFF, new View.OnClickListener() {
            public void onClick(View v) {
                pickFolder();
            }
        }));
        // azul: shaders
        dock.addView(dockButton("\u2726", 0xFF1565C0, 0xFFFFFFFF, soon("Shaders")));
        // verde: video
        dock.addView(dockButton("\u25A3", 0xFF2E9E3E, 0xFFFFFFFF, soon("Opcoes de video")));
        // branco: controle
        dock.addView(dockButton("\uD83C\uDFAE", 0xFFFFFFFF, 0xFF111111, soon("Controle")));
        // cinza: configuracoes
        dock.addView(dockButton("\u2699", 0xFF3A3A3C, 0xFFFFFFFF, soon("Configuracoes")));
        return dock;
    }

    private View.OnClickListener soon(final String what) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(HomeActivity.this, what + " - em breve", Toast.LENGTH_SHORT).show();
            }
        };
    }

    private View dockButton(String glyph, int color, int glyphColor, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(glyphColor);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        t.setBackgroundDrawable(g);
        t.setOnClickListener(l);
        pressScale(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        // largura = altura para ficar redondo
        t.setLayoutParams(lp);
        return t;
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
}
