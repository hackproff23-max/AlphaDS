package com.dsemu.drastic;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
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
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Tela inicial do AlphaDS: grade de jogos + barra de icones embaixo. */
public class HomeActivity extends Activity {
    private static final int REQ_TREE = 11;
    private static final int REQ_IMPORT = 12;
    private static final int REQ_SKIN = 13;
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
    static volatile String keepTree;

    public static boolean keep(Uri u) {
        String k = keepTree;
        return k != null && u != null && k.equals(u.toString());
    }
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
    private static final int DEF_TXT_SHADOW_COLOR = 0x99000000;

    private static final int[] SHADOW_COLORS = {
            0x99000000, 0xCC000000, 0x66000000, 0x33FFFFFF, 0xFFFFFFFF, 0xFF000000,
            0xFF444444, 0xFF888888, 0xFFCCCCCC, 0xFF3D8BFF, 0xFFE53935, 0xFFFFA000,
            0xFF2E9E3E
    };

    private static Typeface sBebas;

    // captura de tecla para o mapeamento do menu Controle
    private int capturingKeySlot = -1;
    private int keymapProfile = 0;
    private java.util.HashMap<Integer, TextView> keyRowValues =
            new java.util.HashMap<Integer, TextView>();

    private static final int[] TXT_COLORS = {
            0xFF000000, 0xFF444444, 0xFF888888, 0xFFCCCCCC, 0xFFFFFFFF, 0xFF795548,
            0xFFE53935, 0xFFFF7043, 0xFFFFA000, 0xFFFDD835, 0xFF2E9E3E, 0xFF00897B,
            0xFF1565C0, 0xFF3D8BFF, 0xFF8E24AA, 0xFFD81B60
    };

