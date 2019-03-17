package com.health.openscale.sync.gui.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.health.openscale.sync.BuildConfig;
import com.health.openscale.sync.R;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import timber.log.Timber;

public class DebugTree {

    public DebugTree() {
        Timber.plant(new Timber.DebugTree());
    }

    public Intent requestDebugIntent() {
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH-mm");
        String fileName = String.format("openScaleSync_%s.txt", format.format(new Date()));

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        return intent;
    }

    public void startLogTo(Context context, Uri uri) {
        try {
            OutputStream output = context.getContentResolver().openOutputStream(uri);
            Timber.plant(new FileDebugTree(output));
            Timber.d("Debug log enabled, %s v%s (%d), SDK %d, %s %s",
                    context.getResources().getString(R.string.app_name),
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                    Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL);
        }
        catch (IOException ex) {
            Timber.e(ex, "Failed to open debug log %s", uri.toString());
        }
    }

    public void close() {
        FileDebugTree tree = getEnabledFileDebugTree();
        if (tree != null) {
            Timber.d("Debug log disabled");
            Timber.uproot(tree);
            tree.close();
        }
    }

    private FileDebugTree getEnabledFileDebugTree() {
        for (Timber.Tree tree : Timber.forest()) {
            if (tree instanceof FileDebugTree) {
                return (FileDebugTree) tree;
            }
        }
        return null;
    }

    class FileDebugTree extends Timber.DebugTree {
        PrintWriter writer;
        DateFormat format;

        FileDebugTree(OutputStream output) {
            writer = new PrintWriter(output, true);
            format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        }

        void close() {
            writer.close();
        }

        private String priorityToString(int priority) {
            switch (priority) {
                case Log.ASSERT:
                    return "Assert";
                case Log.ERROR:
                    return "Error";
                case Log.WARN:
                    return "Warning";
                case Log.INFO:
                    return "Info";
                case Log.DEBUG:
                    return "Debug";
                case Log.VERBOSE:
                    return "Verbose";
            }
            return String.format("Unknown (%d)", priority);
        }

        @Override
        protected synchronized void log(int priority, String tag, String message, Throwable t) {
            final long id = Thread.currentThread().getId();
            writer.printf("%s %s [%d] %s: %s\r\n",
                    format.format(new Date()), priorityToString(priority), id, tag, message);
        }
    }
}
