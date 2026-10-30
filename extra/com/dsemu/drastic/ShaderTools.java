package com.dsemu.drastic;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utilidades do menu de shaders do AlphaDS.
 * - Localiza/cria a pasta de shaders do app (pasta dos jogos /DraStic/shaders) via SAF.
 * - Importa shaders (.dfx/.dsd nativos, .slang, .slangp, .glsl) e converte para o formato do DraStic.
 * - Baixa e converte as shaders do repositorio libretro/slang-shaders.
 * - Gera/injeta o passe de ajuste de imagem (brilho, contraste, cor, gamma).
 */
public final class ShaderTools {

    private ShaderTools() {
    }

    /** Prefs nativas do DraStic (shader ativa global = "_CurrentFx"). */
    static final String DRA_PREFS = "_Dra$t1c_Pref$_";
    static final String KEY_CURRENT_FX = "_CurrentFx";

    /** Nome da shader gerada so com ajuste de cor. */
    public static final String COLOR_SHADER_NAME = "AlphadsCor";
    /** Nome do .dsd do passe de ajuste de cor (referenciado pelos .dfx ajustados). */
    public static final String COLOR_DSD = "alphads_color.dsd";
    /** Subpasta que guarda as versoes originais (sem ajuste) dos .dfx. */
    static final String PRISTINE_DIR = ".alphads_orig";

    static final String[] BUILTIN_FX = {"Linear", "None"};

    // ============================ pasta de shaders (SAF) ============================