    /** Mostra as pastas de shaders do repositorio e baixa so as marcadas. */
    private void chooseAndStartShaderDownload(final TextView dl, final Uri tree,
                                              final ShaderTools.Progress uiP) {
        dl.setEnabled(false);
        dl.setAlpha(0.5f);
        Toast.makeText(this, "Buscando lista de shaders...", Toast.LENGTH_SHORT).show();
        ShaderTools.listCategories(new ShaderTools.Categories() {
            public void got(final List<String> names) {
                if (names == null) {
                    beginShaderDownload(tree, uiP, null);
                    return;
                }
                final boolean[] checked = new boolean[names.size()];
                final AlertDialog d = new AlertDialog.Builder(HomeActivity.this)
                        .setTitle("Quais shaders baixar?")
                        .setMultiChoiceItems(names.toArray(new String[0]), checked,
                                new android.content.DialogInterface.OnMultiChoiceClickListener() {
                                    public void onClick(android.content.DialogInterface di, int which, boolean on) {
                                        checked[which] = on;
                                    }
                                })
                        .setPositiveButton("Baixar", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface di, int w) {
                                ArrayList<String> sel = new ArrayList<String>();
                                for (int i = 0; i < checked.length; i++) if (checked[i]) sel.add(names.get(i));
                                if (sel.isEmpty()) {
                                    Toast.makeText(HomeActivity.this, "Nenhuma shader marcada", Toast.LENGTH_SHORT).show();
                                    dl.setEnabled(true);
                                    dl.setAlpha(1f);
                                    return;
                                }
                                beginShaderDownload(tree, uiP, sel.toArray(new String[0]));
                            }
                        })
                        .setNeutralButton("Marcar todas", null)
                        .setNegativeButton("Cancelar", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface di, int w) {
                                dl.setEnabled(true);
                                dl.setAlpha(1f);
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            public void onCancel(android.content.DialogInterface di) {
                                dl.setEnabled(true);
                                dl.setAlpha(1f);
                            }
                        })
                        .create();
                d.show();
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        boolean any = false;
                        for (boolean b : checked) if (b) any = true;
                        for (int i = 0; i < checked.length; i++) {
                            checked[i] = !any;
                            d.getListView().setItemChecked(i, !any);
                        }
                        ((TextView) v).setText(any ? "Marcar todas" : "Desmarcar todas");
                    }
                });
            }
        });
    }

    private void beginShaderDownload(Uri tree, ShaderTools.Progress uiP, String[] only) {
        uiP.update("Iniciando...", -1);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
            } catch (Throwable ignored) {
            }
        }
        ShaderDownloadService.start(this, tree, only);
        Toast.makeText(this,
                "Download continua em segundo plano (veja a notificacao)",
                Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    /** Fonte do app inteiro (BebasKai, dos assets), carregada uma vez. */
    private Typeface bebas() {
        if (sBebas == null) {
            try {
                sBebas = Typeface.createFromAsset(getAssets(), "fonts/BebasKai.ttf");
            } catch (Exception e) {
                sBebas = Typeface.DEFAULT_BOLD;
            }
        }
        return sBebas;
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
        keepTree = prefs.getString("tree", null);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFECECF0);
        root.setPadding(0, dp(14), 0, 0);

        TextView title = new TextView(this);
        title.setText("AlphaDS");
        title.setTextColor(0xFF222222);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(bebas());
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
        empty.setTypeface(bebas());
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

        Typeface tf = bebas();

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
        dock.addView(slot(IconView.SHADER, 0xFF1565C0, 0xFFFFFFFF, new View.OnClickListener() {
            public void onClick(View v) {
                showShaderMenu();
            }
        }));
        // verde: video
        dock.addView(slot(IconView.VIDEO, 0xFF2E9E3E, 0xFFFFFFFF, new View.OnClickListener() {
            public void onClick(View v) {
                showVideoMenu();
            }
        }));
        // branco: controle
        dock.addView(slot(IconView.PAD, 0xFFFFFFFF, 0xFF3A3A3C, new View.OnClickListener() {
            public void onClick(View v) {
                showPadMenu();
            }
        }));
        // cinza: configuracoes
        dock.addView(slot(IconView.GEAR, 0xFF3A3A3C, 0xFFFFFFFF, new View.OnClickListener() {
            public void onClick(View v) {
                showSettingsMenu();
            }
        }));
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
        IconView v = new IconView(this, type, bg, fg, bebas());
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

    private boolean pendingPick;
    private File browseDir;
    private final ArrayList<File> browseDirs = new ArrayList<File>();

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Botao amarelo: seletor de pasta proprio (sem abrir o gerenciador de arquivos). */
    private void pickFolder() {
        if (hasAllFiles()) {
            showFolderPicker();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this)
                    .setTitle("Permissao necessaria")
                    .setMessage("Para ler a pasta dos jogos, ative \"Permitir gerenciar todos os arquivos\" para o AlphaDS na proxima tela e volte.")
                    .setPositiveButton("Abrir", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            pendingPick = true;
                            try {
                                startActivity(new Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Exception e) {
                                startActivity(new Intent(
                                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        } else {
            pendingPick = true;
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 21);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == 21 && pendingPick) {
            pendingPick = false;
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                showFolderPicker();
            }
        }
    }

    private void fillBrowse(TextView path, ArrayList<String> names,
                            android.widget.ArrayAdapter<String> ad) {
        path.setText(browseDir.getAbsolutePath());
        names.clear();
        browseDirs.clear();
        File parent = browseDir.getParentFile();
        if (parent != null) {
            names.add(".. (subir)");
            browseDirs.add(parent);
        }
        File[] fs = browseDir.listFiles();
        ArrayList<File> sub = new ArrayList<File>();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory() && !f.getName().startsWith(".")) sub.add(f);
            }
        }
        Collections.sort(sub, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : sub) {
            names.add(f.getName() + "/");
            browseDirs.add(f);
        }
        ad.notifyDataSetChanged();
    }

    private void showFolderPicker() {
        String saved = prefs.getString("gamedir", null);
        File start = saved != null ? new File(saved) : android.os.Environment.getExternalStorageDirectory();
        if (!start.isDirectory()) start = android.os.Environment.getExternalStorageDirectory();
        browseDir = start;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final TextView path = new TextView(this);
        path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        path.setPadding(0, 0, 0, dp(8));
        final android.widget.ListView list = new android.widget.ListView(this);
        final ArrayList<String> names = new ArrayList<String>();
        final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, names);
        list.setAdapter(ad);
        box.addView(path, new LinearLayout.LayoutParams(-1, -2));
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(320)));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= browseDirs.size()) return;
                browseDir = browseDirs.get(pos);
                fillBrowse(path, names, ad);
                list.setSelection(0);
            }
        });
        fillBrowse(path, names, ad);

        new AlertDialog.Builder(this)
                .setTitle("Pasta dos jogos")
                .setView(box)
                .setPositiveButton("Usar esta pasta", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.edit().putString("gamedir", browseDir.getAbsolutePath()).commit();
                        games.clear();
                        adapter.notifyDataSetChanged();
                        scan();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void scanFiles(final File root) {
        empty.setText("Procurando jogos...");
        empty.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            public void run() {
                final List<Game> found = new ArrayList<Game>();
                final boolean perm = hasAllFiles();
                try {
                    if (perm) walkFiles(root, found, 0);
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
                        if (!perm) {
                            empty.setText("Sem permissao para ler arquivos. Toque na pasta amarela");
                            empty.setVisibility(View.VISIBLE);
                        } else if (games.isEmpty()) {
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

    private void walkFiles(File dir, List<Game> out, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (depth < 3 && !n.startsWith(".")) walkFiles(f, out, depth + 1);
            } else if (n.toLowerCase().endsWith(".nds")) {
                Game g = readGameFile(f);
                if (g != null) out.add(g);
            }
        }
    }

    private Game readGameFile(File f) {
        String fileName = f.getName();
        String title = fileName.substring(0, fileName.length() - 4);
        Bitmap icon = null;
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            FileChannel ch = raf.getChannel();
            byte[] h = readAt(ch, 0, 0x70);
            int bo = le32(h, 0x68);
            if (bo > 0) {
                byte[] b = readAt(ch, bo & 0xFFFFFFFFL, 0x840);
                String t = readTitle(b, 0x340);
                if (t.length() == 0) t = readTitle(b, 0x240);
                if (t.length() > 0) title = t;
                icon = decodeIcon(b);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
        }
        Game g = new Game();
        g.uri = Uri.fromFile(f);
        g.title = title;
        g.shown = rounded(icon);
        return g;
    }

    private void pickShaderFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            prefs.edit().putString("tree", tree.toString()).commit();
            keepTree = tree.toString();
            scan();
        } else if (req == REQ_IMPORT && res == RESULT_OK && data != null) {
            final ArrayList<Uri> uris = new ArrayList<Uri>();
            final ArrayList<String> names = new ArrayList<String>();
            if (data.getData() != null) {
                uris.add(data.getData());
                names.add(docName(data.getData()));
            }
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) {
                        uris.add(u);
                        names.add(docName(u));
                    }
                }
            }
            final Uri tree = treeUri();
            if (tree == null || uris.isEmpty()) return;
            new Thread(new Runnable() {
                public void run() {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < uris.size(); i++) {
                        String nm = names.get(i);
                        if (nm == null) nm = "shader " + (i + 1);
                        String r = ShaderTools.importFile(HomeActivity.this, tree, uris.get(i), nm);
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(r);
                    }
                    final String msg = sb.toString();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_LONG).show();
                            fillShaderList();
                        }
                    });
                }
            }).start();
        } else if (req == REQ_SKIN && res == RESULT_OK && data != null) {
            final ArrayList<Uri> uris = new ArrayList<Uri>();
            if (data.getData() != null) {
                uris.add(data.getData());
            }
            android.content.ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) {
                        uris.add(u);
                    }
                }
            }
            if (uris.isEmpty()) return;
            // limpa a pasta do skin e copia as imagens escolhidas para la;
            // o emulador le os PNGs de _VirtualPadSkinFolder
            File dir = new File(getFilesDir(), "padskin");
            if (dir.exists()) {
                File[] old = dir.listFiles();
                if (old != null) {
                    for (File f : old) {
                        f.delete();
                    }
                }
            } else {
                dir.mkdirs();
            }
            final File skinDir = dir;
            new Thread(new Runnable() {
                public void run() {
                    int ok = 0;
                    for (int i = 0; i < uris.size(); i++) {
                        String nm = docName(uris.get(i));
                        if (nm == null || nm.length() == 0) {
                            nm = "botao_" + (i + 1) + ".png";
                        }
                        if (!nm.toLowerCase().endsWith(".png")) {
                            nm = nm + ".png";
                        }
                        try {
                            InputStream in = getContentResolver().openInputStream(uris.get(i));
                            OutputStream out = new FileOutputStream(new File(skinDir, nm));
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                            }
                            out.close();
                            in.close();
                            ok++;
                        } catch (Exception ignored) {
                        }
                    }
                    if (ok > 0) {
                        draPrefs().edit().putString("_VirtualPadSkinFolder",
                                skinDir.getAbsolutePath()).commit();
                    }
                    final int nok = ok;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (nok > 0) {
                                Toast.makeText(HomeActivity.this, nok
                                        + " imagem(ns) importada(s). Vale a partir do "
                                        + "proximo jogo aberto.", Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(HomeActivity.this,
                                        "Nao foi possivel importar as imagens.",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }).start();
        }
    }

    /** Enquanto o mapeamento esta esperando, captura qualquer tecla do teclado/controle. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (capturingKeySlot >= 0) {
            int a = e.getAction();
            if (a == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                int code = e.getKeyCode();
                if (code == KeyEvent.KEYCODE_BACK) {
                    capturingKeySlot = -1;
                    Toast.makeText(this, "Mapeamento cancelado", Toast.LENGTH_SHORT).show();
                    refreshKeyRows();
                    return true;
                }
                String dev = "teclado";
                try {
                    if (e.getDevice() != null && e.getDevice().getName() != null
                            && e.getDevice().getName().length() > 0) {
                        dev = e.getDevice().getName();
                    }
                } catch (Exception ignored) {
                }
                SharedPreferences p = draPrefs();
                p.edit().putInt("_KeyMapConfigs_" + keymapProfile + "_" + capturingKeySlot,
                        code).commit();
                p.edit().putString("_KeyMapDevices_" + keymapProfile, dev).commit();
                Toast.makeText(this, "Mapeado: " + keyCodeName(code), Toast.LENGTH_SHORT).show();
                capturingKeySlot = -1;
                refreshKeyRows();
                return true;
            }
            return true; // ignora repeticoes/ups enquanto captura
        }
        return super.dispatchKeyEvent(e);
    }

    private String keyCodeName(int code) {
        String s = KeyEvent.keyCodeToString(code); // "KEYCODE_BUTTON_A"
        if (s.startsWith("KEYCODE_")) {
            s = s.substring(8);
        }
        return s.toLowerCase().replace("_", " ");
    }

    private void refreshKeyRows() {
        SharedPreferences p = draPrefs();
        for (int i = 0; i < PAD_MAP_SLOTS.length; i++) {
            TextView tv = keyRowValues.get(PAD_MAP_SLOTS[i]);
            if (tv != null) {
                int code = p.getInt("_KeyMapConfigs_" + keymapProfile + "_" + PAD_MAP_SLOTS[i], -1);
                tv.setText(code < 0 ? "-" : keyCodeName(code));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingPick && hasAllFiles()) {
            pendingPick = false;
            showFolderPicker();
            return;
        }
        if (games.isEmpty()) scan();
    }

    private void scan() {
        final String gd = prefs.getString("gamedir", null);
        if (gd != null) {
            scanFiles(new File(gd));
            return;
        }
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
        // vai direto para a tela do logo girando (DraStic), sem tela intermediaria
        i.setComponent(new ComponentName(this, "com.dsemu.drastic.DraSticActivity"));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
        overridePendingTransition(0, 0);
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

    private int txtShadowColor() {
        return prefs.getInt("txt_shadow_color", DEF_TXT_SHADOW_COLOR);
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
        tv.setTextStyle(txtSize(), txtColor(), txtOutline(), txtOutlineColor(), txtShadow(),
                txtShadowColor());
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
                              int[] color, int[] ocolor, int[] bg, int[] scol) {
        tv.setTextStyle(size[0], color[0], outline[0], ocolor[0], shadow[0], scol[0]);
        if (Color.alpha(bg[0]) == 0) {
            tv.setBackgroundDrawable(null);
        } else {
            tv.setBackgroundDrawable(textPill(bg[0]));
        }
    }

    /** Salva as opcoes atuais e atualiza os rotulos da grade. */
    private void persistText(int[] size, int[] outline, boolean[] shadow,
                             int[] color, int[] ocolor, int[] bg, int[] scol) {
        prefs.edit()
                .putInt("txt_size", size[0])
                .putInt("txt_outline", outline[0])
                .putBoolean("txt_shadow", shadow[0])
                .putInt("txt_color", color[0])
                .putInt("txt_outline_color", ocolor[0])
                .putInt("txt_bg", bg[0])
                .putInt("txt_shadow_color", scol[0])
                .apply();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private TextView menuLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF55555C);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(bebas());
        return t;
    }

    private TextView menuValue() {
        TextView t = new TextView(this);
        t.setTextColor(0xFF3D8BFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(bebas());
        return t;
    }

    private TextView pillButton(String s, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(bebas());
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
        title.setTypeface(bebas());
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("X");
        x.setTextColor(0xFF666666);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setTypeface(bebas());
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
        final int[] scol = new int[]{txtShadowColor()};

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
                stylePreview(preview, size, outline, shadow, color, ocolor, bg, scol);
                persistText(size, outline, shadow, color, ocolor, bg, scol);
            }
        };

        final SwatchRow colorRow = new SwatchRow(color, onChange);
        final SwatchRow ocolorRow = new SwatchRow(ocolor, onChange);
        final SwatchRow bgRow = new SwatchRow(bg, onChange);
        final SwatchRow scolRow = new SwatchRow(scol, onChange);
        for (int i = 0; i < TXT_COLORS.length; i++) {
            colorRow.add(TXT_COLORS[i]);
            ocolorRow.add(TXT_COLORS[i]);
        }
        for (int i = 0; i < SHADOW_COLORS.length; i++) {
            scolRow.add(SHADOW_COLORS[i]);
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

        // cor da sombra
        TextView lblScol = menuLabel("Cor da sombra");
        lblScol.setPadding(0, dp(14), 0, dp(6));
        content.addView(lblScol, new LinearLayout.LayoutParams(-1, -2));
        content.addView(scolRow.build(), cwlp(0));

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
        hintBg.setTypeface(bebas());
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
                scol[0] = DEF_TXT_SHADOW_COLOR;
                sbSize.setProgress(DEF_TXT_SIZE - 8);
                sbOutline.setProgress(DEF_TXT_OUTLINE);
                swShadow.setChecked(false);
                sizeVal.setText(size[0] + " sp");
                outlineVal.setText(outline[0] + " dp");
                colorRow.refresh();
                ocolorRow.refresh();
                bgRow.refresh();
                scolRow.refresh();
                onChange.run();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
            }
        });

        // estado inicial da previa e dos aneis de selecao
        stylePreview(preview, size, outline, shadow, color, ocolor, bg, scol);
        colorRow.refresh();
        ocolorRow.refresh();
        bgRow.refresh();
        scolRow.refresh();

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

    /** TextView que desenha sombra e contorno no texto (nomes dos jogos e previa).
     *  Cada passada usa a propria cor: sombra, contorno e letra sao independentes. */
    private class StyledTextView extends TextView {
        private int outlineDp;
        private int outlineColor;
        private boolean shadowOn;
        private int shadowColor;

        StyledTextView(Context c) {
            super(c);
            setTypeface(bebas());
        }

        void setTextStyle(int sizeSp, int color, int outline, int ocolor, boolean shadow,
                          int scol) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            setTextColor(color);
            outlineDp = outline;
            outlineColor = ocolor;
            shadowOn = shadow;
            shadowColor = scol;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int fillColor = getCurrentTextColor();
            if (outlineDp <= 0 && !shadowOn) {
                getPaint().setColor(fillColor);
                super.onDraw(cv);
                return;
            }
            TextPaint tp = getPaint();
            Paint.Style keepStyle = tp.getStyle();
            float keepW = tp.getStrokeWidth();
            float dens = getResources().getDisplayMetrics().density;
            // 1) sombra: passada separada, com a cor da sombra e deslocamento fixo
            if (shadowOn) {
                tp.setStyle(Paint.Style.FILL);
                tp.setStrokeWidth(0f);
                tp.setColor(shadowColor);
                cv.save();
                cv.translate(2f * dens, 3f * dens);
                super.onDraw(cv);
                cv.restore();
            }
            // 2) contorno: passada STROKE com a cor do contorno (nao a cor da letra)
            if (outlineDp > 0) {
                tp.setStyle(Paint.Style.STROKE);
                tp.setStrokeWidth(outlineDp * 2f * dens); // 2x: metade fica embaixo do preenchimento
                tp.setColor(outlineColor);
                super.onDraw(cv);
            }
            // 3) letra: preenchimento com a cor da letra
            tp.setStyle(keepStyle);
            tp.setStrokeWidth(keepW);
            tp.setColor(fillColor);
            super.onDraw(cv);
        }
    }

    // ---------- shaders (botao azul) ----------

    private LinearLayout shaderListBox;
    private TextView shaderValActive;

    private Uri treeUri() {
        String t = prefs.getString("tree", null);
        return t == null ? null : Uri.parse(t);
    }

    private static float sliderVal(int p, float lo, float hi) {
        return lo + (hi - lo) * p / 100f;
    }

    private static int sliderPos(float v, float lo, float hi) {
        int p = Math.round((v - lo) * 100f / (hi - lo));
        return Math.max(0, Math.min(100, p));
    }

    private String pct(float v) {
        return Math.round(v * 100f) + "%";
    }

    /** Reaplica brilho/contraste/cor/gamma na shader ativa (fora da thread principal). */
    private void regenColor() {
        final Uri tree = treeUri();
        if (tree == null) return;
        final float b = prefs.getFloat("sh_bright", 1f);
        final float c = prefs.getFloat("sh_contrast", 1f);
        final float s = prefs.getFloat("sh_sat", 1f);
        final float g = prefs.getFloat("sh_gamma", 1f);
        new Thread(new Runnable() {
            public void run() {
                final String err = ShaderTools.applyColorSettings(HomeActivity.this, tree, b, c, s, g);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (err != null) {
                            Toast.makeText(HomeActivity.this, err, Toast.LENGTH_SHORT).show();
                        }
                        fillShaderList();
                    }
                });
            }
        }).start();
    }

    private void fillShaderList() {
        if (shaderListBox == null) return;
        shaderListBox.removeAllViews();
        String cur = ShaderTools.currentShader(this);
        HashSet<String> seen = new HashSet<String>();
        String[] builtins = {"Linear", "None"};
        for (String b : builtins) {
            seen.add(b);
            shaderListBox.addView(shaderRow(b, b.equals(cur)));
        }
        List<String> inst = ShaderTools.installedShaders(this, treeUri());
        for (String n : inst) {
            if (seen.contains(n)) continue;
            seen.add(n);
            shaderListBox.addView(shaderRow(n, n.equals(cur)));
        }
        if (shaderValActive != null) shaderValActive.setText(cur);
    }

    private View shaderRow(final String name, final boolean active) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTypeface(bebas());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(active ? 0xFF3D8BFF : 0xFF333338);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(active ? 0x1A3D8BFF : 0x00000000);
        bgd.setCornerRadius(dp(10));
        bgd.setStroke(active ? dp(2) : 0, active ? 0xFF3D8BFF : 0);
        t.setBackgroundDrawable(bgd);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // so troca a shader; nao mexe nos ajustes de imagem (filtro)
                ShaderTools.setCurrentShader(HomeActivity.this, name);
                fillShaderList();
            }
        });
        return t;
    }

    /** Dialogo para escolher a shader ativa entre as instaladas. */
    private void showShaderPicker() {
        final List<String> names = new ArrayList<String>();
        names.add("Linear");
        names.add("None");
        for (String n : ShaderTools.installedShaders(this, treeUri())) {
            if (!names.contains(n)) names.add(n);
        }
        String cur = ShaderTools.currentShader(this);
        int idx = names.indexOf(cur);
        final String[] arr = names.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Selecionar shader")
                .setSingleChoiceItems(arr, idx, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        // so troca a shader; nao mexe nos ajustes de imagem (filtro)
                        ShaderTools.setCurrentShader(HomeActivity.this, arr[which]);
                        fillShaderList();
                        Toast.makeText(HomeActivity.this, "Shader: " + arr[which],
                                Toast.LENGTH_SHORT).show();
                        d.dismiss();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String docName(Uri doc) {
        Cursor c = null;
        try {
            c = getContentResolver().query(doc, new String[]{
                    android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private void openImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMPORT);
    }

    /** Abre o menu de shaders sobre a tela inicial. */
    private void showShaderMenu() {
        final Uri tree = treeUri();
        if (tree == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Pasta dos shaders")
                    .setMessage("Os shaders precisam de uma pasta de dados. Escolher agora?")
                    .setPositiveButton("Escolher", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            pickShaderFolder();
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x66000000);
        overlay.setClickable(true);
        final Runnable closer = new Runnable() {
            public void run() {
                shaderListBox = null;
                shaderValActive = null;
                ShaderTools.detachUi();
                rootFrame.removeView(overlay);
            }
        };
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFFF7F7F9);
        pbg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        panel.setBackgroundDrawable(pbg);

        // cabecalho
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(10), dp(4));
        TextView title = new TextView(this);
        title.setText("Shaders");
        title.setTextColor(0xFF222222);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(bebas());
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("X");
        x.setTextColor(0xFF666666);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setTypeface(bebas());
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
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // shader ativa
        LinearLayout rowActive = new LinearLayout(this);
        rowActive.setOrientation(LinearLayout.HORIZONTAL);
        rowActive.setGravity(Gravity.CENTER_VERTICAL);
        rowActive.setPadding(dp(16), dp(6), dp(16), 0);
        TextView lblActive = menuLabel("Shader ativa:");
        lblActive.setPadding(0, 0, dp(8), 0);
        shaderValActive = menuValue();
        shaderValActive.setText(ShaderTools.currentShader(this));
        rowActive.addView(lblActive, new LinearLayout.LayoutParams(-2, -2));
        rowActive.addView(shaderValActive, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(rowActive, new LinearLayout.LayoutParams(-1, -2));
        TextView hint1 = new TextView(this);
        hint1.setText("Vale a partir do proximo jogo aberto. Escolher a shader nao altera os ajustes de imagem.");
        hint1.setTextColor(0xFF9A9AA2);
        hint1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint1.setTypeface(bebas());
        hint1.setPadding(dp(16), dp(2), dp(16), 0);
        panel.addView(hint1, new LinearLayout.LayoutParams(-1, -2));

        // lista de shaders instaladas
        shaderListBox = new LinearLayout(this);
        shaderListBox.setOrientation(LinearLayout.VERTICAL);
        shaderListBox.setPadding(dp(10), dp(6), dp(10), dp(6));
        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.addView(shaderListBox, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        fillShaderList();

        // importar / baixar
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(dp(16), dp(4), dp(16), 0);
        TextView imp = pillButton("Importar shader", 0xFF1565C0, 0xFFFFFFFF);
        final TextView dl = pillButton("Baixar todas", 0xFF2E9E3E, 0xFFFFFFFF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
        blp.rightMargin = dp(8);
        btns.addView(imp, blp);
        btns.addView(dl, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sel = pillButton("Selecionar shader", 0xFF3D8BFF, 0xFFFFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(dp(16), dp(4), dp(16), dp(8));
        panel.addView(sel, slp);
        sel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showShaderPicker();
            }
        });
        panel.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout dlBox = new LinearLayout(this);
        dlBox.setOrientation(LinearLayout.VERTICAL);
        dlBox.setPadding(dp(16), dp(8), dp(16), 0);
        dlBox.setVisibility(View.GONE);
        final TextView dlLabel = new TextView(this);
        dlLabel.setTextColor(0xFF333338);
        dlLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dlLabel.setTypeface(bebas());
        dlBox.addView(dlLabel, new LinearLayout.LayoutParams(-1, -2));
        final android.widget.ProgressBar dlBar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        dlBar.setMax(100);
        dlBox.addView(dlBar, new LinearLayout.LayoutParams(-1, dp(14)));
        panel.addView(dlBox, new LinearLayout.LayoutParams(-1, -2));
        TextView hintDl = new TextView(this);
        hintDl.setText("Importar: arquivos .dfx, .slang, .slangp ou .glsl. Baixar: puxa e converte as shaders do repositorio slang-shaders (libretro). Ajuste abaixo funciona junto com qualquer shader.");
        hintDl.setTextColor(0xFF9A9AA2);
        hintDl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hintDl.setTypeface(bebas());
        hintDl.setPadding(dp(16), dp(4), dp(16), 0);
        panel.addView(hintDl, new LinearLayout.LayoutParams(-1, -2));

        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openImport();
            }
        });
        final ShaderTools.Progress uiP = new ShaderTools.Progress() {
            public void update(String label, int percent) {
                dlBox.setVisibility(View.VISIBLE);
                dl.setEnabled(false);
                dl.setAlpha(0.5f);
                dlLabel.setText(label);
                if (percent < 0) {
                    dlBar.setIndeterminate(true);
                } else {
                    dlBar.setIndeterminate(false);
                    dlBar.setProgress(percent);
                }
            }
        };
        final ShaderTools.Result uiR = new ShaderTools.Result() {
            public void done(String summary) {
                dl.setEnabled(true);
                dl.setAlpha(1f);
                dlBox.setVisibility(View.GONE);
                ShaderTools.dlPendingSummary = null;
                Toast.makeText(HomeActivity.this, summary, Toast.LENGTH_LONG).show();
                fillShaderList();
            }
        };
        // reabriu o menu com download em andamento (ou terminado enquanto estava fora)
        ShaderTools.attachUi(uiP, uiR);
        if (ShaderTools.dlRunning) {
            uiP.update(ShaderTools.dlLabel, ShaderTools.dlPercent);
        } else if (ShaderTools.dlPendingSummary != null) {
            uiR.done(ShaderTools.dlPendingSummary);
        }
        dl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ShaderTools.dlRunning) return;
                chooseAndStartShaderDownload(dl, tree, uiP);
            }
        });

        // ajustes de imagem (brilho, contraste, cor, gamma)
        TextView lblAdj = menuLabel("Ajustes de imagem");
        lblAdj.setPadding(dp(16), dp(12), 0, 0);
        panel.addView(lblAdj, new LinearLayout.LayoutParams(-1, -2));

        final float loBC = 0.25f, hiBC = 3.0f, hiSat = 2.0f;
        final float[] b = new float[]{prefs.getFloat("sh_bright", 1f)};
        final float[] c = new float[]{prefs.getFloat("sh_contrast", 1f)};
        final float[] s = new float[]{prefs.getFloat("sh_sat", 1f)};
        final float[] g = new float[]{prefs.getFloat("sh_gamma", 1f)};

        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        rowB.setGravity(Gravity.CENTER_VERTICAL);
        rowB.setPadding(dp(16), dp(8), dp(16), 0);
        rowB.addView(menuLabel("Brilho"), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView valB = menuValue();
        valB.setText(pct(b[0]));
        rowB.addView(valB, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(rowB, new LinearLayout.LayoutParams(-1, -2));
        final SeekBar sbB = new SeekBar(this);
        sbB.setMax(100);
        sbB.setProgress(sliderPos(b[0], loBC, hiBC));
        sbB.setPadding(dp(12), 0, dp(12), 0);
        panel.addView(sbB, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout rowC = new LinearLayout(this);
        rowC.setOrientation(LinearLayout.HORIZONTAL);
        rowC.setGravity(Gravity.CENTER_VERTICAL);
        rowC.setPadding(dp(16), dp(6), dp(16), 0);
        rowC.addView(menuLabel("Contraste"), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView valC = menuValue();
        valC.setText(pct(c[0]));
        rowC.addView(valC, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(rowC, new LinearLayout.LayoutParams(-1, -2));
        final SeekBar sbC = new SeekBar(this);
        sbC.setMax(100);
        sbC.setProgress(sliderPos(c[0], loBC, hiBC));
        sbC.setPadding(dp(12), 0, dp(12), 0);
        panel.addView(sbC, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout rowS = new LinearLayout(this);
        rowS.setOrientation(LinearLayout.HORIZONTAL);
        rowS.setGravity(Gravity.CENTER_VERTICAL);
        rowS.setPadding(dp(16), dp(6), dp(16), 0);
        rowS.addView(menuLabel("Cor (saturacao)"), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView valS = menuValue();
        valS.setText(pct(s[0]));
        rowS.addView(valS, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(rowS, new LinearLayout.LayoutParams(-1, -2));
        final SeekBar sbS = new SeekBar(this);
        sbS.setMax(100);
        sbS.setProgress(sliderPos(s[0], 0f, hiSat));
        sbS.setPadding(dp(12), 0, dp(12), 0);
        panel.addView(sbS, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout rowG = new LinearLayout(this);
        rowG.setOrientation(LinearLayout.HORIZONTAL);
        rowG.setGravity(Gravity.CENTER_VERTICAL);
        rowG.setPadding(dp(16), dp(6), dp(16), 0);
        rowG.addView(menuLabel("Gamma"), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView valG = menuValue();
        valG.setText(String.format(java.util.Locale.US, "%.2f", g[0]));
        rowG.addView(valG, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(rowG, new LinearLayout.LayoutParams(-1, -2));
        final SeekBar sbG = new SeekBar(this);
        sbG.setMax(100);
        sbG.setProgress(sliderPos(g[0], loBC, hiBC));
        sbG.setPadding(dp(12), 0, dp(12), 0);
        panel.addView(sbG, new LinearLayout.LayoutParams(-1, -2));

        sbB.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                b[0] = sliderVal(p, loBC, hiBC);
                valB.setText(pct(b[0]));
                prefs.edit().putFloat("sh_bright", b[0]).apply();
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
                regenColor();
            }
        });
        sbC.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                c[0] = sliderVal(p, loBC, hiBC);
                valC.setText(pct(c[0]));
                prefs.edit().putFloat("sh_contrast", c[0]).apply();
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
                regenColor();
            }
        });
        sbS.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                s[0] = sliderVal(p, 0f, hiSat);
                valS.setText(pct(s[0]));
                prefs.edit().putFloat("sh_sat", s[0]).apply();
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
                regenColor();
            }
        });
        sbG.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                g[0] = sliderVal(p, loBC, hiBC);
                valG.setText(String.format(java.util.Locale.US, "%.2f", g[0]));
                prefs.edit().putFloat("sh_gamma", g[0]).apply();
            }

            public void onStartTrackingTouch(SeekBar sb) {
            }

            public void onStopTrackingTouch(SeekBar sb) {
                regenColor();
            }
        });

        // rodape
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(dp(16), dp(10), dp(16), dp(16));
        TextView rst = pillButton("Cor padrao", 0xFFE8E8EC, 0xFF333338);
        TextView closeBtn = pillButton("Fechar", 0xFF3D8BFF, 0xFFFFFFFF);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, -2, 1f);
        flp.rightMargin = dp(8);
        foot.addView(rst, flp);
        foot.addView(closeBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        rst.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                b[0] = 1f;
                c[0] = 1f;
                s[0] = 1f;
                g[0] = 1f;
                sbB.setProgress(sliderPos(1f, loBC, hiBC));
                sbC.setProgress(sliderPos(1f, loBC, hiBC));
                sbS.setProgress(sliderPos(1f, 0f, hiSat));
                sbG.setProgress(sliderPos(1f, loBC, hiBC));
                valB.setText(pct(1f));
                valC.setText(pct(1f));
                valS.setText(pct(1f));
                valG.setText(String.format(java.util.Locale.US, "%.2f", 1f));
                prefs.edit().putFloat("sh_bright", 1f).putFloat("sh_contrast", 1f)
                        .putFloat("sh_sat", 1f).putFloat("sh_gamma", 1f).apply();
                regenColor();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
            }
        });

        overlay.addView(panel, new FrameLayout.LayoutParams(-1,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.86f), Gravity.BOTTOM));
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    // ---------- video (botao verde) ----------

    /** Prefs nativas do DraStic: o emulador le esse arquivo ao abrir cada jogo. */
    static final String DRA_PREFS = "_Dra$t1c_Pref$_";

    private static final String[] VID_FS_LABELS = {"Nenhum", "Manual", "Auto"};
    private static final String[] VID_LAT_LABELS = {"Baixa", "Media", "Alta", "Muito alta"};
    private static final String[] VID_LAYOUT_LABELS = {
            "Paisagem 1:1", "Paisagem x:1", "Retrato 1:1", "Paisagem proporcao", "Paisagem tela cheia",
            "Personalizado"};
    private static final String[] VID_ROT_LABELS = {"Auto", "Paisagem", "Paisagem invertida"};
    private static final String[] VID_EXT_LABELS = {"Clonar dispositivo", "Proporcao correta", "Tela cheia"};
    private static final String[] VID_EXTSCR_LABELS = {"Tela de cima", "Tela de baixo"};

    private SharedPreferences draPrefs() {
        return getSharedPreferences(DRA_PREFS, MODE_PRIVATE);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private TextView vidSection(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF2E9E3E);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(bebas());
        t.setPadding(0, dp(18), 0, dp(2));
        return t;
    }

    private TextView vidHint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF9A9AA2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(bebas());
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    /** Linha "rotulo ... valor" (o mesmo formato do menu de shaders). */
    private LinearLayout vidRow(String label, TextView val) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(menuLabel(label), new LinearLayout.LayoutParams(0, -2, 1f));
        if (val != null) row.addView(val, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** Linha "rotulo ... switch". */
    private LinearLayout vidSwitch(String label, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(menuLabel(label), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private Switch vidSwitchNew(final boolean[] state, final Runnable onChange) {
        Switch sw = new Switch(this);
        sw.setTextColor(0xFF222222);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setChecked(state[0]);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                state[0] = checked;
                onChange.run();
            }
        });
        return sw;
    }

    /** Linha de opcao em ciclo: tocar na linha avanca para o proximo valor. */
    private LinearLayout vidCycle(String label, final String[] labels, final int[] values,
                                  final int[] cur, final List<Runnable> refreshers,
                                  final Runnable onChange) {
        final TextView val = menuValue();
        final Runnable refresh = new Runnable() {
            public void run() {
                String name = labels[0];
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == cur[0]) {
                        name = labels[i];
                        break;
                    }
                }
                val.setText(name);
            }
        };
        refresh.run();
        refreshers.add(refresh);
        LinearLayout row = vidRow(label, val);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int idx = 0;
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == cur[0]) {
                        idx = i;
                        break;
                    }
                }
                cur[0] = values[(idx + 1) % values.length];
                refresh.run();
                onChange.run();
            }
        });
        return row;
    }

    /** Abre o menu de opcoes de video sobre a tela inicial.
     *  Escreve direto nas preferencias nativas (_Dra$t1c_Pref$_); o emulador
     *  le tudo de novo ao abrir o proximo jogo. */
    private void showVideoMenu() {
        final SharedPreferences p = draPrefs();

        // texturas em baixa resolucao: o proprio app decide sozinho em telas pequenas
        final boolean autoLow = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) <= 800;

        final int[] fsType = new int[]{clamp(p.getInt("_FrameskipType", 0), 0, 2)};
        final int[] fsVal = new int[]{clamp(p.getInt("_FrameskipValue", 4), 0, 9)};
        final boolean[] fsSafe = new boolean[]{p.getBoolean("_FrameskipSafe", false)};
        final boolean[] threaded = new boolean[]{p.getBoolean("_Threaded3D", false)};
        final int[] cpuLoad = new int[]{clamp(p.getInt("_CpuLoad", 0), 0, 100)};
        final int[] latency = new int[]{clamp(p.getInt("_AudioLatency", 3), 0, 3)};
        final boolean[] hires = new boolean[]{p.getBoolean("_Hires3D", false)};
        final boolean[] bit16 = new boolean[]{p.getBoolean("_GlUse16Bit", false)};
        final boolean[] lowres = new boolean[]{p.getBoolean("_UseLowResTextures", autoLow)};
        final boolean[] noEdge = new boolean[]{p.getBoolean("_DisableEdgeMarking", false)};
        final boolean[] glSleep = new boolean[]{p.getBoolean("_GlThreadSleep", true)};
        final boolean[] fixMain = new boolean[]{p.getBoolean("_FixMainEngineScreen", false)};
        final boolean[] showFps = new boolean[]{p.getBoolean("_ShowFPS", false)};
        final boolean[] fpsTrans = new boolean[]{p.getBoolean("_FpsTransparent", true)};
        final int[] layout = new int[]{clamp(p.getInt("_DefaultLayout", 0), 0, 5)};
        final int[] rot = new int[]{clamp(p.getInt("_RotationMode", 0), 0, 2)};
        final int[] extType = new int[]{clamp(p.getInt("_ExtDisplayType", 0), 0, 2)};
        final int[] extScr = new int[]{clamp(p.getInt("_ExtDisplayScreen", 0), 0, 1)};

        // salva tudo que esta na tela (sync = true grava na hora)
        final Runnable persistLater = new Runnable() {
            public void run() {
                persistVideo(p, fsType[0], fsVal[0], fsSafe[0], threaded[0], cpuLoad[0],
                        latency[0], hires[0], bit16[0], lowres[0], noEdge[0], glSleep[0],
                        fixMain[0], showFps[0], fpsTrans[0], layout[0], rot[0], extType[0],
                        extScr[0], false);
            }
        };
        final Runnable persistSync = new Runnable() {
            public void run() {
                persistVideo(p, fsType[0], fsVal[0], fsSafe[0], threaded[0], cpuLoad[0],
                        latency[0], hires[0], bit16[0], lowres[0], noEdge[0], glSleep[0],
                        fixMain[0], showFps[0], fpsTrans[0], layout[0], rot[0], extType[0],
                        extScr[0], true);
            }
        };

        // switches criadas uma unica vez; guardadas para o botao Padrao
        swFsSafe = vidSwitchNew(fsSafe, persistLater);
        swThreaded = vidSwitchNew(threaded, persistLater);
        swHires = vidSwitchNew(hires, persistLater);
        swBit16 = vidSwitchNew(bit16, persistLater);
        swLowres = vidSwitchNew(lowres, persistLater);
        swEdge = vidSwitchNew(noEdge, persistLater);
        swSleep = vidSwitchNew(glSleep, persistLater);
        swFix = vidSwitchNew(fixMain, persistLater);
        swFps = vidSwitchNew(showFps, persistLater);
        swFpsT = vidSwitchNew(fpsTrans, persistLater);

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
        title.setText("Opcoes de video");
        title.setTextColor(0xFF222222);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(bebas());
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("X");
        x.setTextColor(0xFF666666);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setTypeface(bebas());
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

        TextView hint0 = vidHint("Vale a partir do proximo jogo aberto. Toque numa opcao de valor "
                + "para mudar. Sao as mesmas opcoes do menu interno do emulador.");
        hint0.setPadding(0, dp(6), 0, 0);
        content.addView(hint0, new LinearLayout.LayoutParams(-1, -2));

        final List<Runnable> refreshers = new ArrayList<Runnable>();

        // ===== desempenho =====
        content.addView(vidSection("Desempenho"), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidCycle("Pular quadros", VID_FS_LABELS, new int[]{0, 1, 2}, fsType,
                refreshers, persistLater), new LinearLayout.LayoutParams(-1, -2));

        final TextView valFs = menuValue();
        valFs.setText(String.valueOf(fsVal[0]));
        content.addView(vidRow("Valor de quadros pulados", valFs), new LinearLayout.LayoutParams(-1, -2));
        final SeekBar sbFs = new SeekBar(this);
        sbFs.setMax(9);
        sbFs.setProgress(fsVal[0]);
        sbFs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean fromUser) {
                fsVal[0] = pr;
                valFs.setText(String.valueOf(pr));
                persistLater.run();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                persistSync.run();
            }
        });
        content.addView(sbFs, new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidSwitch("Pular quadros seguro", swFsSafe),
                new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidSwitch("Renderizacao 3D com varias threads", swThreaded),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Melhora o desempenho em jogos com muito 3D, mas pode causar "
                + "falhas graficas (como telas trocadas) em alguns jogos. Se notar falhas "
                + "na imagem, desative esta opcao."), new LinearLayout.LayoutParams(-1, -2));

        final TextView valCpu = menuValue();
        valCpu.setText(cpuLoad[0] + "%");
        content.addView(vidRow("Estabilizador de FPS (carga de CPU)", valCpu),
                new LinearLayout.LayoutParams(-1, -2));
        final SeekBar sbCpu = new SeekBar(this);
        sbCpu.setMax(100);
        sbCpu.setProgress(cpuLoad[0]);
        sbCpu.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean fromUser) {
                cpuLoad[0] = pr;
                valCpu.setText(pr + "%");
                persistLater.run();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                persistSync.run();
            }
        });
        content.addView(sbCpu, new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("0 = desligado. Usa CPU extra para manter a taxa de quadros "
                + "estavel; comece com valores baixos."), new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidCycle("Latencia de audio", VID_LAT_LABELS, new int[]{0, 1, 2, 3},
                latency, refreshers, persistLater), new LinearLayout.LayoutParams(-1, -2));

        // ===== graficos =====
        content.addView(vidSection("Graficos"), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidSwitch("Renderizacao 3D em alta resolucao", swHires),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Renderiza o 3D em 512x384 em vez de 256x192; imagem mais "
                + "nitida, usa mais processamento."), new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidSwitch("Renderizacao em 16 bits", swBit16),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Ganha desempenho em aparelhos fracos; as cores ficam menos "
                + "precisas."), new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidSwitch("Usar texturas em baixa resolucao", swLowres),
                new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidSwitch("Desativar marcacao de bordas", swEdge),
                new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidSwitch("Pausa na thread de renderizacao", swSleep),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Economiza bateria e esquentamento; pode derrubar um pouco o "
                + "desempenho."), new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidSwitch("Manter a tela principal na tela de cima", swFix),
                new LinearLayout.LayoutParams(-1, -2));

        // ===== tela =====
        content.addView(vidSection("Tela"), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidSwitch("Mostrar FPS", swFps),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidSwitch("FPS transparente", swFpsT),
                new LinearLayout.LayoutParams(-1, -2));

        content.addView(vidCycle("Layout padrao", VID_LAYOUT_LABELS, new int[]{0, 1, 2, 3, 4, 5},
                layout, refreshers, persistLater), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidCycle("Rotacao", VID_ROT_LABELS, new int[]{0, 1, 2},
                rot, refreshers, persistLater), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidCycle("Tela externa", VID_EXT_LABELS, new int[]{0, 1, 2},
                extType, refreshers, persistLater), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidCycle("Tela da tela externa", VID_EXTSCR_LABELS, new int[]{0, 1},
                extScr, refreshers, persistLater), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Tela externa: para jogar numa TV ou monitor via Cast/HDMI."),
                new LinearLayout.LayoutParams(-1, -2));

        // ===== telas e controles =====
        content.addView(vidSection("Telas e controles"), new LinearLayout.LayoutParams(-1, -2));

        TextView btnEdScr = pillButton("Editar posicao das telas", 0xFF3D8BFF, 0xFFFFFFFF);
        LinearLayout.LayoutParams esp = new LinearLayout.LayoutParams(-1, -2);
        esp.topMargin = dp(10);
        content.addView(btnEdScr, esp);
        btnEdScr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLayoutEditor(0);
            }
        });

        TextView btnEdPad = pillButton("Editar controles na tela", 0xFF3D8BFF, 0xFFFFFFFF);
        LinearLayout.LayoutParams epp = new LinearLayout.LayoutParams(-1, -2);
        epp.topMargin = dp(8);
        content.addView(btnEdPad, epp);
        btnEdPad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLayoutEditor(1);
            }
        });
        content.addView(vidHint("Arraste para mover; arraste o quadradinho do canto para "
                + "mudar o tamanho. Salvar cria o layout \"Personalizado\" e seleciona ele "
                + "na hora de abrir um jogo."), new LinearLayout.LayoutParams(-1, -2));

        final String[] sizeLabels = {"Pequena", "Media", "Grande"};
        final float[] sizeVals = {0.70f, 0.85f, 1.0f};
        final int[] sizeCur = new int[]{clamp(p.getInt("alphads_scrsize", 2), 0, 2)};
        content.addView(vidCycle("Tamanho das telas", sizeLabels, new int[]{0, 1, 2},
                sizeCur, refreshers, new Runnable() {
                    public void run() {
                        p.edit().putInt("alphads_scrsize", sizeCur[0]).commit();
                        applyScreenScale(sizeVals[sizeCur[0]]);
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Tamanho das telas no layout Personalizado (recalcula a "
                + "posicao; os controles ficam como estao)."), new LinearLayout.LayoutParams(-1, -2));

        final boolean[] haptic = new boolean[]{p.getBoolean("_HapticFeedback", true)};
        final boolean[] noDiag = new boolean[]{p.getBoolean("_NoDiagonals", false)};
        content.addView(vidSwitch("Vibracao ao tocar", vidSwitchNew(haptic, new Runnable() {
            public void run() {
                p.edit().putBoolean("_HapticFeedback", haptic[0]).commit();
            }
        })), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidSwitch("Nao usar diagonais no D-Pad", vidSwitchNew(noDiag, new Runnable() {
            public void run() {
                p.edit().putBoolean("_NoDiagonals", noDiag[0]).commit();
            }
        })), new LinearLayout.LayoutParams(-1, -2));

        final float defAlpha = 0.45f;
        final float padAlpha = p.getFloat("_ControllerAlpha", defAlpha);
        final TextView valAlpha = menuValue();
        valAlpha.setText(Math.round(padAlpha * 100f) + "%");
        content.addView(vidRow("Transparencia dos controles", valAlpha),
                new LinearLayout.LayoutParams(-1, -2));
        SeekBar sbAlpha = new SeekBar(this);
        sbAlpha.setMax(100);
        sbAlpha.setProgress(Math.round(padAlpha * 100f));
        sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean fromUser) {
                valAlpha.setText(pr + "%");
                p.edit().putFloat("_ControllerAlpha", pr / 100f).apply();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                p.edit().putFloat("_ControllerAlpha", s.getProgress() / 100f).commit();
            }
        });
        content.addView(sbAlpha, new LinearLayout.LayoutParams(-1, -2));

        final float defDead = 0.3f;
        final float dead = p.getFloat("_AnalogDeadzone", defDead);
        final TextView valDead = menuValue();
        valDead.setText(Math.round(dead * 100f) + "%");
        content.addView(vidRow("Zona morta do analogico", valDead),
                new LinearLayout.LayoutParams(-1, -2));
        SeekBar sbDead = new SeekBar(this);
        sbDead.setMax(100);
        sbDead.setProgress(Math.round(dead * 100f));
        sbDead.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean fromUser) {
                valDead.setText(pr + "%");
                p.edit().putFloat("_AnalogDeadzone", pr / 100f).apply();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                p.edit().putFloat("_AnalogDeadzone", s.getProgress() / 100f).commit();
            }
        });
        content.addView(sbDead, new LinearLayout.LayoutParams(-1, -2));

        // rodape
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = pillButton("Padrao", 0xFFE8E8EC, 0xFF333338);
        TextView closeBtn = pillButton("Fechar", 0xFF2E9E3E, 0xFFFFFFFF);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.rightMargin = dp(8);
        btns.addView(reset, rlp);
        btns.addView(closeBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(btns, cwlp(18));

        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                fsType[0] = 0;
                fsVal[0] = 4;
                fsSafe[0] = false;
                threaded[0] = false;
                cpuLoad[0] = 0;
                latency[0] = 3;
                hires[0] = false;
                bit16[0] = false;
                lowres[0] = autoLow;
                noEdge[0] = false;
                glSleep[0] = true;
                fixMain[0] = false;
                showFps[0] = false;
                fpsTrans[0] = true;
                layout[0] = 0;
                rot[0] = 0;
                extType[0] = 0;
                extScr[0] = 0;
                persistVideo(p, fsType[0], fsVal[0], fsSafe[0], threaded[0], cpuLoad[0],
                        latency[0], hires[0], bit16[0], lowres[0], noEdge[0], glSleep[0],
                        fixMain[0], showFps[0], fpsTrans[0], layout[0], rot[0], extType[0],
                        extScr[0], true);
                sbFs.setProgress(4);
                sbCpu.setProgress(0);
                valFs.setText("4");
                valCpu.setText("0%");
                swFsSafe.setChecked(false);
                swThreaded.setChecked(false);
                swHires.setChecked(false);
                swBit16.setChecked(false);
                swLowres.setChecked(autoLow);
                swEdge.setChecked(false);
                swSleep.setChecked(true);
                swFix.setChecked(false);
                swFps.setChecked(false);
                swFpsT.setChecked(true);
                for (int i = 0; i < refreshers.size(); i++) {
                    refreshers.get(i).run();
                }
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
            }
        });

        overlay.addView(panel, new FrameLayout.LayoutParams(-1,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.86f), Gravity.BOTTOM));
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private Switch swFsSafe, swThreaded, swHires, swBit16, swLowres, swEdge, swSleep, swFix,
            swFps, swFpsT;

    // ---------- configuracoes gerais (botao engrenagem) ----------

    private static final String[] SET_LANG_LABELS = {
            "Japones", "Ingles", "Frances", "Alemao", "Italiano", "Espanhol"};
    private static final String[] SET_MONTH_LABELS = {
            "Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez"};
    private static final String[] SET_ONOFF = {"Desligado", "Ligado"};
    private static final String[] SET_FFWD_LABELS = {"2x", "3x", "4x"};
    private static final String[] SET_STICK_LABELS = {"Automatico", "Esquerdo", "Direito"};

    /** Switch que avisa quando o botao "Padrao" precisa voltar o valor inicial. */
    private Switch setSwitch(final boolean[] st, final int def, final List<Runnable> resets,
                             final Runnable onChange) {
        final Switch sw = vidSwitchNew(st, onChange);
        resets.add(new Runnable() {
            public void run() {
                st[0] = def != 0;
                sw.setChecked(def != 0);
            }
        });
        return sw;
    }

    /** Linha com rotulo + switch ja ligada ao reset. */
    private LinearLayout setSwitchRow(String label, boolean[] st, int def,
                                      List<Runnable> resets, Runnable onChange) {
        return vidSwitch(label, setSwitch(st, def, resets, onChange));
    }

    /** Slider (0..max) gravando em st[0]; adiciona o reset automaticamente. */
    private LinearLayout setSeekRow(String label, final int[] st, final int def, int max,
                                    final List<Runnable> resets, final Runnable onChange) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView val = menuValue();
        val.setText(String.valueOf(st[0]));
        box.addView(vidRow(label, val), new LinearLayout.LayoutParams(-1, -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(st[0]);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean fromUser) {
                st[0] = pr;
                val.setText(String.valueOf(pr));
                onChange.run();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        resets.add(new Runnable() {
            public void run() {
                st[0] = def;
                sb.setProgress(def);
                val.setText(String.valueOf(def));
            }
        });
        return box;
    }

    /** Linha de ciclo com reset automatico. */
    private LinearLayout setCycleRow(String label, final String[] labels, final int[] values,
                                     final int[] cur, final int def, final List<Runnable> refreshers,
                                     final List<Runnable> resets, final Runnable onChange) {
        LinearLayout row = vidCycle(label, labels, values, cur, refreshers, onChange);
        resets.add(new Runnable() {
            public void run() {
                cur[0] = def;
                for (int i = 0; i < refreshers.size(); i++) {
                    refreshers.get(i).run();
                }
            }
        });
        return row;
    }

    /** Abre o menu completo de configuracoes do emulador (botao engrenagem).
     *  Escreve direto nas preferencias nativas (_Dra$t1c_Pref$_); o emulador
     *  le tudo de novo ao abrir o proximo jogo. */
    private void showSettingsMenu() {
        final SharedPreferences p = draPrefs();
        final List<Runnable> refreshers = new ArrayList<Runnable>();
        final List<Runnable> resets = new ArrayList<Runnable>();

        // ===== estado (valores atuais) =====
        // som
        final boolean[] snd = new boolean[]{p.getBoolean("_SoundEnabled", true)};
        final int[] vol = new int[]{clamp(p.getInt("_Volume", 10), 0, 15)};
        final boolean[] mic = new boolean[]{p.getBoolean("_MicEnabled", false)};
        // emulacao
        final boolean[] cheats = new boolean[]{p.getBoolean("_CheatsEnabled", false)};
        final int[] autosave = new int[]{clamp(p.getInt("_AutosaveMode", 1), 0, 1)};
        final boolean[] rawSav = new boolean[]{p.getBoolean("_RawSavFormat", false)};
        final boolean[] backupSav = new boolean[]{p.getBoolean("_BackupInSavestates", false)};
        final boolean[] rtcSys = new boolean[]{p.getBoolean("_RtcSystemTime", false)};
        final boolean[] clockEn = new boolean[]{p.getBoolean("_CustomClockEnable", false)};
        final long clockVal = p.getLong("_CustomClock", 0L);
        final boolean[] zipCache = new boolean[]{p.getBoolean("_ZipCaching", false)};
        final boolean[] preload = new boolean[]{p.getBoolean("_PreloadRoms", false)};
        final boolean[] autoTrim = new boolean[]{p.getBoolean("_AutoTrim", false)};
        final boolean[] lua = new boolean[]{p.getBoolean("_LuaEnabled", false)};
        final boolean[] ignoreGc = new boolean[]{p.getBoolean("_IgnoreGamecardLimit", false)};
        final boolean[] lowMem = new boolean[]{p.getBoolean("_LowMemWarningDisabled", false)};
        // jogabilidade
        final boolean[] ffwd = new boolean[]{p.getBoolean("_FastForwardToggle", false)};
        final int[] ffwdSpd = new int[]{clamp(p.getInt("_FfwdSpeed", 2), 1, 3)};
        final boolean[] ffwdInd = new boolean[]{p.getBoolean("_ShowFfwdIndicator", false)};
        final int[] autofire = new int[]{clamp(p.getInt("_AutoFireSpeed", 2), 1, 5)};
        final int[] stickMode = new int[]{clamp(p.getInt("_AnalogStickMode", -1), -1, 1)};
        final boolean[] analogTouch = new boolean[]{p.getBoolean("_AnalogTouch", false)};
        final boolean[] analogTrig = new boolean[]{p.getBoolean("_AnalogTriggers", false)};
        final boolean[] touchThrough = new boolean[]{p.getBoolean("_TouchThrough", false)};
        final boolean[] noBack = new boolean[]{p.getBoolean("_DisableBackButton", false)};
        final boolean[] noMenu = new boolean[]{p.getBoolean("_DisableMenuButton", false)};
        final boolean[] autoResume = new boolean[]{p.getBoolean("_ShortcutAutoResume", false)};
        // firmware
        final String[] nick = new String[]{p.getString("_FirmwareNick", "Dr Drastic")};
        final int[] lang = new int[]{clamp(p.getInt("_FirmwareLanguage", 1), 0, 5)};
        final int[] bdDay = new int[]{clamp(p.getInt("_FirmwareBdayDay", 10), 1, 31)};
        final int[] bdMon = new int[]{clamp(p.getInt("_FirmwareBdayMonth", 6), 1, 12)};

        // ===== persistencia =====
        final Runnable persist = new Runnable() {
            public void run() {
                SharedPreferences.Editor e = p.edit();
                e.putBoolean("_SoundEnabled", snd[0]);
                e.putInt("_Volume", vol[0]);
                e.putBoolean("_MicEnabled", mic[0]);
                e.putBoolean("_CheatsEnabled", cheats[0]);
                e.putInt("_AutosaveMode", autosave[0]);
                e.putBoolean("_RawSavFormat", rawSav[0]);
                e.putBoolean("_BackupInSavestates", backupSav[0]);
                e.putBoolean("_RtcSystemTime", rtcSys[0]);
                e.putBoolean("_CustomClockEnable", clockEn[0]);
                e.putBoolean("_ZipCaching", zipCache[0]);
                e.putBoolean("_PreloadRoms", preload[0]);
                e.putBoolean("_AutoTrim", autoTrim[0]);
                e.putBoolean("_LuaEnabled", lua[0]);
                e.putBoolean("_IgnoreGamecardLimit", ignoreGc[0]);
                e.putBoolean("_LowMemWarningDisabled", lowMem[0]);
                e.putBoolean("_FastForwardToggle", ffwd[0]);
                e.putInt("_FfwdSpeed", ffwdSpd[0]);
                e.putBoolean("_ShowFfwdIndicator", ffwdInd[0]);
                e.putInt("_AutoFireSpeed", autofire[0]);
                e.putInt("_AnalogStickMode", stickMode[0]);
                e.putBoolean("_AnalogTouch", analogTouch[0]);
                e.putBoolean("_AnalogTriggers", analogTrig[0]);
                e.putBoolean("_TouchThrough", touchThrough[0]);
                e.putBoolean("_DisableBackButton", noBack[0]);
                e.putBoolean("_DisableMenuButton", noMenu[0]);
                e.putBoolean("_ShortcutAutoResume", autoResume[0]);
                e.putString("_FirmwareNick", nick[0]);
                e.putInt("_FirmwareLanguage", lang[0]);
                e.putInt("_FirmwareBdayDay", bdDay[0]);
                e.putInt("_FirmwareBdayMonth", bdMon[0]);
                e.apply();
            }
        };

        // ===== painel =====
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
        title.setText("Configuracoes");
        title.setTextColor(0xFF222222);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(bebas());
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("X");
        x.setTextColor(0xFF666666);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setTypeface(bebas());
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

        TextView hint0 = vidHint("Vale a partir do proximo jogo aberto. Toque numa opcao de "
                + "valor para mudar; sao as mesmas opcoes do menu interno do emulador.");
        hint0.setPadding(0, dp(6), 0, 0);
        content.addView(hint0, new LinearLayout.LayoutParams(-1, -2));

        // ===== som =====
        content.addView(vidSection("Som"), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Ligar o som", snd, 1, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSeekRow("Volume", vol, 10, 15, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Microfone do DS", mic, 0, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Microfone: jogos que pedem para assoprar/falar recebem "
                + "o som do microfone do aparelho."), new LinearLayout.LayoutParams(-1, -2));

        // ===== emulacao =====
        content.addView(vidSection("Emulacao"), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Trapacas (cheats)", cheats, 0, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Liga os codes adicionados no menu interno do emulador "
                + "(action replay)."), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setCycleRow("Salvar a bateria ao sair (autosave)", SET_ONOFF,
                new int[]{0, 1}, autosave, 1, refreshers, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Gravar saves em formato cru (.sav)", rawSav, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Ligado: grava .sav padrao (funciona em outros emuladores e "
                + "flashcards). Desligado: usa o formato proprio (.dsv), que guarda mais "
                + "informacoes."), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Guardar a bateria dentro dos savestates", backupSav, 0,
                resets, persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Usar o relogio do sistema", rtcSys, 0, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        TextView btnClock = pillButton("Definir data e hora do DS", 0xFF3A3A3C, 0xFFFFFFFF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(8);
        content.addView(btnClock, clp);
        btnClock.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Calendar c = Calendar.getInstance();
                if (clockVal > 0L) {
                    c.setTimeInMillis(clockVal);
                }
                new DatePickerDialog(HomeActivity.this, null,
                        c.get(Calendar.YEAR), c.get(Calendar.MONTH),
                        c.get(Calendar.DAY_OF_MONTH)) {
                    public void onDateSet(android.widget.DatePicker dp2, int y, int m, int day) {
                        new TimePickerDialog(HomeActivity.this,
                                new TimePickerDialog.OnTimeSetListener() {
                                    public void onTimeSet(android.widget.TimePicker tp, int hh, int mm) {
                                        Calendar c2 = Calendar.getInstance();
                                        c2.set(y, m, day, hh, mm, 0);
                                        p.edit().putLong("_CustomClock",
                                                c2.getTimeInMillis()).commit();
                                    }
                                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true)
                                .show();
                    }
                }.show();
                p.edit().putBoolean("_CustomClockEnable", true).commit();
            }
        });
        content.addView(vidHint("Define a data/hora que o jogo enxerga (util para eventos "
                + "especiais de jogos como Pokemon). Ao usar, o relogio personalizado e "
                + "ligado automaticamente."), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Cache para ROMs zipadas", zipCache, 0, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Pre-carregar a lista de jogos", preload, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Cortar bordas pretas automaticamente", autoTrim, 0,
                resets, persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Suporte a scripts Lua", lua, 0, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Ignorar limite de gamecard", ignoreGc, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Nao avisar sobre pouca memoria", lowMem, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));

        // ===== jogabilidade =====
        content.addView(vidSection("Jogabilidade"), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Avanco rapido (fast-forward)", ffwd, 0, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setCycleRow("Velocidade do avanco rapido", SET_FFWD_LABELS,
                new int[]{1, 2, 3}, ffwdSpd, 2, refreshers, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Mostrar indicador de avanco", ffwdInd, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setCycleRow("Velocidade do auto-fire",
                new String[]{"1", "2", "3", "4", "5"}, new int[]{1, 2, 3, 4, 5}, autofire, 2,
                refreshers, resets, persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Auto-fire: repeticao automatica do botao mapeado no menu "
                + "interno; 1 = mais rapida."), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setCycleRow("Analogico virtual", SET_STICK_LABELS, new int[]{-1, 0, 1},
                stickMode, -1, refreshers, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Escolhe qual lado da tela o analogico virtual controla; "
                + "Automatico decide sozinho pelo jogo."), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Analogico move o toque na tela", analogTouch, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Gatilhos do analogico", analogTrig, 0, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Toque atravessa os controles", touchThrough, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Permite tocar a tela de baixo atraves da area dos botoes "
                + "virtuais."), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Desativar botao voltar", noBack, 0, resets, persist),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Desativar botao de menu do pad", noMenu, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRow("Atalho continua de onde parou", autoResume, 0, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));

        // ===== firmware (perfil do DS) =====
        content.addView(vidSection("Firmware (perfil do DS)"), new LinearLayout.LayoutParams(-1, -2));
        final TextView valNick = menuValue();
        valNick.setText(nick[0]);
        LinearLayout rowNick = vidRow("Apelido no jogo", valNick);
        rowNick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final EditText et = new EditText(HomeActivity.this);
                et.setText(nick[0]);
                et.setMaxLines(1);
                et.setTypeface(bebas());
                new AlertDialog.Builder(HomeActivity.this)
                        .setTitle("Apelido no jogo (max. 10 letras)")
                        .setView(et)
                        .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface dlg, int w) {
                                String s = et.getText().toString().trim();
                                if (s.length() > 10) {
                                    s = s.substring(0, 10);
                                }
                                if (s.length() > 0) {
                                    nick[0] = s;
                                    valNick.setText(s);
                                    persist.run();
                                }
                            }
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            }
        });
        content.addView(rowNick, new LinearLayout.LayoutParams(-1, -2));
        content.addView(setCycleRow("Idioma", SET_LANG_LABELS, new int[]{0, 1, 2, 3, 4, 5},
                lang, 1, refreshers, resets, persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setCycleRow("Mes de aniversario", SET_MONTH_LABELS,
                new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, bdMon, 6, refreshers, resets,
                persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setCycleRow("Dia do aniversario",
                new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
                        "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23",
                        "24", "25", "26", "27", "28", "29", "30", "31"},
                new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
                        20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31},
                bdDay, 10, refreshers, resets, persist), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("O perfil aparece nos jogos que leem o firmware do DS "
                + "(nome do treinador, idioma e aniversario)."), new LinearLayout.LayoutParams(-1, -2));

        // ===== atalhos para os outros menus =====
        content.addView(vidSection("Outros menus"), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnVid = pillButton("Opcoes de video", 0xFF2E9E3E, 0xFFFFFFFF);
        TextView btnShd = pillButton("Shaders", 0xFF1565C0, 0xFFFFFFFF);
        TextView btnTxt = pillButton("Texto", 0xFFE53935, 0xFFFFFFFF);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1f);
        l1.rightMargin = dp(6);
        linkRow.addView(btnVid, l1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1f);
        l2.rightMargin = dp(6);
        linkRow.addView(btnShd, l2);
        linkRow.addView(btnTxt, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(linkRow, cwlp(10));
        btnVid.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
                showVideoMenu();
            }
        });
        btnShd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
                showShaderMenu();
            }
        });
        btnTxt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
                showTextMenu();
            }
        });

        LinearLayout linkRow2 = new LinearLayout(this);
        linkRow2.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnEdS = pillButton("Editar telas", 0xFF3A3A3C, 0xFFFFFFFF);
        TextView btnEdC = pillButton("Editar controles", 0xFF3A3A3C, 0xFFFFFFFF);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, -2, 1f);
        l3.rightMargin = dp(6);
        linkRow2.addView(btnEdS, l3);
        linkRow2.addView(btnEdC, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(linkRow2, cwlp(8));
        btnEdS.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
                showLayoutEditor(0);
            }
        });
        btnEdC.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
                showLayoutEditor(1);
            }
        });

        // ===== rodape =====
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = pillButton("Padrao", 0xFFE8E8EC, 0xFF333338);
        TextView closeBtn = pillButton("Fechar", 0xFF3A3A3C, 0xFFFFFFFF);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.rightMargin = dp(8);
        btns.addView(reset, rlp);
        btns.addView(closeBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(btns, cwlp(18));

        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                snd[0] = true;
                vol[0] = 10;
                mic[0] = false;
                cheats[0] = false;
                autosave[0] = 1;
                rawSav[0] = false;
                backupSav[0] = false;
                rtcSys[0] = false;
                clockEn[0] = false;
                zipCache[0] = false;
                preload[0] = false;
                autoTrim[0] = false;
                lua[0] = false;
                ignoreGc[0] = false;
                lowMem[0] = false;
                ffwd[0] = false;
                ffwdSpd[0] = 2;
                ffwdInd[0] = false;
                autofire[0] = 2;
                stickMode[0] = -1;
                analogTouch[0] = false;
                analogTrig[0] = false;
                touchThrough[0] = false;
                noBack[0] = false;
                noMenu[0] = false;
                autoResume[0] = false;
                nick[0] = "Dr Drastic";
                lang[0] = 1;
                bdDay[0] = 10;
                bdMon[0] = 6;
                for (int i = resets.size() - 1; i >= 0; i--) {
                    resets.get(i).run();
                }
                valNick.setText(nick[0]);
                persist.run();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                persist.run();
                closer.run();
            }
        });

        overlay.addView(panel, new FrameLayout.LayoutParams(-1,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.86f), Gravity.BOTTOM));
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    // ---------- controle (botao branco do pad) ----------

    /** Slots do mapeamento de teclas do DraStic (os indices 8 a 11 sao internos de toque). */
    private static final int[] PAD_MAP_SLOTS = {
            0, 1, 2, 3, 4, 5, 6, 7, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
            26, 27, 28
    };
    private static final String[] PAD_MAP_LABELS = {
            "X", "Y", "B", "A", "R", "L", "Start", "Select",
            "Cima", "Direita", "Baixo", "Esquerda", "Trocar telas", "Avanco rapido",
            "Salvar rapido", "Carregar rapido", "Abrir menu", "Cima-Esquerda", "Cima-Direita",
            "Baixo-Esquerda", "Baixo-Direita", "Meia troca de tela", "Ruido do microfone",
            "Ligar auto-fire", "Ponteiro para baixo"
    };

    /** Abre o menu de controle (botao branco): mapeamento, skin PNG, analogico,
     *  ocultar botoes e atalho para o editor de layout. */
    private void showPadMenu() {
        final SharedPreferences p = draPrefs();
        final SharedPreferences home = prefs;

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
        title.setText("Controle");
        title.setTextColor(0xFF222222);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(bebas());
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("X");
        x.setTextColor(0xFF666666);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setTypeface(bebas());
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

        TextView hint0 = vidHint("Vale a partir do proximo jogo aberto.");
        hint0.setPadding(0, dp(6), 0, 0);
        content.addView(hint0, new LinearLayout.LayoutParams(-1, -2));

        // ===== mapeamento =====
        content.addView(vidSection("Mapeamento (teclado e controle)"), new LinearLayout.LayoutParams(-1, -2));
        final int[] mapId = new int[]{p.getInt("_KeyMapId", -1)};
        final String[] profLabels = {"Padrao (auto)", "Perfil 1", "Perfil 2", "Perfil 3"};
        final int[] profVals = {-1, 0, 1, 2};
        final List<Runnable> refreshers = new ArrayList<Runnable>();
        content.addView(vidCycle("Perfil de mapeamento", profLabels, profVals, mapId,
                refreshers, new Runnable() {
                    public void run() {
                        p.edit().putInt("_KeyMapId", mapId[0]).commit();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        TextView btnMap = pillButton("Mapear teclas", 0xFF3A3A3C, 0xFFFFFFFF);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(8);
        content.addView(btnMap, mlp);
        content.addView(vidHint("Toque num item da lista e aperte uma tecla no teclado ou "
                + "um botao do controle/joystick conectado. Voltar cancela."), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRowHere("Desativar mapeamentos", "_DisableMapped", false, p),
                new LinearLayout.LayoutParams(-1, -2));

        btnMap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mapId[0] < 0) {
                    mapId[0] = 0;
                    p.edit().putInt("_KeyMapId", 0).commit();
                    for (int i = 0; i < refreshers.size(); i++) {
                        refreshers.get(i).run();
                    }
                }
                keymapProfile = mapId[0];
                keyRowValues.clear();
                final FrameLayout mOverlay = new FrameLayout(HomeActivity.this);
                mOverlay.setBackgroundColor(0x66000000);
                mOverlay.setClickable(true);
                final Runnable mCloser = new Runnable() {
                    public void run() {
                        capturingKeySlot = -1;
                        rootFrame.removeView(mOverlay);
                    }
                };
                mOverlay.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        mCloser.run();
                    }
                });
                ScrollView mPanel = new ScrollView(HomeActivity.this);
                mPanel.setVerticalScrollBarEnabled(false);
                mPanel.setClickable(true);
                GradientDrawable mbg = new GradientDrawable();
                mbg.setColor(0xFFF7F7F9);
                mbg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
                mPanel.setBackgroundDrawable(mbg);
                LinearLayout mc = new LinearLayout(HomeActivity.this);
                mc.setOrientation(LinearLayout.VERTICAL);
                mc.setPadding(dp(16), dp(12), dp(16), dp(20));
                mPanel.addView(mc, new FrameLayout.LayoutParams(-1, -2));
                TextView mt = new TextView(HomeActivity.this);
                mt.setText("Mapear teclas - Perfil " + (keymapProfile + 1));
                mt.setTextColor(0xFF222222);
                mt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                mt.setTypeface(bebas());
                mc.addView(mt, new LinearLayout.LayoutParams(-1, -2));
                mc.addView(vidHint("Toque num item e aperte a tecla/botao que quiser usar. "
                        + "O botao Voltar cancela a captura."), new LinearLayout.LayoutParams(-1, -2));
                for (int i = 0; i < PAD_MAP_SLOTS.length; i++) {
                    final int slot = PAD_MAP_SLOTS[i];
                    final TextView val = menuValue();
                    keyRowValues.put(slot, val);
                    LinearLayout row = vidRow(PAD_MAP_LABELS[i] + " ...", val);
                    row.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v2) {
                            capturingKeySlot = slot;
                            Toast.makeText(HomeActivity.this,
                                    "Aperte a tecla/botao para \"" + PAD_MAP_LABELS[slot]
                                            + "\" (Voltar cancela)", Toast.LENGTH_SHORT).show();
                        }
                    });
                    mc.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                TextView clearBtn = pillButton("Limpar mapeamento deste perfil", 0xFFE53935, 0xFFFFFFFF);
                LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(-1, -2);
                clp2.topMargin = dp(14);
                mc.addView(clearBtn, clp2);
                clearBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v2) {
                        SharedPreferences.Editor e = p.edit();
                        for (int i = 0; i < PAD_MAP_SLOTS.length; i++) {
                            e.putInt("_KeyMapConfigs_" + keymapProfile + "_" + PAD_MAP_SLOTS[i], -1);
                        }
                        e.commit();
                        refreshKeyRows();
                        Toast.makeText(HomeActivity.this, "Mapeamento limpo", Toast.LENGTH_SHORT).show();
                    }
                });
                TextView closeBtn = pillButton("Fechar", 0xFF2E9E3E, 0xFFFFFFFF);
                mc.addView(closeBtn, cwlp(8));
                closeBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v2) {
                        mCloser.run();
                    }
                });
                mOverlay.addView(mPanel, new FrameLayout.LayoutParams(-1,
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.86f),
                        Gravity.BOTTOM));
                refreshKeyRows();
                rootFrame.addView(mOverlay, new FrameLayout.LayoutParams(-1, -1));
            }
        });

        // ===== skin (botoes em PNG) =====
        content.addView(vidSection("Botoes em PNG (skin do pad)"), new LinearLayout.LayoutParams(-1, -2));
        TextView btnSkin = pillButton("Importar imagens dos botoes (PNG)", 0xFF1565C0, 0xFFFFFFFF);
        content.addView(btnSkin, new LinearLayout.LayoutParams(-1, -2));
        btnSkin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/png");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(i, "Escolha os PNGs dos botoes"),
                        REQ_SKIN);
            }
        });
        TextView btnSkinDef = pillButton("Voltar aos botoes padrao", 0xFFE8E8EC, 0xFF333338);
        content.addView(btnSkinDef, cwlp(8));
        btnSkinDef.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                p.edit().putString("_VirtualPadSkinFolder", "").commit();
                Toast.makeText(HomeActivity.this,
                        "Skin padrao. Vale a partir do proximo jogo aberto.",
                        Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(vidHint("Escolha os PNGs dos botoes que voce quiser (pode marcar "
                + "varios de uma vez). Eles sao copiados para a pasta do app e o emulador "
                + "passa a usa-los como skin do pad."), new LinearLayout.LayoutParams(-1, -2));

        // ===== ocultar e aparencia dos botoes =====
        content.addView(vidSection("Botoes na tela"), new LinearLayout.LayoutParams(-1, -2));
        final float[] alphaCur = new float[]{p.getFloat("_ControllerAlpha", 0.45f)};
        final boolean[] hidden = new boolean[]{alphaCur[0] <= 0.001f};
        final float savedAlpha = home.getFloat("pad_alpha_saved", 0.45f);
        if (hidden[0] && savedAlpha > 0.001f) {
            alphaCur[0] = savedAlpha;
        }
        final TextView valAlpha = menuValue();
        valAlpha.setText(hidden[0] ? "Ocultos" : Math.round(alphaCur[0] * 100f) + "%");
        content.addView(vidSwitch("Ocultar todos os botoes", vidSwitchNew(hidden,
                new Runnable() {
                    public void run() {
                        if (hidden[0]) {
                            home.edit().putFloat("pad_alpha_saved", alphaCur[0]).commit();
                            p.edit().putFloat("_ControllerAlpha", 0f).commit();
                            valAlpha.setText("Ocultos");
                        } else {
                            p.edit().putFloat("_ControllerAlpha", alphaCur[0]).commit();
                            valAlpha.setText(Math.round(alphaCur[0] * 100f) + "%");
                        }
                    }
                })), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidRow("Transparencia dos botoes", valAlpha),
                new LinearLayout.LayoutParams(-1, -2));
        SeekBar sbAlpha = new SeekBar(this);
        sbAlpha.setMax(100);
        sbAlpha.setProgress(Math.round(alphaCur[0] * 100f));
        sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean fromUser) {
                alphaCur[0] = pr / 100f;
                if (!hidden[0]) {
                    valAlpha.setText(pr + "%");
                    p.edit().putFloat("_ControllerAlpha", alphaCur[0]).commit();
                }
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        content.addView(sbAlpha, new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRowHere("Vibracao ao tocar", "_HapticFeedback", true, p),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRowHere("Nao usar diagonais no D-Pad", "_NoDiagonals", false, p),
                new LinearLayout.LayoutParams(-1, -2));

        // ===== analogico =====
        content.addView(vidSection("Analogico"), new LinearLayout.LayoutParams(-1, -2));
        final int[] stick = new int[]{p.getInt("_AnalogStickMode", -1)};
        content.addView(vidCycle("Ativar analogico virtual",
                new String[]{"Automatico", "Esquerdo", "Direito"}, new int[]{-1, 0, 1}, stick,
                refreshers, new Runnable() {
                    public void run() {
                        p.edit().putInt("_AnalogStickMode", stick[0]).commit();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Automatico decide sozinho pelo jogo; Esquerdo/Direito "
                + "forcam o lado da tela onde o analogico aparece."), new LinearLayout.LayoutParams(-1, -2));
        final boolean[] pen = new boolean[]{p.getBoolean("_AnalogTouch", false)};
        content.addView(vidSwitch("Analogico de caneta (arrastar na tela)",
                vidSwitchNew(pen, new Runnable() {
                    public void run() {
                        p.edit().putBoolean("_AnalogTouch", pen[0]).commit();
                    }
                })), new LinearLayout.LayoutParams(-1, -2));
        content.addView(vidHint("Arrastar a caneta/dedo na tela de baixo move o analogico "
                + "virtual."), new LinearLayout.LayoutParams(-1, -2));
        content.addView(setSwitchRowHere("Gatilhos do analogico", "_AnalogTriggers", false, p),
                new LinearLayout.LayoutParams(-1, -2));
        final float defDead = 0.3f;
        final float dead = p.getFloat("_AnalogDeadzone", defDead);
        final TextView valDead = menuValue();
        valDead.setText(Math.round(dead * 100f) + "%");
        content.addView(vidRow("Zona morta do analogico", valDead),
                new LinearLayout.LayoutParams(-1, -2));
        SeekBar sbDead = new SeekBar(this);
        sbDead.setMax(100);
        sbDead.setProgress(Math.round(dead * 100f));
        sbDead.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean fromUser) {
                valDead.setText(pr + "%");
                p.edit().putFloat("_AnalogDeadzone", pr / 100f).commit();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        content.addView(sbDead, new LinearLayout.LayoutParams(-1, -2));

        // ===== layout =====
        content.addView(vidSection("Layout"), new LinearLayout.LayoutParams(-1, -2));
        TextView btnEdPad = pillButton("Editar layout de controle", 0xFF2E9E3E, 0xFFFFFFFF);
        content.addView(btnEdPad, new LinearLayout.LayoutParams(-1, -2));
        btnEdPad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
                showLayoutEditor(1);
            }
        });
        TextView btnEdScr = pillButton("Editar posicao das telas", 0xFF3A3A3C, 0xFFFFFFFF);
        content.addView(btnEdScr, cwlp(8));
        btnEdScr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
                showLayoutEditor(0);
            }
        });

        // rodape
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = pillButton("Padrao", 0xFFE8E8EC, 0xFF333338);
        TextView closeBtn = pillButton("Fechar", 0xFF2E9E3E, 0xFFFFFFFF);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.rightMargin = dp(8);
        btns.addView(reset, rlp);
        btns.addView(closeBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(btns, cwlp(18));

        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SharedPreferences.Editor e = p.edit();
                e.putBoolean("_DisableMapped", false);
                e.putString("_VirtualPadSkinFolder", "");
                e.putFloat("_ControllerAlpha", 0.45f);
                e.putBoolean("_HapticFeedback", true);
                e.putBoolean("_NoDiagonals", false);
                e.putInt("_AnalogStickMode", -1);
                e.putBoolean("_AnalogTouch", false);
                e.putBoolean("_AnalogTriggers", false);
                e.putFloat("_AnalogDeadzone", defDead);
                e.commit();
                hidden[0] = false;
                alphaCur[0] = 0.45f;
                valAlpha.setText("45%");
                sbAlpha.setProgress(45);
                stick[0] = -1;
                mapId[0] = -1;
                for (int i = 0; i < refreshers.size(); i++) {
                    refreshers.get(i).run();
                }
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closer.run();
            }
        });

        overlay.addView(panel, new FrameLayout.LayoutParams(-1,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.86f), Gravity.BOTTOM));
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Switch simples de uma chave booleana, gravando na hora. */
    private LinearLayout setSwitchRowHere(String label, final String key, boolean def,
                                          final SharedPreferences p) {
        final boolean[] st = new boolean[]{p.getBoolean(key, def)};
        return vidSwitch(label, vidSwitchNew(st, new Runnable() {
            public void run() {
                p.edit().putBoolean(key, st[0]).commit();
            }
        }));
    }

    // ---------- editor de telas e controles ----------

    /** Layout "Personalizado": gravado no config/LC_5.dat do DraStic. */
    private static final int CUST_LAYOUT = 5;

    /** Retangulo simples usado pelo editor (em pixels reais de tela). */
    private static class R {
        int x, y, w, h;
        boolean v = true;

        R(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private R[] edScr;   // [0] = tela de cima, [1] = tela de baixo
    private R[] edPad;   // 9 areas do controle (dpad, botoes, L, R, ...)
    private boolean edSwap;      // flag de troca gravada junto
    private boolean[] edPadVis;  // flags de visibilidade gravadas junto

    private static final String[] PAD_NAMES = {
            "D-Pad", "Botoes", "L", "R", "Start", "Select", "Menu", "Extra 1", "Extra 2"};
    private static final int[] PAD_COLORS = {
            0xFF3D8BFF, 0xFFE53935, 0xFFFFA000, 0xFFFFA000, 0xFF2E9E3E, 0xFF2E9E3E,
            0xFF8E24AA, 0xFF00897B, 0xFFD81B60};

    private void edDefaults(float f, boolean sideBySide) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int W = dm.widthPixels;
        int H = dm.heightPixels;
        int w, h;
        if (sideBySide) {
            w = (int) (W * f);
            h = w * 3 / 4;
            if (h * 2 > H * f) {
                h = (int) (H * f) / 2;
                w = h * 4 / 3;
            }
            int x0 = (W - w * 2) / 2;
            int y0 = (H - h) / 2;
            edScr[0] = new R(x0, y0, w, h);
            edScr[1] = new R(x0 + w + dp(4), y0, w, h);
        } else {
            w = (int) (W * f);
            h = w * 3 / 4;
            if (h * 2 > H * f) {
                h = (int) (H * f) / 2;
                w = h * 4 / 3;
            }
            int x0 = (W - w) / 2;
            int y0 = (H - h * 2) / 2;
            edScr[0] = new R(x0, y0, w, h);
            edScr[1] = new R(x0, y0 + h + dp(4), w, h);
        }

        int s = (int) (Math.min(W, H) * 0.24f);
        int m = (int) (Math.min(W, H) * 0.02f);
        if (sideBySide && W >= H) {
            edPad[0] = new R(m, H - s - m, s, s);
            edPad[1] = new R(W - s - m, H - s - m, s, s);
        } else {
            edPad[0] = new R(m, H - s - m, s, s);
            edPad[1] = new R(W - s - m, H - s - m, s, s);
        }
        int sh = (int) (s * 0.55f);
        edPad[2] = new R(m, m, sh, sh);
        edPad[3] = new R(W - sh - m, m, sh, sh);
        int ss = (int) (s * 0.45f);
        edPad[4] = new R(W / 2 - ss - dp(6), H - ss - m, ss, ss);
        edPad[5] = new R(W / 2 + dp(6), H - ss - m, ss, ss);
        int mn = (int) (s * 0.4f);
        edPad[6] = new R(W - mn - m, (int) (m + sh * 1.3f), mn, mn);
        edPad[7] = new R(0, 0, 0, 0);
        edPad[7].v = false;
        edPad[8] = new R(0, 0, 0, 0);
        edPad[8].v = false;
        edPadVis = new boolean[]{true, true, true, true};
        edSwap = false;
    }

    private void edDefaultsNow() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        edDefaults(1.0f, dm.widthPixels >= dm.heightPixels);
    }

    private static Object edNew(String cls) throws Exception {
        return Class.forName(cls).newInstance();
    }

    private static void edSetRect(Object holder, String field, R r) throws Exception {
        Object m = holder.getClass().getField(field).get(holder);
        Class c = m.getClass();
        c.getField("a").setInt(m, r.x);
        c.getField("b").setInt(m, r.y);
        c.getField("c").setInt(m, r.w);
        c.getField("d").setInt(m, r.h);
        c.getField("e").setBoolean(m, r.v);
    }

    private static R edGetRect(Object holder, String field) throws Exception {
        Object m = holder.getClass().getField(field).get(holder);
        Class c = m.getClass();
        R r = new R(c.getField("a").getInt(m), c.getField("b").getInt(m),
                c.getField("c").getInt(m), c.getField("d").getInt(m));
        r.v = c.getField("e").getBoolean(m);
        return r;
    }

    /** Le o layout personalizado (LC_5.dat); se nao existir, cria valores padrao. */
    private void edLoadAll() {
        edScr = new R[2];
        edPad = new R[9];
        edDefaultsNow();
        try {
            Class fh = Class.forName("f0.h");
            String oldF = null;
            try {
                oldF = (String) fh.getField("f").get(null);
            } catch (Throwable t2) {
            }
            fh.getField("f").set(null, "alphads.nds");
            Object k = edNew("f0.k");
            Object b = edNew("f0.b");
            Object j = edNew("f0.j");
            j.getClass().getField("b").set(j, "");
            Method mz = fh.getMethod("z", android.content.Context.class,
                    int.class, k.getClass(), b.getClass(), boolean.class, j.getClass());
            boolean ok = ((Boolean) mz.invoke(null, this, CUST_LAYOUT, k, b, false, j)).booleanValue();
            if (oldF != null) {
                try {
                    fh.getField("f").set(null, oldF);
                } catch (Throwable t2) {
                }
            }
            if (ok) {
                edScr[0] = edGetRect(k, "a");
                edScr[1] = edGetRect(k, "b");
                edSwap = k.getClass().getField("c").getBoolean(k);
                String[] bf = {"a", "b", "c", "d", "e", "f", "g", "h", "i"};
                for (int i = 0; i < 9; i++) {
                    edPad[i] = edGetRect(b, bf[i]);
                }
                edPadVis = new boolean[4];
                String[] vf = {"j", "k", "l", "m"};
                for (int i = 0; i < 4; i++) {
                    edPadVis[i] = b.getClass().getField(vf[i]).getBoolean(b);
                }
            }
        } catch (Throwable t) {
            // sem layout salvo: segue com os padroes
        }
    }

    /** Grava o layout personalizado e seleciona ele como layout padrao. */
    private void edSaveAll() {
        try {
            Class fh = Class.forName("f0.h");
            fh.getField("f").set(null, "alphads.nds");
            Object k = edNew("f0.k");
            Object b = edNew("f0.b");
            Object j = edNew("f0.j");
            j.getClass().getField("b").set(j, "");
            edSetRect(k, "a", edScr[0]);
            edSetRect(k, "b", edScr[1]);
            k.getClass().getField("c").setBoolean(k, edSwap);
            String[] bf = {"a", "b", "c", "d", "e", "f", "g", "h", "i"};
            for (int i = 0; i < 9; i++) {
                edSetRect(b, bf[i], edPad[i]);
            }
            String[] vf = {"j", "k", "l", "m"};
            for (int i = 0; i < 4; i++) {
                b.getClass().getField(vf[i]).setBoolean(b, edPadVis[i]);
            }
            Method me = fh.getMethod("E", android.content.Context.class,
                    int.class, k.getClass(), b.getClass(), boolean.class, j.getClass());
            me.invoke(null, this, CUST_LAYOUT, k, b, false, j);
            draPrefs().edit().putInt("_DefaultLayout", CUST_LAYOUT).commit();
        } catch (Throwable t) {
            Toast.makeText(this, "Erro ao salvar o layout", Toast.LENGTH_SHORT).show();
        }
    }

    /** Recalcula as telas com outro tamanho e grava (usado pelo ciclo de tamanho). */
    private void applyScreenScale(float f) {
        edLoadAll();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        edDefaults(f, dm.widthPixels >= dm.heightPixels);
        edSaveAll();
        Toast.makeText(this, "Tamanho aplicado; abra um jogo para ver", Toast.LENGTH_SHORT).show();
    }

    /** Editor visual: mode 0 = telas, mode 1 = controles. */
    private void showLayoutEditor(final int mode) {
        edLoadAll();

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xEE101014);
        overlay.setClickable(true);

        final Runnable closer = new Runnable() {
            public void run() {
                rootFrame.removeView(overlay);
            }
        };

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(10), dp(14), dp(6));
        TextView title = new TextView(this);
        title.setText(mode == 0 ? "Editar telas" : "Editar controles");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(bebas());
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView x = new TextView(this);
        x.setText("X");
        x.setTextColor(0xFF333338);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        x.setTypeface(bebas());
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
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText(mode == 0
                ? "Arraste as telas. Toque no canto inferior direito e arraste para redimensionar."
                : "Arraste cada controle. Toque no canto inferior direito e arraste para mudar o tamanho.");
        hint.setTextColor(0xFF9A9AA2);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTypeface(bebas());
        hint.setPadding(dp(14), 0, dp(14), dp(6));
        col.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        final EditorView ev = new EditorView(this, mode);
        col.addView(ev, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(10), dp(8), dp(10), dp(14));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.rightMargin = dp(8);
        if (mode == 0) {
            TextView st = pillButton("Empilhadas", 0xFF333338, 0xFFFFFFFF);
            st.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    edDefaults(edScreenScale(), false);
                    ev.invalidate();
                }
            });
            bar.addView(st, blp);
            TextView sd = pillButton("Lado a lado", 0xFF333338, 0xFFFFFFFF);
            sd.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    edDefaults(edScreenScale(), true);
                    ev.invalidate();
                }
            });
            bar.addView(sd, blp);
            TextView swp = pillButton("Trocar", 0xFF333338, 0xFFFFFFFF);
            swp.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    R tmp = edScr[0];
                    edScr[0] = edScr[1];
                    edScr[1] = tmp;
                    ev.invalidate();
                }
            });
            bar.addView(swp, blp);
        }
        TextView rst = pillButton("Padrao", 0xFF333338, 0xFFFFFFFF);
        rst.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                edDefaultsNow();
                ev.invalidate();
            }
        });
        bar.addView(rst, blp);
        TextView save = pillButton("Salvar", 0xFF2E9E3E, 0xFFFFFFFF);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                edSaveAll();
                Toast.makeText(HomeActivity.this,
                        "Layout salvo como Personalizado; abra um jogo para ver",
                        Toast.LENGTH_SHORT).show();
                closer.run();
            }
        });
        bar.addView(save, new LinearLayout.LayoutParams(-2, -2));
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        overlay.addView(col, new FrameLayout.LayoutParams(-1, -1));
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private float edScreenScale() {
        int i = clamp(draPrefs().getInt("alphads_scrsize", 2), 0, 2);
        return new float[]{0.70f, 0.85f, 1.0f}[i];
    }

    /** Area de desenho do editor: mostra os retangulos e trata o arraste. */
    private class EditorView extends View {
        private final int mode;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int sel = -1;
        private boolean resizing;
        private float sc = 1f;
        private int ox, oy;

        EditorView(Context c, int m) {
            super(c);
            mode = m;
        }

        private R item(int i) {
            return mode == 0 ? edScr[i] : edPad[i];
        }

        private int itemCount() {
            return mode == 0 ? 2 : 9;
        }

        @Override
        protected void onSizeChanged(int vw, int vh, int ow, int oh) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            sc = Math.min(vw / (float) dm.widthPixels, vh / (float) dm.heightPixels);
            ox = (int) ((vw - dm.widthPixels * sc) / 2f);
            oy = (int) ((vh - dm.heightPixels * sc) / 2f);
        }

        @Override
        protected void onDraw(Canvas cv) {
            cv.drawColor(0xFF000000);
            for (int i = 0; i < itemCount(); i++) {
                R r = item(i);
                if (r == null || r.w <= 0 || r.h <= 0) continue;
                float lx = ox + r.x * sc;
                float ty = oy + r.y * sc;
                float lw = r.w * sc;
                float lh = r.h * sc;
                int color = mode == 0 ? (i == 0 ? 0xFF3D8BFF : 0xFFFFA000) : PAD_COLORS[i];
                p.setStyle(Paint.Style.FILL);
                p.setColor((color & 0x00FFFFFF) | 0x33000000);
                cv.drawRect(lx, ty, lx + lw, ty + lh, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(i == sel ? dp(3) : dp(2));
                p.setColor(color);
                cv.drawRect(lx, ty, lx + lw, ty + lh, p);
                if (i == sel) {
                    p.setStyle(Paint.Style.FILL);
                    cv.drawRect(lx + lw - dp(18), ty + lh - dp(18), lx + lw, ty + lh, p);
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                p.setTextSize(dp(12));
                String nm = mode == 0 ? (i == 0 ? "CIMA" : "BAIXO") : PAD_NAMES[i];
                cv.drawText(nm, lx + dp(6), ty + dp(16), p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float tx = (e.getX() - ox) / sc;
            float ty = (e.getY() - oy) / sc;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    sel = -1;
                    resizing = false;
                    int bestArea = Integer.MAX_VALUE;
                    for (int i = 0; i < itemCount(); i++) {
                        R r = item(i);
                        if (r == null || r.w <= 0 || r.h <= 0) continue;
                        int pad = dp(10);
                        if (tx >= r.x - pad && tx <= r.x + r.w + pad
                                && ty >= r.y - pad && ty <= r.y + r.h + pad) {
                            int area = r.w * r.h;
                            if (area <= bestArea) {
                                bestArea = area;
                                sel = i;
                                resizing = tx > r.x + r.w - dp(24) && ty > r.y + r.h - dp(24);
                            }
                        }
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (sel < 0) return true;
                    R r = item(sel);
                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    if (resizing) {
                        r.w = clamp((int) tx - r.x, dp(30), dm.widthPixels - r.x);
                        r.h = clamp((int) ty - r.y, dp(30), dm.heightPixels - r.y);
                    } else {
                        r.x = clamp((int) tx - r.w / 2, 0, dm.widthPixels - r.w);
                        r.y = clamp((int) ty - r.h / 2, 0, dm.heightPixels - r.h);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        }
    }

    /** Grava todas as opcoes de video nas preferencias nativas do emulador. */
    private void persistVideo(SharedPreferences p, int fsType, int fsVal, boolean fsSafe,
                              boolean threaded, int cpuLoad, int latency, boolean hires,
                              boolean bit16, boolean lowres, boolean noEdge, boolean glSleep,
                              boolean fixMain, boolean showFps, boolean fpsTrans, int layout,
                              int rot, int extType, int extScr, boolean sync) {
        SharedPreferences.Editor e = p.edit();
        e.putInt("_FrameskipType", clamp(fsType, 0, 2));
        e.putInt("_FrameskipValue", clamp(fsVal, 0, 9));
        e.putBoolean("_FrameskipSafe", fsSafe);
        e.putBoolean("_Threaded3D", threaded);
        e.putInt("_CpuLoad", clamp(cpuLoad, 0, 100));
        e.putInt("_AudioLatency", clamp(latency, 0, 3));
        e.putBoolean("_Hires3D", hires);
        e.putBoolean("_GlUse16Bit", bit16);
        e.putBoolean("_UseLowResTextures", lowres);
        e.putBoolean("_DisableEdgeMarking", noEdge);
        e.putBoolean("_GlThreadSleep", glSleep);
        e.putBoolean("_FixMainEngineScreen", fixMain);
        e.putBoolean("_ShowFPS", showFps);
        e.putBoolean("_FpsTransparent", fpsTrans);
        e.putInt("_DefaultLayout", clamp(layout, 0, 5));
        e.putInt("_RotationMode", clamp(rot, 0, 2));
        e.putInt("_ExtDisplayType", clamp(extType, 0, 2));
        e.putInt("_ExtDisplayScreen", clamp(extScr, 0, 1));
        if (sync) e.commit();
        else e.apply();
    }

    // ---------- icones simples desenhados em codigo ----------

    private static class IconView extends View {
        static final int HOME = 0, TEXT = 1, FOLDER = 2, SHADER = 3, VIDEO = 4, PAD = 5, GEAR = 6;
        private final int type, bg, fg;
        private final Typeface tf;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        IconView(Context c, int type, int bg, int fg, Typeface font) {
            super(c);
            this.type = type;
            this.bg = bg;
            this.fg = fg;
            this.tf = font;
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
                    p.setTypeface(tf);
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
