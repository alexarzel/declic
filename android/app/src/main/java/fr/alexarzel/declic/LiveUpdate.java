package fr.alexarzel.declic;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Mises à jour sans réinstaller l'APK.
 * La page de l'appli (web.html) est téléchargée depuis la dernière Release GitHub
 * et enregistrée dans le stockage de l'appli. Elle n'est utilisée que si la version
 * native qu'elle demande (native-version.txt) est déjà installée ; sinon l'appli
 * propose d'installer le nouvel APK.
 */
final class LiveUpdate {
    static final String BASE = "https://github.com/" + BuildConfig.REPO + "/releases/latest/download/";
    static final String APK_URL = BASE + "declic.apk";

    enum Result { NONE, WEB_READY, NEED_APK }

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "www");
    }

    static File page(Context ctx) {
        return new File(dir(ctx), "index.html");
    }

    static File required(Context ctx) {
        return new File(dir(ctx), "native-version.txt");
    }

    /** La page téléchargée est-elle utilisable par cette version de l'appli ? */
    static boolean hasUsablePage(Context ctx) {
        if (!page(ctx).isFile()) return false;
        try {
            int need = Integer.parseInt(new String(readAll(new java.io.FileInputStream(required(ctx))), StandardCharsets.UTF_8).trim());
            return need <= BuildConfig.NATIVE_VERSION;
        } catch (Exception e) {
            return false;
        }
    }

    /** À appeler hors du fil principal. */
    static Result check(Context ctx, boolean usingLivePage) {
        try {
            int need = Integer.parseInt(new String(download(BASE + "native-version.txt"), StandardCharsets.UTF_8).trim());
            if (need > BuildConfig.NATIVE_VERSION) return Result.NEED_APK;

            byte[] fresh = download(BASE + "web.html");
            if (fresh.length < 1000 || !new String(fresh, StandardCharsets.UTF_8).contains("data-declic-page")) return Result.NONE;

            byte[] current = usingLivePage && page(ctx).isFile()
                    ? readAll(new java.io.FileInputStream(page(ctx)))
                    : readAll(ctx.getAssets().open("www/index.html"));
            if (Arrays.equals(fresh, current)) return Result.NONE;

            File d = dir(ctx);
            if (!d.isDirectory() && !d.mkdirs()) return Result.NONE;
            File tmp = new File(d, "index.html.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(fresh);
            }
            try (FileOutputStream out = new FileOutputStream(required(ctx))) {
                out.write(String.valueOf(need).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(page(ctx))) return Result.NONE;
            return Result.WEB_READY;
        } catch (Exception e) {
            return Result.NONE;
        }
    }

    private static byte[] download(String url) throws Exception {
        String next = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(next).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Declic-Android");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                next = new URL(new URL(next), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new Exception("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("Trop de redirections");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