    private static Uri child(Context ctx, Uri parentDoc, String docId, String name) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(parentDoc, docId);
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(kids, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String n = c.getString(1);
                    if (name.equals(n)) {
                        return DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static Uri ensureChildDir(Context ctx, Uri parentDoc, String docId, String name) {
        Uri found = child(ctx, parentDoc, docId, name);
        if (found != null) return found;
        try {
            return DocumentsContract.createDocument(ctx.getContentResolver(), parentDoc,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Devolve o document-URI da pasta de shaders (cria "DraStic/shaders" se faltar). */
    public static Uri shadersDir(Context ctx, Uri tree) {
        if (tree == null) return null;
        try {
            String treeId = DocumentsContract.getTreeDocumentId(tree);
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
            Uri dra = ensureChildDir(ctx, doc, treeId, "DraStic");
            if (dra == null) return null;
            String draId = DocumentsContract.getDocumentId(dra);
            return ensureChildDir(ctx, dra, draId, "shaders");
        } catch (Exception e) {
            return null;
        }
    }

    private static Uri findChild(Context ctx, Uri dirDoc, String name) {
        try {
            String id = DocumentsContract.getDocumentId(dirDoc);
            return child(ctx, dirDoc, id, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readDoc(Context ctx, Uri doc) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(doc);
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Cria (ou reescreve) um arquivo dentro da pasta; devolve o URI ou null. */
    private static Uri writeFile(Context ctx, Uri dirDoc, String name, byte[] data) {
        try {
            Uri old = findChild(ctx, dirDoc, name);
            if (old != null) {
                DocumentsContract.deleteDocument(ctx.getContentResolver(), old);
            }
            Uri doc = DocumentsContract.createDocument(ctx.getContentResolver(), dirDoc,
                    "application/octet-stream", name);
            if (doc == null) return null;
            OutputStream os = ctx.getContentResolver().openOutputStream(doc, "w");
            if (os == null) return null;
            os.write(data);
            os.close();
            return doc;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasChild(Context ctx, Uri dirDoc, String name) {
        return findChild(ctx, dirDoc, name) != null;
    }

    private static List<String> listNames(Context ctx, Uri dirDoc, String ext) {
        List<String> out = new ArrayList<String>();
        try {
            String id = DocumentsContract.getDocumentId(dirDoc);
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(dirDoc, id);
            Cursor c = ctx.getContentResolver().query(kids, new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    if (n != null && n.toLowerCase().endsWith(ext)) out.add(n);
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Nomes (sem extensao) das shaders .dfx instaladas. */
    public static List<String> installedShaders(Context ctx, Uri tree) {
        List<String> out = new ArrayList<String>();
        Uri dir = shadersDir(ctx, tree);
        if (dir == null) return out;
        for (String n : listNames(ctx, dir, ".dfx")) {
            out.add(n.substring(0, n.length() - 4));
        }
        return out;
    }

    /** Copia Linear.dfx/None.dfx dos assets para a pasta (se faltarem), como o app faz. */
    public static void ensureBuiltins(Context ctx, Uri tree) {
        try {
            Uri dir = shadersDir(ctx, tree);
            if (dir == null) return;
            String[] files = {"Linear.dfx", "None.dfx", "linear.dsd"};
            for (String f : files) {
                if (!hasChild(ctx, dir, f)) {
                    byte[] b = readAsset(ctx, "shaders/" + f);
                    if (b != null) writeFile(ctx, dir, f, b);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAsset(Context ctx, String path) {
        try {
            InputStream in = ctx.getAssets().open(path);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ============================ shader ativa ============================

    /** Nome da shader ativa (prefs nativas do DraStic, caindo no campo estatico). */
    public static String currentShader(Context ctx) {
        try {
            String v = ctx.getSharedPreferences(DRA_PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_CURRENT_FX, null);
            if (v != null && v.length() > 0) return v;
        } catch (Exception ignored) {
        }
        try {
            Class<?> c = Class.forName("f0.h");
            return (String) c.getField("i").get(null);
        } catch (Throwable ignored) {
        }
        return "Linear";
    }

    /** Define a shader ativa (prefs nativas + campos estaticos do processo). */
    public static void setCurrentShader(Context ctx, String name) {
        try {
            ctx.getSharedPreferences(DRA_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CURRENT_FX, name).commit();
        } catch (Exception ignored) {
        }
        try {
            Class<?> c = Class.forName("f0.h");
            java.lang.reflect.Field fi = c.getField("i");
            fi.setAccessible(true);
            fi.set(null, name);
            java.lang.reflect.Field fp = c.getField("P0");
            fp.setAccessible(true);
            fp.set(null, name);
        } catch (Throwable ignored) {
        }
    }

    // ============================ ajuste de cor ============================

    private static boolean neutral(float b, float c, float s, float g) {
        return Math.abs(b - 1f) < 0.005f && Math.abs(c - 1f) < 0.005f
                && Math.abs(s - 1f) < 0.005f && Math.abs(g - 1f) < 0.005f;
    }

    private static String f(float v) {
        if (Math.abs(v - Math.round(v)) < 1e-6f) {
            return String.valueOf(Math.round(v)) + ".0";
        }
        String s = String.valueOf(v);
        if (s.length() > 8) s = s.substring(0, 8);
        return s;
    }

    /** Conteudo do alphads_color.dsd com os valores atuais. */
    static String colorDsd(float bright, float contrast, float sat, float gamma) {
        StringBuilder sb = new StringBuilder();
        sb.append("<vertex>\n");
        sb.append("attribute vec2 a_vertex_coordinate;\n");
        sb.append("attribute vec2 a_texture_coordinate;\n");
        sb.append("varying vec2 v_texture_coordinate;\n");
        sb.append("void main() {\n");
        sb.append("  gl_Position = vec4(a_vertex_coordinate.xy, 0.0, 1.0);\n");
        sb.append("  v_texture_coordinate = a_texture_coordinate;\n");
        sb.append("}\n");
        sb.append("</vertex>\n");
        sb.append("<fragment>\n");
        sb.append("#if GL_ES\n#ifdef GL_FRAGMENT_PRECISION_HIGH\n");
        sb.append("precision highp float;\n#else\nprecision mediump float;\n#endif\n#endif\n");
        sb.append("varying vec2 v_texture_coordinate;\n");
        sb.append("uniform sampler2D u_texture;\n");
        sb.append("void main() {\n");
        sb.append("  vec4 col = texture2D(u_texture, v_texture_coordinate);\n");
        sb.append("  col.rgb *= ").append(f(bright)).append(";\n");
        sb.append("  col.rgb = (col.rgb - 0.5) * ").append(f(contrast)).append(" + 0.5;\n");
        sb.append("  float gray = dot(col.rgb, vec3(0.299, 0.587, 0.114));\n");
        sb.append("  col.rgb = mix(vec3(gray), col.rgb, ").append(f(sat)).append(");\n");
        sb.append("  col.rgb = pow(max(col.rgb, vec3(0.0)), vec3(1.0 / ").append(f(gamma)).append("));\n");
        sb.append("  gl_FragColor = vec4(clamp(col.rgb, 0.0, 1.0), col.a);\n");
        sb.append("}\n");
        sb.append("</fragment>\n");
        return sb.toString();
    }

    /** Injeta o passe de ajuste de cor no final da cadeia de um .dfx. */
    static String injectColorPass(String dfxText, int texIndex) {
        String t = dfxText;
        Matcher mo = Pattern.compile("textures\\s*=\\s*(\\d+)").matcher(t);
        if (!mo.find()) return t;
        int texCount = Integer.parseInt(mo.group(1));
        int lastPass = t.lastIndexOf("<pass>");
        int lastEnd = t.lastIndexOf("</pass>");
        if (lastPass < 0 || lastEnd < lastPass) return t;
        lastEnd = lastEnd + "</pass>".length();
        String block = t.substring(lastPass, lastEnd);
        if (!block.contains("output=")) {
            block = block.replace("</pass>", "output=" + texCount + ":1\n</pass>");
            t = t.substring(0, lastPass) + block + t.substring(lastEnd);
        }
        StringBuilder add = new StringBuilder();
        add.append("\n<texture:").append(texCount).append(">\n");
        add.append("input=null\n");
        add.append("</texture>\n");
        add.append("\n<pass>\n");
        add.append("shader=").append(COLOR_DSD).append("\n");
        add.append("sampler:u_texture=").append(texCount).append("\n");
        add.append("</pass>\n");
        t = t + add;
        t = t.replaceFirst("textures\\s*=\\s*\\d+", "textures=" + (texCount + 1));
        return t;
    }

    static String standaloneColorDfx(float bright, float contrast, float sat, float gamma) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Ajuste de cor AlphaDS (brilho/contraste/cor/gamma)\n");
        sb.append("<options>\nname=").append(COLOR_SHADER_NAME).append("\ntextures=1\n</options>\n");
        sb.append("\n<fheader>\n#if GL_ES\n#ifdef GL_FRAGMENT_PRECISION_HIGH\n");
        sb.append("precision highp float;\n#else\nprecision mediump float;\n#endif\n#endif\n</fheader>\n");
        sb.append("\n<texture:0>\ninput=framebuffer\nmin_filter=GL_LINEAR\nmag_filter=GL_LINEAR\n</texture>\n");
        sb.append("\n<pass>\nshader=").append(COLOR_DSD).append("\nsampler:u_texture=0\n</pass>\n");
        return sb.toString();
    }

    /**
     * Reescreve a shader ativa com os ajustes atuais. Fora da thread principal.
     * Devolve null se ok, ou a mensagem de erro.
     */
    public static String applyColorSettings(Context ctx, Uri tree,
                                            float bright, float contrast, float sat, float gamma) {
        Uri dir = shadersDir(ctx, tree);
        if (dir == null) return "Nao achei a pasta de shaders";
        try {
            writeFile(ctx, dir, COLOR_DSD, colorDsd(bright, contrast, sat, gamma).getBytes("UTF-8"));
            ensureBuiltins(ctx, tree);
            String active = currentShader(ctx);
            Uri pristineDir = ensurePristineDir(ctx, dir);
            if (neutral(bright, contrast, sat, gamma)) {
                if (COLOR_SHADER_NAME.equals(active)) {
                    String prev = ctx.getSharedPreferences("alphads_home", Context.MODE_PRIVATE)
                            .getString("sh_before", null);
                    if (prev == null || prev.length() == 0) prev = "Linear";
                    setCurrentShader(ctx, prev);
                    active = prev;
                }
                if (!isBuiltin(active)) {
                    restorePristine(ctx, dir, pristineDir, active);
                }
                return null;
            }
            if (isBuiltin(active)) {
                if (!COLOR_SHADER_NAME.equals(active)) {
                    ctx.getSharedPreferences("alphads_home", Context.MODE_PRIVATE).edit()
                            .putString("sh_before", active).commit();
                }
                byte[] dfx = standaloneColorDfx(bright, contrast, sat, gamma).getBytes("UTF-8");
                writeFile(ctx, dir, COLOR_SHADER_NAME + ".dfx", dfx);
                setCurrentShader(ctx, COLOR_SHADER_NAME);
                return null;
            }
            // shader do usuario: reinjeta o passe a partir da versao original
            byte[] orig = readPristine(ctx, dir, pristineDir, active);
            if (orig == null) {
                Uri cur = findChild(ctx, dir, active + ".dfx");
                if (cur == null) return "Shader ativa nao encontrada";
                orig = readDoc(ctx, cur);
                if (orig != null) writeFile(ctx, pristineDir, active + ".dfx", orig);
            }
            if (orig == null) return "Nao consegui ler a shader";
            String txt = new String(orig, "UTF-8");
            txt = injectColorPass(txt, countTextures(txt));
            writeFile(ctx, dir, active + ".dfx", txt.getBytes("UTF-8"));
            return null;
        } catch (Exception e) {
            return "Erro: " + e;
        }
    }

    private static int countTextures(String dfxText) {
        Matcher m = Pattern.compile("textures\\s*=\\s*(\\d+)").matcher(dfxText);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 1;
    }

    private static boolean isBuiltin(String name) {
        if (name == null || name.length() == 0) return true;
        if (COLOR_SHADER_NAME.equals(name)) return true;
        for (String b : BUILTIN_FX) if (b.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static Uri ensurePristineDir(Context ctx, Uri dir) {
        try {
            String id = DocumentsContract.getDocumentId(dir);
            Uri d = child(ctx, dir, id, PRISTINE_DIR);
            if (d != null) return d;
            return DocumentsContract.createDocument(ctx.getContentResolver(), dir,
                    DocumentsContract.Document.MIME_TYPE_DIR, PRISTINE_DIR);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readPristine(Context ctx, Uri dir, Uri pristineDir, String name) {
        if (pristineDir == null) return null;
        Uri p = findChild(ctx, pristineDir, name + ".dfx");
        return p == null ? null : readDoc(ctx, p);
    }

    private static void restorePristine(Context ctx, Uri dir, Uri pristineDir, String name) {
        byte[] orig = readPristine(ctx, dir, pristineDir, name);
        if (orig != null) {
            Uri cur = findChild(ctx, dir, name + ".dfx");
            byte[] now = cur == null ? null : readDoc(ctx, cur);
            if (now == null || !java.util.Arrays.equals(now, orig)) {
                writeFile(ctx, dir, name + ".dfx", orig);
            }
        }
    }

    /** Guarda a versao original (sem ajuste) de uma shader recem-instalada. */
    public static void snapshotPristine(Context ctx, Uri tree, String name) {
        try {
            Uri dir = shadersDir(ctx, tree);
            if (dir == null) return;
            Uri pristineDir = ensurePristineDir(ctx, dir);
            if (pristineDir == null) return;
            if (readPristine(ctx, dir, pristineDir, name) == null) {
                Uri cur = findChild(ctx, dir, name + ".dfx");
                if (cur != null) {
                    byte[] b = readDoc(ctx, cur);
                    if (b != null) writeFile(ctx, pristineDir, name + ".dfx", b);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ============================ leitura de fontes ============================

    /** Interface para ler arquivos relativos durante a conversao (includes, .dsd, LUTs). */
    interface FileSource {
        byte[] get(String relPath);
    }

    /** Fonte SAF: resolve caminhos relativos ao pai do documento escolhido. */
    static class SafSource implements FileSource {
        final Context ctx;
        final Uri tree;
        final String parentId;

        SafSource(Context c, Uri treeUri, Uri fileDoc) {
            ctx = c;
            tree = treeUri;
            String pid = null;
            try {
                String id = DocumentsContract.getDocumentId(fileDoc);
                int slash = id.lastIndexOf('/');
                pid = slash > 0 ? id.substring(0, slash) : id;
            } catch (Exception ignored) {
            }
            parentId = pid;
        }

        public byte[] get(String relPath) {
            if (parentId == null || relPath == null) return null;
            try {
                String curId = parentId;
                Uri cur = DocumentsContract.buildDocumentUriUsingTree(tree, curId);
                String[] parts = normalizePath(relPath).split("/");
                for (String p : parts) {
                    if (p.equals("..")) {
                        int slash = curId.lastIndexOf('/');
                        if (slash <= 0) return null;
                        curId = curId.substring(0, slash);
                        cur = DocumentsContract.buildDocumentUriUsingTree(tree, curId);
                        continue;
                    }
                    Uri next = child(ctx, cur, curId, p);
                    if (next == null) return null;
                    cur = next;
                    curId = DocumentsContract.getDocumentId(next);
                }
                return readDoc(ctx, cur);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Fonte em arquivos (pasta extraida do zip). */
    static class FsSource implements FileSource {
        final File root;

        FsSource(File r) {
            root = r;
        }

        public byte[] get(String relPath) {
            try {
                File f = new File(root, normalizePath(relPath)).getCanonicalFile();
                if (!f.getPath().startsWith(root.getCanonicalFile().getPath())) return null;
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                return bo.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Resultado de uma conversao: texto do .dfx + arquivos auxiliares. */
    static class Build {
        String dfx = "";
        final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
    }

    // ============================ helpers de nome ============================

    static String safeName(String s, HashSet<String> used) {
        String n = s.replaceAll("[^A-Za-z0-9 _()\\[\\]-]", " ").trim();
        n = n.replaceAll(" +", " ");
        if (n.length() == 0) n = "shader";
        if (n.length() > 60) n = n.substring(0, 60).trim();
        String base = n;
        int i = 2;
        while (used.contains(n.toLowerCase())) {
            String suf = " " + i;
            n = base.length() + suf.length() > 60
                    ? base.substring(0, 60 - suf.length()).trim() + suf : base + suf;
            i++;
        }
        used.add(n.toLowerCase());
        return n;
    }

    static String fileStem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String fileExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    static String dsdName(String outName, int pass) {
        return outName.replaceAll(" ", "_") + "_p" + pass + ".dsd";
    }

    static String normalizePath(String p) {
        String[] parts = p.replace('\\', '/').split("/");
        ArrayList<String> st = new ArrayList<String>();
        for (String s : parts) {
            if (s.length() == 0 || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!st.isEmpty()) st.remove(st.size() - 1);
                continue;
            }
            st.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(st.get(i));
        }
        return sb.toString();
    }

    // ============================ importacao ============================

    /**
     * Importa um arquivo escolhido pelo usuario para a pasta de shaders.
     * Devolve a mensagem de resultado.
     */
    public static String importFile(Context ctx, Uri tree, Uri doc, String displayName) {
        Uri dir = shadersDir(ctx, tree);
        if (dir == null) return "Nao achei a pasta de shaders";
        try {
            ensureBuiltins(ctx, tree);
            String ext = fileExt(displayName);
            byte[] data = readDoc(ctx, doc);
            if (data == null) return displayName + ": nao consegui ler";
            String outName = safeName(fileStem(displayName), new HashSet<String>());
            Build b;
            if (ext.equals("dfx")) {
                b = passthroughDfx(new String(data, "UTF-8"), new SafSource(ctx, tree, doc), outName);
            } else if (ext.equals("slang")) {
                b = convertSlangSingle(new String(data, "UTF-8"), new SafSource(ctx, tree, doc),
                        outName, "");
            } else if (ext.equals("glsl")) {
                b = convertGlsl(new String(data, "UTF-8"), new SafSource(ctx, tree, doc), outName);
            } else if (ext.equals("slangp") || ext.equals("cgp")) {
                b = convertPreset(new String(data, "UTF-8"), new SafSource(ctx, tree, doc), outName, "");
            } else if (ext.equals("dsd")) {
                writeFile(ctx, dir, outName + ".dsd", data);
                return outName + ".dsd copiado (use com um .dfx)";
            } else {
                return displayName + ": formato nao suportado (." + ext + ")";
            }
            writeFile(ctx, dir, outName + ".dfx", b.dfx.getBytes("UTF-8"));
            for (Map.Entry<String, byte[]> e : b.files.entrySet()) {
                writeFile(ctx, dir, e.getKey(), e.getValue());
            }
            snapshotPristine(ctx, tree, outName);
            return outName + " importada";
        } catch (Exception e) {
            return displayName + ": " + e.getMessage();
        }
    }

    /** Copia um .dfx nativo reescrevendo os caminhos internos (.dsd, includes, texturas). */
    static Build passthroughDfx(String text, FileSource src, String outName) throws Exception {
        Build b = new Build();
        String t = text;
        Map<String, String> ren = new LinkedHashMap<String, String>();
        Matcher m = Pattern.compile("(shader|file|input)\\s*=\\s*([^\\s=]+\\.(dsd|raw))",
                Pattern.CASE_INSENSITIVE).matcher(t);
        while (m.find()) {
            String ref = m.group(2);
            if (ren.containsKey(ref)) continue;
            byte[] data = src.get(ref);
            if (data == null) throw new Exception("falta o arquivo " + ref);
            String base = ref.substring(Math.max(ref.lastIndexOf('/'), ref.lastIndexOf('\\')) + 1);
            String newName = outName.replaceAll(" ", "_") + "__" + base;
            ren.put(ref, newName);
            b.files.put(newName, data);
        }
        for (Map.Entry<String, String> e : ren.entrySet()) {
            t = t.replace(e.getKey(), e.getValue());
        }
        b.dfx = t;
        return b;
    }

    // ============================ slang -> GLSL ============================

    /** Resolve #include "arquivo" recursivamente. */
    static String resolveIncludes(String text, FileSource src, String relDir, HashSet<String> seen)
            throws Exception {
        Matcher m = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\".*$", Pattern.MULTILINE).matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inc = m.group(1).replace('\\', '/');
            String key = normalizePath(relDir.length() > 0 ? relDir + "/" + inc : inc);
            if (seen.contains(key)) {
                m.appendReplacement(sb, "// include repetido: " + inc);
                continue;
            }
            byte[] data = src.get(key);
            if (data == null) throw new Exception("include nao encontrado: " + inc);
            String inner = new String(data, "UTF-8");
            String subDir = key.contains("/") ? key.substring(0, key.lastIndexOf('/')) : "";
            HashSet<String> subSeen = new HashSet<String>(seen);
            subSeen.add(key);
            String expanded = resolveIncludes(inner, src, subDir, subSeen);
            m.appendReplacement(sb, Matcher.quoteReplacement(expanded));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static final String[] RETRO_UNIFORMS = {
            "SourceSize", "OriginalSize", "OriginalTextureSize", "OutputSize",
            "FinalViewportSize", "NormalizedNativeSize", "FrameTime", "FrameCount",
            "FrameDirection", "TargetFPS", "Rotation", "OriginalAspect"
    };

    static final Pattern P_PARAM = Pattern.compile(
            "^\\s*#pragma\\s+parameter\\s+(\\w+)\\s+\"[^\"]*\"\\s+([0-9.eE+-]+).*$",
            Pattern.MULTILINE);

    static final Pattern P_PUSH_BLOCK = Pattern.compile(
            "layout\\s*\\(\\s*push_constant[^)]*\\)\\s*uniform\\s+\\w+\\s*\\{[^{}]*\\}\\s*(\\w+)\\s*;",
            Pattern.DOTALL);
    static final Pattern P_UBO_BLOCK = Pattern.compile(
            "layout\\s*\\([^)]*std140[^)]*\\)\\s*uniform\\s+\\w+\\s*\\{[^{}]*\\}\\s*(\\w+)\\s*;",
            Pattern.DOTALL);
    static final Pattern P_VK_SAMPLER = Pattern.compile(
            "(?m)^\\s*uniform\\s+sampler2D\\s+(\\w+)\\s*;.*$");
    static final Pattern P_IO_DECL = Pattern.compile(
            "(?m)^\\s*(?:flat\\s+|centroid\\s+)?(in|out)\\s+(vec[234]|float[234])\\s+(\\w+)\\s*;.*$");
    static final Pattern P_IO_BLOCK = Pattern.compile(
            "(?m)^\\s*(?:flat\\s+|centroid\\s+)?(in|out)\\s+\\w+\\s*\\{([^}]*)\\}\\s*(\\w+)\\s*;",
            Pattern.DOTALL);

    /**
     * Prepara um .slang no formato novo (Vulkan GLSL, #version 4xx):
     * tira blocos push_constant/UBO, layouts e mapeia samplers.
     * Devolve o mapa nomeDeSampler -> uniform GLSL do dsd.
     */
    static Map<String, String> vulkanPrep(String text) {
        Map<String, String> vkMap = new LinkedHashMap<String, String>();
        Matcher ms = P_VK_SAMPLER.matcher(text);
        ArrayList<String> names = new ArrayList<String>();
        while (ms.find()) names.add(ms.group(1));
        String input = null;
        for (String n : names) {
            String l = n.toLowerCase();
            if (l.contains("source") || l.equals("texture")) {
                input = n;
                break;
            }
        }
        if (input == null && !names.isEmpty()) input = names.get(0);
        int li = 0;
        for (String n : names) {
            if (n.equals(input)) {
                vkMap.put(n, "u_texture");
                continue;
            }
            String m = null;
            for (Map.Entry<String, String> e : lutSamplerMapStatic.get().entrySet()) {
                if (e.getKey().equalsIgnoreCase(n)) {
                    m = e.getValue();
                    break;
                }
            }
            if (m == null) m = "u_vk_unused" + li;
            vkMap.put(n, m);
            li++;
        }
        return vkMap;
    }

    static final ThreadLocal<Map<String, String>> lutSamplerMapStatic =
            new ThreadLocal<Map<String, String>>() {
                @Override
                protected Map<String, String> initialValue() {
                    return new HashMap<String, String>();
                }
            };

    /**
     * Converte as declaracoes in/out de um estagio Vulkan GLSL:
     * - vertex: in vec4 Position -> constante da quad; in vec2 TexCoord -> a_texture_coordinate;
     *   out -> varying.
     * - fragment: in -> varying; out vec4 FragColor -> gl_FragColor.
     */
    static String vulkanStageIO(String code, boolean isVertex) throws Exception {
        String c = code;
        // blocos de interface (out VertexData { vec2 vTexCoord; } vo;) -> varyings soltos
        Matcher mb = P_IO_BLOCK.matcher(c);
        StringBuffer sbB = new StringBuffer();
        ArrayList<String> instPrefixes = new ArrayList<String>();
        while (mb.find()) {
            String fields = mb.group(2).trim();
            String inst = mb.group(3);
            instPrefixes.add(inst);
            StringBuilder repl = new StringBuilder();
            for (String f : fields.split(";")) {
                String fl = f.trim();
                if (fl.length() == 0) continue;
                repl.append("varying ").append(fl).append(";\n");
            }
            mb.appendReplacement(sbB, Matcher.quoteReplacement(repl.toString()));
        }
        mb.appendTail(sbB);
        c = sbB.toString();
        for (String inst : instPrefixes) {
            c = c.replaceAll("\\b" + inst + "\\s*\\.", "");
        }

        Matcher m = P_IO_DECL.matcher(c);
        StringBuffer sb = new StringBuffer();
        ArrayList<String> posNames = new ArrayList<String>();
        ArrayList<String> uvNames = new ArrayList<String>();
        String fragOut = null;
        while (m.find()) {
            String kind = m.group(1);
            String type = m.group(2);
            String name = m.group(3);
            String repl;
            if (isVertex) {
                if (kind.equals("in")) {
                    if (type.equals("vec4") || type.equals("float4")) {
                        posNames.add(name);
                    } else {
                        uvNames.add(name);
                    }
                    repl = ""; // declaracao some (o wrapper fornece o valor)
                } else {
                    repl = "varying " + type + " " + name + ";";
                }
            } else {
                if (kind.equals("in")) {
                    repl = "varying " + type + " " + name + ";";
                } else {
                    if (fragOut == null) fragOut = name;
                    repl = ""; // saida vira gl_FragColor
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        c = sb.toString();
        if (isVertex) {
            for (String n : posNames) {
                c = c.replaceAll("\\b" + n + "\\b", "vec4(a_vertex_coordinate.xy, 0.0, 1.0)");
            }
            for (String n : uvNames) {
                c = c.replaceAll("\\b" + n + "\\b", "a_texture_coordinate");
            }
        }
        if (fragOut != null) {
            c = c.replaceAll("\\b" + fragOut + "\\b", "gl_FragColor");
        }
        return c;
    }

    static String typeConv(String h) {
        if (h.equals("float")) return "float";
        if (h.equals("int")) return "int";
        if (h.equals("bool")) return "bool";
        if (h.startsWith("float")) return "vec" + h.substring(5);
        if (h.startsWith("int")) return "ivec" + h.substring(3);
        if (h.startsWith("uint")) return "ivec" + h.substring(4);
        if (h.startsWith("half")) return "vec" + (h.length() > 4 ? h.substring(4) : "1");
        return h;
    }

    static String tokenConv(String code) {
        String t = code;
        t = t.replaceAll("(?m)^\\s*static\\s+", "");
        t = t.replaceAll("\\binline\\s+", "");
        t = t.replaceAll("\\bfloat4x4\\b", "mat4");
        t = t.replaceAll("\\bfloat3x3\\b", "mat3");
        t = t.replaceAll("\\bfloat2x2\\b", "mat2");
        t = t.replaceAll("\\bfloat4\\b", "vec4");
        t = t.replaceAll("\\bfloat3\\b", "vec3");
        t = t.replaceAll("\\bfloat2\\b", "vec2");
        t = t.replaceAll("\\bhalf4\\b", "vec4");
        t = t.replaceAll("\\bhalf3\\b", "vec3");
        t = t.replaceAll("\\bhalf2\\b", "vec2");
        t = t.replaceAll("\\bhalf\\b", "float");
        t = t.replaceAll("\\buint\\b", "int");
        t = t.replaceAll("\\bint2\\b", "ivec2");
        t = t.replaceAll("\\bint3\\b", "ivec3");
        t = t.replaceAll("\\bint4\\b", "ivec4");
        t = t.replaceAll("\\blerp\\s*\\(", "mix(");
        t = t.replaceAll("\\bfrac\\s*\\(", "fract(");
        t = t.replaceAll("\\brcp\\s*\\(", "(1.0 /");
        t = t.replaceAll("\\brsqrt\\s*\\(", "inversesqrt(");
        t = t.replaceAll("\\bddx\\s*\\(", "dFdx(");
        t = t.replaceAll("\\bddy_coarse\\s*\\(", "dFdy(");
        t = t.replaceAll("\\bddy\\s*\\(", "dFdy(");
        t = t.replaceAll("\\bfmod\\s*\\(", "mod(");
        t = t.replaceAll("\\batan2\\s*\\(", "atan(");
        t = t.replaceAll("\\btex2D\\s*\\(", "texture2D(");
        t = t.replaceAll("\\bmul\\s*\\(\\s*([^,()]+?)\\s*,\\s*([^,()]+?)\\s*\\)", "($1 * $2)");
        return t;
    }

    static final String HELPERS =
            "float saturate(float x){ return clamp(x, 0.0, 1.0); }\n" +
            "vec2 saturate(vec2 x){ return clamp(x, 0.0, 1.0); }\n" +
            "vec3 saturate(vec3 x){ return clamp(x, 0.0, 1.0); }\n" +
            "vec4 saturate(vec4 x){ return clamp(x, 0.0, 1.0); }\n" +
            "float round(float x){ return floor(x + 0.5); }\n" +
            "vec2 round(vec2 x){ return floor(x + 0.5); }\n" +
            "vec3 round(vec3 x){ return floor(x + 0.5); }\n" +
            "vec4 round(vec4 x){ return floor(x + 0.5); }\n" +
            "float trunc(float x){ return x >= 0.0 ? floor(x) : ceil(x); }\n" +
            "vec2 trunc(vec2 x){ return vec2(trunc(x.x), trunc(x.y)); }\n" +
            "vec3 trunc(vec3 x){ return vec3(trunc(x.x), trunc(x.y), trunc(x.z)); }\n" +
            "vec4 trunc(vec4 x){ return vec4(trunc(x.x), trunc(x.y), trunc(x.z), trunc(x.w)); }\n" +
            "void clip(float x){ if (x < 0.0) discard; }\n";

    static final String RETRO_FNS =
            "vec4 SourceSize() { return vec4(u_texture_size.zw, u_texture_size.xy); }\n" +
            "vec4 OriginalSize() { return vec4(u_texture_size.zw, u_texture_size.xy); }\n" +
            "vec4 OriginalTextureSize() { return vec4(u_texture_size.zw, u_texture_size.xy); }\n" +
            "vec2 OutputSize() { return u_target_size; }\n" +
            "vec2 FinalViewportSize() { return u_target_size; }\n" +
            "vec2 NormalizedNativeSize() { return vec2(1.0) / u_texture_size.zw; }\n" +
            "float FrameTime() { return u_time; }\n" +
            "float FrameCount() { return u_time * 60.0; }\n" +
            "float FrameDirection() { return 1.0; }\n" +
            "float TargetFPS() { return 60.0; }\n" +
            "float Rotation() { return 0.0; }\n" +
            "float OriginalAspect() { return u_texture_size.z / u_texture_size.w; }\n";

    static final String COMMON_UNIFORMS =
            "uniform vec4 u_texture_size;\nuniform vec2 u_target_size;\nuniform float u_time;\n";

    /**
     * Converte o codigo de um estagio slang para GLSL com um main() novo.
     * isVertex: etapa de vertices. allowedTex: maior textureK que o passe pode amostrar.
     */
    static String wrapStage(String code, boolean isVertex, String fnTag, int allowedTex)
            throws Exception {
        String c = code.replaceAll("(?m)^\\s*void\\s+main\\s*\\(\\s*\\)\\s*\\{",
                "void alpha_old_main() {");
        Pattern fn = Pattern.compile(
                "(?:void|vec2|vec3|vec4)\\s+(\\w+)\\s*\\(([^;{]*?)\\)\\s*(?::\\s*\\w+\\s*)?\\{",
                Pattern.DOTALL);
        // deteccao em camadas: semantica correta > semantica parcial > nome
        String entry = null, sig = null;
        int entryPos = -1;
        for (int tier = 1; tier <= 3 && entry == null; tier++) {
            Matcher m = fn.matcher(c);
            while (m.find()) {
                String params = m.group(2);
                boolean pos = params.matches("(?s).*\\b(SV_Position|POSITION)\\b.*");
                boolean uv0 = params.matches("(?s).*\\bTEXCOORD0\\b.*");
                boolean tgt = params.matches("(?s).*\\bSV_Target0?\\b.*")
                        || c.substring(m.end()).trim().startsWith(": SV_Target")
                        || c.substring(m.end()).trim().startsWith(":SV_Target");
                String nm = m.group(1).toLowerCase();
                boolean ok;
                if (isVertex) {
                    if (tier == 1) ok = pos;
                    else if (tier == 2) ok = nm.contains("vertex");
                    else ok = pos || nm.contains("vertex");
                } else {
                    if (tier == 1) ok = (uv0 && (tgt || nm.contains("main") || nm.contains("fragment")));
                    else if (tier == 2) ok = uv0 || tgt;
                    else ok = tgt || nm.contains("fragment") || nm.contains("main");
                }
                if (ok) {
                    entry = m.group(1);
                    sig = params;
                    entryPos = m.start();
                    break;
                }
            }
        }
        if (entry == null) {
            throw new Exception("funcao de entrada (" + (isVertex ? "vertex" : "fragment")
                    + ") nao encontrada");
        }
        int bodyStart = c.indexOf('{', entryPos);
        int depth = 0;
        int bodyEnd = -1;
        for (int i = bodyStart; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    bodyEnd = i + 1;
                    break;
                }
            }
        }
        if (bodyEnd < 0) throw new Exception("corpo da funcao de entrada incompleto");
        String head = c.substring(0, entryPos);
        String tail = c.substring(bodyEnd);
        String sigGlsl = sig.replaceAll(":\\s*(SV_\\w+|TEXCOORD\\d*|POSITION|COLOR\\d*|NORMAL\\d*|WPOS|VPOS|VFACE)", "");
        String paramsGlsl = sigGlsl.substring(sigGlsl.indexOf('(') + 1, sigGlsl.lastIndexOf(')')).trim();
        String[] params = paramsGlsl.length() == 0 ? new String[0] : paramsGlsl.split(",");

        StringBuilder decl = new StringBuilder();
        StringBuilder call = new StringBuilder();
        String outVar = null;
        String posVar = null;
        String uvVar = null;
        int usedTex = -1;
        for (int i = 0; i < params.length; i++) {
            String p = params[i].trim();
            if (p.length() == 0) continue;
            String[] toks = p.trim().split("\\s+");
            String kind = "in";
            for (String tk : toks) {
                if (tk.equals("in") || tk.equals("out") || tk.equals("inout")) kind = tk;
            }
            String type = typeConv(toks[0]);
            String name = toks[toks.length - 1];
            String sem = semanticOf(params[i]);
            boolean isPos = sem != null && (sem.equals("SV_Position") || sem.equals("POSITION"));
            boolean isUv = sem != null && sem.startsWith("TEXCOORD");
            String init;
            if (isVertex) {
                if (isPos) {
                    init = "vec4(a_vertex_coordinate.xy, 0.0, 1.0)";
                    posVar = name;
                } else if (isUv) {
                    init = "a_texture_coordinate";
                    uvVar = name;
                } else if (i == 0) {
                    init = "vec4(a_vertex_coordinate.xy, 0.0, 1.0)";
                    posVar = name;
                } else if (i == 1) {
                    init = "a_texture_coordinate";
                    uvVar = name;
                } else {
                    init = zeroOf(type);
                }
            } else {
                if (isPos) {
                    init = "vec4(gl_FragCoord.xy, 0.0, 1.0)";
                } else if (isUv) {
                    init = "v_texture_coordinate";
                    if ("TEXCOORD0".equals(sem)) uvVar = name;
                } else if (i == 0 && type.equals("vec4")) {
                    init = "vec4(gl_FragCoord.xy, 0.0, 1.0)";
                } else if (i <= 1 && type.equals("vec2")) {
                    init = "v_texture_coordinate";
                    if (uvVar == null) uvVar = name;
                } else {
                    init = zeroOf(type);
                }
                if (kind.equals("out") && (sem != null && sem.startsWith("SV_Target"))) {
                    init = "vec4(0.0)";
                    outVar = name;
                }
                if (kind.equals("out") && outVar == null && type.equals("vec4") && sem == null
                        && i == params.length - 1) {
                    init = "vec4(0.0)";
                    outVar = name;
                }
            }
            if (kind.equals("out") && isVertex && isPos) init = "vec4(0.0)";
            decl.append("  ").append(type).append(" ").append(name)
                    .append(" = ").append(init).append(";\n");
            call.append(name);
            if (i < params.length - 1) call.append(", ");
        }
        if (!isVertex) {
            // texturas extras amostradas no fragmento (u_tex1..u_tex7)
            for (int k = 1; k <= 7; k++) {
                if (Pattern.compile("\\bu_tex" + k + "\\b").matcher(c).find()) usedTex = k;
            }
        }
        if (usedTex > allowedTex) {
            throw new Exception("usa texture" + usedTex + " (passe multi-textura nao suportado aqui)");
        }
        String fnName = "alpha_entry_" + fnTag;
        StringBuilder w = new StringBuilder();
        w.append(head);
        w.append(fnName).append("(").append(paramsGlsl.isEmpty() ? "" : paramsGlsl).append(") {")
                .append(c.substring(c.indexOf('{', entryPos) + 1, bodyEnd - 1)).append("}\n");
        w.append(tail).append("\nvoid main() {\n").append(decl);
        if (outVar != null && !isVertex) {
            w.append("  ").append(fnName).append("(").append(call).append(");\n");
            w.append("  gl_FragColor = ").append(outVar).append(";\n");
        } else if (isVertex) {
            // vertex: executa e usa os parametros inout/out
            w.append("  ").append(fnName).append("(").append(call).append(");\n");
            w.append("  gl_Position = ").append(posVar != null ? posVar : "vec4(0.0, 0.0, 0.0, 1.0)")
                    .append(";\n");
            w.append("  v_texture_coordinate = ")
                    .append(uvVar != null ? uvVar : "a_texture_coordinate").append(";\n");
        } else {
            w.append("  gl_FragColor = ").append(fnName).append("(").append(call).append(");\n");
        }
        w.append("}\n");
        return w.toString();
    }

    private static String semanticOf(String param) {
        Matcher m = Pattern.compile(":\\s*(\\w+)\\s*$").matcher(param.trim());
        return m.find() ? m.group(1) : null;
    }

    private static String zeroOf(String type) {
        if (type.startsWith("vec")) return type + "(0.0)";
        if (type.startsWith("ivec")) return type + "(0)";
        if (type.startsWith("mat")) return type + "(1.0)";
        return "0";
    }

    /** Preambulo + estagios -> conteudo do .dsd. */
    static String slangToDsd(String text, String fnTag, int passIdx, int allowedTex,
                             Map<String, String> lutSamplerMap) throws Exception {
        // parametros #pragma parameter viram constantes com o valor padrao
        Map<String, String> paramDef = new LinkedHashMap<String, String>();
        Matcher mp = P_PARAM.matcher(text);
        while (mp.find()) paramDef.put(mp.group(1), mp.group(2));
        text = P_PARAM.matcher(text).replaceAll("");
        for (Map.Entry<String, String> e : paramDef.entrySet()) {
            text = text.replaceAll("(?m)^\\s*uniform\\s+(float\\d?|half\\d?)\\s+" + e.getKey()
                    + "\\s*;.*$", "const float " + e.getKey() + " = " + e.getValue() + ";");
        }
        // uniforms do RetroArch viram funcoes
        for (String u : RETRO_UNIFORMS) {
            text = text.replaceAll("(?m)^\\s*uniform\\s+\\w+\\s+" + u + "\\s*;.*$", "");
            text = text.replaceAll("(?m)^\\s*uniform\\s+\\w+\\s+" + u + "\\s*=.*$", "");
        }
        // declarações de textura/sampler saem (viram uniforms do dsd)
        text = text.replaceAll("(?m)^\\s*(?:Texture2D|UniformTexture|sampler2D)\\s+(\\w+)\\s*(?::[^;]*)?;.*$", "");
        text = text.replaceAll("(?m)^\\s*SamplerState\\s+(\\w+)\\s*(?::[^;]*)?;.*$", "");
        text = text.replaceAll("(?m)^\\s*#pragma\\s+(name|format|order|target)\\b.*$", "");
        text = text.replaceAll("(?m)^\\s*#version\\b.*$", "");
        text = text.replaceAll("(?m)^\\s*cbuffer\\s+\\w+[^\\{\\n]*\\{\\s*$", "");
        text = text.replaceAll("(?m)^\\s*\\};\\s*$", "");
        text = text.replaceAll(":\\s*register\\s*\\([^)]*\\)", "");

        // formato Vulkan GLSL (#version 4xx): blocos e layouts
        boolean vulkan = text.contains("layout(") || text.contains("layout (");
        Map<String, String> vkMap = new LinkedHashMap<String, String>();
        if (vulkan) {
            lutSamplerMapStatic.set(lutSamplerMap);
            Matcher mp2 = P_PUSH_BLOCK.matcher(text);
            while (mp2.find()) {
                String prefix = mp2.group(1);
                text = mp2.replaceFirst("").toString();
                text = text.replaceAll("\\b" + prefix + "\\s*\\.", "");
                mp2 = P_PUSH_BLOCK.matcher(text);
            }
            Matcher mu = P_UBO_BLOCK.matcher(text);
            while (mu.find()) {
                String prefix = mu.group(1);
                text = mu.replaceFirst("").toString();
                text = text.replaceAll("\\b" + prefix + "\\s*\\.", "");
                mu = P_UBO_BLOCK.matcher(text);
            }
            text = text.replaceAll("\\bgl_PerVertex\\s*\\{[^}]*\\}\\s*;", "");
            vkMap = vulkanPrep(text);
            text = text.replaceAll("layout\\s*\\([^)]*\\)\\s*", "");
        }

        // separa estagios
        String vertex, fragment, preamble;
        int vi = text.indexOf("#pragma stage vertex");
        int fi = text.indexOf("#pragma stage fragment");
        if (vi < 0 && fi < 0) throw new Exception("sem '#pragma stage vertex/fragment'");
        if (vi >= 0 && (fi < 0 || vi < fi)) {
            preamble = text.substring(0, vi);
            vertex = text.substring(vi + "#pragma stage vertex".length(),
                    fi > vi ? fi : text.length());
            fragment = fi > vi ? text.substring(fi + "#pragma stage fragment".length()) : "";
        } else {
            preamble = text.substring(0, fi);
            fragment = text.substring(fi + "#pragma stage fragment".length());
            vertex = vi > fi ? text.substring(vi + "#pragma stage vertex".length()) : "";
        }

        if (vulkan) {
            vertex = vulkanStageIO(vertex, true);
            fragment = vulkanStageIO(fragment, false);
            for (Map.Entry<String, String> e : vkMap.entrySet()) {
                vertex = vertex.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
                fragment = fragment.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
                preamble = preamble.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
            }
            vertex = vertex.replaceAll("\\btexture\\s*\\(", "texture2D(");
            fragment = fragment.replaceAll("\\btexture\\s*\\(", "texture2D(");
            if (vertex.contains("u_vk_unused") || fragment.contains("u_vk_unused")
                    || preamble.contains("u_vk_unused")) {
                throw new Exception("usa texturas extras sem LUT declarada no preset");
            }
        }

        // reescreve amostragens <tex>.Sample(estado, uv)
        Map<String, String> texMap = new LinkedHashMap<String, String>();
        texMap.put("texture0", "u_texture");
        for (int k = 1; k < 8; k++) texMap.put("texture" + k, "u_tex" + k);
        for (Map.Entry<String, String> e : lutSamplerMap.entrySet()) {
            texMap.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : texMap.entrySet()) {
            vertex = vertex.replaceAll(
                    "\\b" + e.getKey() + "\\s*\\.\\s*Sample(?:Level)?\\s*\\(\\s*\\w+\\s*,",
                    "texture2D(" + e.getValue() + ",");
            fragment = fragment.replaceAll(
                    "\\b" + e.getKey() + "\\s*\\.\\s*Sample(?:Level)?\\s*\\(\\s*\\w+\\s*,",
                    "texture2D(" + e.getValue() + ",");
        }
        if (vertex.contains(".Sample") || fragment.contains(".Sample")) {
            throw new Exception("amostragem nao suportada (.Sample)");
        }
        // uniforms retro viram chamadas de funcao
        for (String u : RETRO_UNIFORMS) {
            vertex = vertex.replaceAll("\\b" + u + "\\b", u + "()");
            fragment = fragment.replaceAll("\\b" + u + "\\b", u + "()");
            preamble = preamble.replaceAll("\\b" + u + "\\b", u + "()");
        }
        // conversao de tipos e funcoes
        preamble = tokenConv(preamble);
        vertex = tokenConv(vertex);
        fragment = tokenConv(fragment);
        // entry points
        String vWrap, fWrap;
        if (vulkan) {
            // o proprio codigo ja tem main() (Vulkan GLSL)
            vWrap = vertex;
            fWrap = fragment;
            for (int k = 1; k <= 7; k++) {
                if (Pattern.compile("\\bu_tex" + k + "\\b").matcher(fWrap).find()
                        && k > allowedTex) {
                    throw new Exception("usa texture" + k
                            + " (passe multi-textura nao suportado aqui)");
                }
            }
        } else {
            vWrap = wrapStage(vertex, true, sanitizeTag(fnTag) + "_v", allowedTex);
            fWrap = wrapStage(fragment, false, sanitizeTag(fnTag) + "_f", allowedTex);
        }
        // samplers usados
        TreeSet<String> samplers = new TreeSet<String>();
        if (vWrap.contains("u_texture") || fWrap.contains("u_texture")
                || vertex.contains("u_texture") || fragment.contains("u_texture")) {
            samplers.add("u_texture");
        }
        for (int k = 1; k < 8; k++) {
            String n = "u_tex" + k;
            if (vWrap.contains(n) || fWrap.contains(n) || vertex.contains(n) || fragment.contains(n)) {
                samplers.add(n);
            }
        }
        for (String s : lutSamplerMap.values()) {
            if (vWrap.contains(s) || fWrap.contains(s) || vertex.contains(s) || fragment.contains(s)) {
                samplers.add(s);
            }
        }
        StringBuilder sbSamplers = new StringBuilder();
        for (String s : samplers) sbSamplers.append("uniform sampler2D ").append(s).append(";\n");
        boolean vMvp = Pattern.compile("\\bMVP\\b").matcher(vWrap).find()
                && !Pattern.compile("(uniform|const)\\s+mat4\\s+MVP").matcher(vWrap).find();
        boolean fMvp = Pattern.compile("\\bMVP\\b").matcher(fWrap).find()
                && !Pattern.compile("(uniform|const)\\s+mat4\\s+MVP").matcher(fWrap).find();
        StringBuilder v = new StringBuilder();
        v.append("<vertex>\n");
        v.append("attribute vec2 a_vertex_coordinate;\n");
        v.append("attribute vec2 a_texture_coordinate;\n");
        v.append("varying vec2 v_texture_coordinate;\n");
        v.append(COMMON_UNIFORMS).append(sbSamplers);
        if (vMvp) v.append("const mat4 MVP = mat4(1.0);\n");
        v.append(preamble.trim()).append("\n").append(HELPERS).append("\n")
                .append(RETRO_FNS).append("\n").append(vWrap).append("\n");
        v.append("</vertex>\n");
        StringBuilder fr = new StringBuilder();
        fr.append("<fragment>\n");
        fr.append("#if GL_ES\n#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n");
        fr.append("#else\nprecision mediump float;\n#endif\n#endif\n");
        fr.append("varying vec2 v_texture_coordinate;\n");
        fr.append(COMMON_UNIFORMS).append(sbSamplers);
        if (fMvp) fr.append("const mat4 MVP = mat4(1.0);\n");
        fr.append(preamble.trim()).append("\n").append(HELPERS).append("\n")
                .append(RETRO_FNS).append("\n").append(fWrap).append("\n");
        fr.append("</fragment>\n");
        return v.append("\n").append(fr).toString();
    }

    /** Converte uma .slang de passe unico (dfx + dsd). */
    static Build convertSlangSingle(String text, FileSource src, String outName, String relDir)
            throws Exception {
        HashSet<String> seen = new HashSet<String>();
        String t = resolveIncludes(text, src, relDir == null ? "" : relDir, seen);
        Map<String, String> lutSamplers = new LinkedHashMap<String, String>();
        String dsd = slangToDsd(t, sanitizeTag(outName), 0, 0, lutSamplers);
        Build b = new Build();
        b.files.put(dsdName(outName, 0), dsd.getBytes("UTF-8"));
        b.dfx = singlePassDfx(outName, dsdName(outName, 0));
        return b;
    }

    static String sanitizeTag(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "_");
    }

    static String singlePassDfx(String outName, String dsdFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Convertido por AlphaDS\n");
        sb.append("<options>\nname=").append(optName(outName));
        sb.append("\ntextures=1\n</options>\n");
        sb.append("\n<fheader>\n#if GL_ES\n#ifdef GL_FRAGMENT_PRECISION_HIGH\n");
        sb.append("precision highp float;\n#else\nprecision mediump float;\n#endif\n#endif\n</fheader>\n");
        sb.append("\n<texture:0>\ninput=framebuffer\nmin_filter=GL_LINEAR\nmag_filter=GL_LINEAR\n</texture>\n");
        sb.append("\n<pass>\nshader=").append(dsdFile);
        sb.append("\nsampler:u_texture=0\n</pass>\n");
        return sb.toString();
    }

    static String optName(String s) {
        String n = s.trim();
        if (n.length() > 31) n = n.substring(0, 31);
        return n.replace("\n", " ");
    }

    // ============================ presets multi-passe ============================

    static Map<String, String> parsePreset(String text) {
        Map<String, String> kv = new LinkedHashMap<String, String>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String l = line.trim();
            if (l.length() == 0 || l.startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            String v = l.substring(eq + 1).trim();
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                v = v.substring(1, v.length() - 1).trim();
            }
            kv.put(l.substring(0, eq).trim(), v);
        }
        return kv;
    }

    /** Converte um .slangp (cadeia multi-passe) para Build com dfx + dsds + LUTs. */
    static Build convertPreset(String text, FileSource src, String outName, String presetDir)
            throws Exception {
        Map<String, String> kv = parsePreset(text);
        int n;
        try {
            n = Integer.parseInt(kv.get("shaders"));
        } catch (Exception e) {
            throw new Exception("preset sem 'shaders = N'");
        }
        if (n < 1 || n > 8) throw new Exception("numero de passes nao suportado: " + n);
        // LUTs declaradas
        Map<String, String> lutFiles = new LinkedHashMap<String, String>();
        String texList = kv.get("textures");
        if (texList != null && texList.length() > 0) {
            for (String k : texList.split(";")) {
                String key = k.trim();
                if (key.length() == 0) continue;
                String path = kv.get(key);
                if (path == null) throw new Exception("textura " + key + " sem caminho");
                lutFiles.put(key, path);
            }
        }
        StringBuilder dfx = new StringBuilder();
        dfx.append("// Convertido por AlphaDS de um preset libretro\n");
        dfx.append("<options>\nname=").append(optName(outName));
        dfx.append("\ntextures=").append(n + lutFiles.size()).append("\n</options>\n");
        dfx.append("\n<fheader>\n#if GL_ES\n#ifdef GL_FRAGMENT_PRECISION_HIGH\n");
        dfx.append("precision highp float;\n#else\nprecision mediump float;\n#endif\n#endif\n</fheader>\n");
        // textura 0 = framebuffer
        String filt0 = truthy(kv.get("filter_linear0")) ? "GL_LINEAR" : "GL_NEAREST";
        dfx.append("\n<texture:0>\ninput=framebuffer\nmin_filter=").append(filt0);
        dfx.append("\nmag_filter=").append(filt0).append("\n</texture>\n");
        // FBOs intermediarios (indice i = saida do passe i-1)
        for (int i = 1; i < n; i++) {
            String fi = truthy(kv.get("filter_linear" + i)) ? "GL_LINEAR" : "GL_NEAREST";
            dfx.append("\n<texture:").append(i).append(">\ninput=null\nmin_filter=").append(fi);
            dfx.append("\nmag_filter=").append(fi).append("\n</texture>\n");
        }
        // LUTs -> texturas com arquivo .raw
        Map<String, Integer> lutTexIndex = new LinkedHashMap<String, Integer>();
        Map<String, String> lutSamplerMap = new LinkedHashMap<String, String>();
        int lutIdx = 0;
        int texIdx = n;
        Build acc = new Build();
        for (Map.Entry<String, String> e : lutFiles.entrySet()) {
            String key = e.getKey();
            String path = e.getValue();
            String rel = normalizePath(presetDir.length() > 0 ? presetDir + "/" + path : path);
            byte[] data = src.get(rel);
            if (data == null) throw new Exception("textura nao encontrada: " + path);
            String linear = truthy(kv.get(key + "_linear")) ? "GL_LINEAR" : "GL_NEAREST";
            if (path.toLowerCase().endsWith(".png") || path.toLowerCase().endsWith(".jpg")
                    || path.toLowerCase().endsWith(".jpeg")) {
                Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bm == null) throw new Exception("textura png invalida: " + path);
                int w = bm.getWidth(), h = bm.getHeight();
                int[] px = new int[w * h];
                bm.getPixels(px, 0, w, 0, 0, w, h);
                byte[] raw = new byte[w * h * 4];
                for (int i = 0; i < px.length; i++) {
                    raw[i * 4] = (byte) ((px[i] >> 16) & 0xFF);
                    raw[i * 4 + 1] = (byte) ((px[i] >> 8) & 0xFF);
                    raw[i * 4 + 2] = (byte) (px[i] & 0xFF);
                    raw[i * 4 + 3] = (byte) ((px[i] >> 24) & 0xFF);
                }
                String rawName = sanitizeTag(outName) + "_lut" + lutIdx + ".raw";
                dfx.append("\n<texture:").append(texIdx).append(">\ninput=").append(rawName);
                dfx.append("\nwidth=").append(w).append("\nheight=").append(h);
                dfx.append("\nformat=GL_RGBA\ninternalformat=GL_RGBA\ntype=GL_UNSIGNED_BYTE");
                dfx.append("\nmin_filter=").append(linear).append("\nmag_filter=").append(linear);
                dfx.append("\n</texture>\n");
                acc.files.put(rawName, raw);
                bm.recycle();
            } else {
                throw new Exception("textura nao-suportada (so png/jpg): " + path);
            }
            lutTexIndex.put(key, texIdx);
            lutSamplerMap.put(key, "u_lut" + lutIdx);
            lutIdx++;
            texIdx++;
        }
        // passes
        for (int i = 0; i < n; i++) {
            String shPath = kv.get("shader" + i);
            if (shPath == null) throw new Exception("passe " + i + " sem shader" + i);
            String rel = normalizePath(presetDir.length() > 0 ? presetDir + "/" + shPath : shPath);
            byte[] shData = src.get(rel);
            if (shData == null) throw new Exception("shader do passe " + i + " nao encontrada: " + shPath);
            String shText = new String(shData, "UTF-8");
            HashSet<String> seen = new HashSet<String>();
            shText = resolveIncludes(shText, src, relDirOf(rel), seen);
            String dsd = slangToDsd(shText, sanitizeTag(outName) + "_p" + i, i, i - 1, lutSamplerMap);
            acc.files.put(dsdName(outName, i), dsd.getBytes("UTF-8"));
            dfx.append("\n<pass>\nshader=").append(dsdName(outName, i));
            dfx.append("\nsampler:u_texture=").append(i);
            for (Map.Entry<String, Integer> e : lutTexIndex.entrySet()) {
                dfx.append("\nsampler:").append(lutSamplerMap.get(e.getKey()))
                        .append("=").append(e.getValue());
            }
            if (i < n - 1) {
                dfx.append("\noutput=").append(i + 1).append(":").append(passScale(kv, i));
            }
            dfx.append("\n</pass>\n");
        }
        acc.dfx = dfx.toString();
        return acc;
    }

    private static int passScale(Map<String, String> kv, int i) {
        String st = kv.get("scale_type" + i);
        if (st != null && (st.equals("viewport") || st.equals("absolute"))) return 1;
        float sx = parseFloat(kv.get("scale_x" + i), 1f);
        float sy = parseFloat(kv.get("scale_y" + i), 1f);
        int s = (int) Math.ceil(Math.max(sx, sy));
        return Math.max(1, s);
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean truthy(String s) {
        return s != null && s.toLowerCase().equals("true");
    }

    private static String relDirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "";
    }

    // ============================ .glsl (melhor esforco) ============================

    static Build convertGlsl(String text, FileSource src, String outName) throws Exception {
        HashSet<String> seen = new HashSet<String>();
        String t = resolveIncludes(text, src, "", seen);
        String vertex = "";
        String fragment = t;
        int vi = t.indexOf("#pragma stage vertex");
        int fi = t.indexOf("#pragma stage fragment");
        if (vi >= 0 && fi >= 0) {
            if (vi < fi) {
                vertex = t.substring(vi + "#pragma stage vertex".length(), fi);
                fragment = t.substring(fi + "#pragma stage fragment".length());
            } else {
                fragment = t.substring(fi + "#pragma stage fragment".length(), vi);
                vertex = t.substring(vi + "#pragma stage vertex".length());
            }
        }
        fragment = tokenConv(fragment);
        vertex = tokenConv(vertex);
        fragment = fragment.replaceAll("\\bCOMPAT_TEXTURE\\s*\\(", "texture2D(");
        vertex = vertex.replaceAll("\\bCOMPAT_TEXTURE\\s*\\(", "texture2D(");
        if (!fragment.contains("gl_FragColor")) {
            throw new Exception(".glsl sem gl_FragColor (formato nao reconhecido)");
        }
        // nomes comuns do formato libretro .glsl
        vertex = vertex
                .replaceAll("\\battribute\\s+vec4\\s+VertexCoord\\s*;", "")
                .replaceAll("\\battribute\\s+vec2\\s+TEXCoord\\s*;", "")
                .replaceAll("\\bVertexCoord\\.xy\\b", "a_vertex_coordinate.xy")
                .replaceAll("\\bgl_Position\\s*=\\s*MVPMatrix\\s*\\*\\s*VertexCoord\\s*;",
                        "gl_Position = vec4(a_vertex_coordinate.xy, 0.0, 1.0);")
                .replaceAll("\\bgl_Position\\s*=\\s*MVPMatrix\\s*\\*\\s*a_vertex_position\\s*;",
                        "gl_Position = vec4(a_vertex_coordinate.xy, 0.0, 1.0);")
                .replaceAll("\\bTEXCoord\\b(?=\\s*=)", "a_texture_coordinate")
                .replaceAll("(?m)^\\s*uniform\\s+mat4\\s+\\w*(MVP|Mvp|mvp)\\w*\\s*;.*$",
                        "const mat4 MVPMatrix = mat4(1.0);");
        vertex = vertex.replaceFirst("(?m)^\\s*(uniform\\s+)?mat4\\s+MVPMatrix\\s*;",
                "const mat4 MVPMatrix = mat4(1.0);");
        fragment = fragment
                .replaceAll("\\bTEXCoord\\b", "v_texture_coordinate")
                .replaceAll("\\bTexCoord\\b", "v_texture_coordinate")
                .replaceAll("\\bvTexCoord\\b", "v_texture_coordinate")
                .replaceAll("\\bv_tex\\b", "v_texture_coordinate");
        fragment = fragment.replaceFirst("\\buniform\\s+sampler2D\\s+texture0\\s*;",
                "uniform sampler2D u_texture;");
        fragment = fragment.replaceAll("\\btexture0\\b", "u_texture");
        Build b = new Build();
        StringBuilder v = new StringBuilder();
        v.append("<vertex>\n");
        v.append("attribute vec2 a_vertex_coordinate;\n");
        v.append("attribute vec2 a_texture_coordinate;\n");
        v.append("varying vec2 v_texture_coordinate;\n");
        v.append(COMMON_UNIFORMS);
        v.append(vertex.trim()).append("\n</vertex>\n");
        StringBuilder fr = new StringBuilder();
        fr.append("<fragment>\n");
        fr.append("#if GL_ES\n#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n");
        fr.append("#else\nprecision mediump float;\n#endif\n#endif\n");
        fr.append("varying vec2 v_texture_coordinate;\n");
        fr.append(COMMON_UNIFORMS).append("uniform sampler2D u_texture;\n");
        fr.append(fragment.trim()).append("\n</fragment>\n");
        b.files.put(dsdName(outName, 0), (v + "\n" + fr).getBytes("UTF-8"));
        b.dfx = singlePassDfx(outName, dsdName(outName, 0));
        return b;
    }

    // ============================ download de todas ============================

    static final String REPO_ZIP =
            "https://github.com/libretro/slang-shaders/archive/refs/heads/master.zip";

    public interface Result {
        void done(String summary);
    }

    /** Progresso do download (chamado na thread principal). percent < 0 = indeterminado. */
    public interface Progress {
        void update(String label, int percent);
    }

    private static void prog(final Progress p, final String label, final int percent) {
        if (p == null) return;
        ui(new Runnable() {
            public void run() {
                p.update(label, percent);
            }
        });
    }

    /**
     * Baixa o repositorio libretro/slang-shaders e converte/instala o que der.
     * Chama cb.done(resumo) na thread principal.
     */
    public static void downloadAllShaders(Context ctx, Uri tree, Result cb) {
        downloadAllShaders(ctx, tree, null, cb);
    }

    public static void downloadAllShaders(final Context ctx, final Uri tree,
                                          final Progress pg, final Result cb) {
        new Thread(new Runnable() {
            public void run() {
                String summary;
                File tmpZip = new File(ctx.getCacheDir(), "slang_shaders.zip");
                File tmpDir = new File(ctx.getCacheDir(), "slang_shaders");
                try {
                    if (pg == null) toast(ctx, "Baixando shaders do GitHub...");
                    prog(pg, "Baixando shaders do GitHub...", 0);
                    download(tmpZip, pg);
                    if (pg == null) toast(ctx, "Extraindo...");
                    prog(pg, "Extraindo...", -1);
                    rmrf(tmpDir);
                    tmpDir.mkdirs();
                    unzip(tmpZip, tmpDir);
                    File root = findFirstDir(tmpDir);
                    if (root == null) throw new Exception("zip estranho");
                    summary = convertRepo(ctx, tree, root, pg);
                } catch (Throwable e) {
                    summary = "Falhou: " + e;
                } finally {
                    rmrf(tmpDir);
                    tmpZip.delete();
                }
                final String s = summary;
                ui(new Runnable() {
                    public void run() {
                        cb.done(s);
                    }
                });
            }
        }).start();
    }

    private static void toast(final Context ctx, final String msg) {
        ui(new Runnable() {
            public void run() {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void ui(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private static void download(File dest, Progress pg) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(REPO_ZIP).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        long total = conn.getContentLength();
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[32768];
        int n;
        long got = 0, last = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            got += n;
            long now = System.currentTimeMillis();
            if (pg != null && now - last > 150) {
                last = now;
                String mb = String.format(java.util.Locale.US, "%.1f MB", got / 1048576f);
                if (total > 0) {
                    int pc = (int) (got * 100L / total);
                    prog(pg, "Baixando... " + mb + " (" + pc + "%)", pc);
                } else {
                    prog(pg, "Baixando... " + mb, -1);
                }
            }
        }
        out.close();
        in.close();
        if (dest.length() < 1024) throw new Exception("download vazio");
    }

    private static void unzip(File zip, File destDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zip));
        byte[] buf = new byte[32768];
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            File out = new File(destDir, e.getName());
            if (!out.getCanonicalPath().startsWith(destDir.getCanonicalPath())) continue;
            if (e.isDirectory()) {
                out.mkdirs();
                continue;
            }
            out.getParentFile().mkdirs();
            FileOutputStream fo = new FileOutputStream(out);
            int n;
            while ((n = zis.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
        }
        zis.close();
    }

    private static File findFirstDir(File parent) {
        File[] fs = parent.listFiles();
        if (fs == null) return null;
        for (File f : fs) if (f.isDirectory()) return f;
        return null;
    }

    private static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) rmrf(c);
        f.delete();
    }

    /** Converte e instala as presets/shaders de uma pasta extraida. */
    static String convertRepo(Context ctx, Uri tree, File rootDir, Progress pg) {
        Uri dir = shadersDir(ctx, tree);
        if (dir == null) return "Nao achei a pasta de shaders";
        ensureBuiltins(ctx, tree);
        Uri probe = writeFile(ctx, dir, ".alphads_probe", new byte[]{1});
        if (probe == null) {
            return "Sem permissao de escrita na pasta de shaders. Escolha a pasta de novo no menu de shaders.";
        }
        try {
            DocumentsContract.deleteDocument(ctx.getContentResolver(), probe);
        } catch (Exception ignored) {
        }
        List<File> presets = new ArrayList<File>();
        List<File> singles = new ArrayList<File>();
        collect(rootDir, presets, singles);
        Collections.sort(presets);
        Collections.sort(singles);
        HashSet<String> referenced = new HashSet<String>();
        HashSet<String> used = new HashSet<String>();
        int ok = 0, fail = 0, shown = 0;
        final int totalItems = Math.max(1, presets.size() + singles.size());
        StringBuilder fails = new StringBuilder();
        for (File p : presets) {
            try {
                Map<String, String> kv;
                String text = readFile(p);
                kv = parsePreset(text);
                int np;
                try {
                    np = Integer.parseInt(kv.get("shaders"));
                } catch (Exception e) {
                    throw new Exception("sem shaders=N");
                }
                for (int i = 0; i < np; i++) {
                    String v = kv.get("shader" + i);
                    if (v != null) referenced.add(fileStem(new File(v).getName().toLowerCase()));
                }
                String rel = relFromRoot(rootDir, p);
                String name = safeName(displayNameFromRel(rel), used);
                Build b = convertPreset(text, new FsSource(rootDir), name, relDirOf(rel));
                writeFile(ctx, dir, name + ".dfx", b.dfx.getBytes("UTF-8"));
                for (Map.Entry<String, byte[]> e : b.files.entrySet()) {
                    writeFile(ctx, dir, e.getKey(), e.getValue());
                }
                snapshotPristine(ctx, tree, name);
                ok++;
            } catch (Throwable e) {
                fail++;
                if (fails.length() < 300) {
                    fails.append(p.getName()).append(" (").append(e.getMessage()).append("); ");
                }
            }
            shown++;
            if (pg == null) {
                if (shown % 100 == 0) toast(ctx, "Convertendo shaders... " + shown);
            } else if (shown % 5 == 0 || shown == totalItems) {
                prog(pg, "Convertendo shaders... " + shown + "/" + totalItems,
                        Math.min(100, shown * 100 / totalItems));
            }
        }
        for (File s : singles) {
            if (referenced.contains(fileStem(s.getName().toLowerCase()))) continue;
            try {
                String rel = relFromRoot(rootDir, s);
                String name = safeName(displayNameFromRel(rel), used);
                Build b = convertSlangSingle(readFile(s), new FsSource(rootDir), name,
                        relDirOf(rel));
                writeFile(ctx, dir, name + ".dfx", b.dfx.getBytes("UTF-8"));
                for (Map.Entry<String, byte[]> e : b.files.entrySet()) {
                    writeFile(ctx, dir, e.getKey(), e.getValue());
                }
                snapshotPristine(ctx, tree, name);
                ok++;
            } catch (Throwable ignoredFail) {
                fail++;
            }
            shown++;
            if (pg == null) {
                if (shown % 100 == 0) toast(ctx, "Convertendo shaders... " + shown);
            } else if (shown % 5 == 0 || shown == totalItems) {
                prog(pg, "Convertendo shaders... " + shown + "/" + totalItems,
                        Math.min(100, shown * 100 / totalItems));
            }
        }
        String msg = ok + " shaders instaladas, " + fail + " nao suportadas";
        if (fails.length() > 0) msg += "\nEx.: " + fails;
        return msg;
    }

    static String relFromRoot(File root, File f) {
        return root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
    }

    static String displayNameFromRel(String rel) {
        String noExt = fileStem(rel);
        String[] parts = noExt.split("/");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + " " + parts[parts.length - 1];
        }
        return parts[parts.length - 1];
    }

    static void collect(File dir, List<File> presets, List<File> singles) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, presets, singles);
            else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".slangp") || n.endsWith(".cgp")) presets.add(f);
                else if (n.endsWith(".slang")) singles.add(f);
            }
        }
    }

    static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
