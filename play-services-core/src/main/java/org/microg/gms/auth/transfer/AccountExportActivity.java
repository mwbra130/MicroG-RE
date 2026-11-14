/*
 * Fork addition (mwbra130): account export for phone -> watch account transfer.
 *
 * Runs inside MicroG (the authenticator), so it may read the account password
 * (the master token), stored SID/LSID tokens and user data of MicroG's own
 * accounts and serialize them into the AuthResponse wire format that
 * AuthResponse.parse() consumes on the importing device.
 *
 * The exported text is a full account credential. It is written to a file only,
 * never logged, and should be deleted on both devices after the transfer.
 */

package org.microg.gms.auth.transfer;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.microg.gms.auth.AuthConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class AccountExportActivity extends Activity {
    public static final String EXPORT_FILE_NAME = "microg-account-export.txt";

    private AccountManager accountManager;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accountManager = AccountManager.get(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("Export a MicroG account for transfer to another device.\n\n"
                + "WARNING: the export file contains the account master token, "
                + "which is a full account credential. Transfer it only over a "
                + "local connection and delete it afterwards.");
        layout.addView(status);

        Account[] accounts = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE);
        if (accounts.length == 0) {
            TextView none = new TextView(this);
            none.setText("\nNo MicroG accounts found on this device.");
            layout.addView(none);
        }
        for (Account account : accounts) {
            Button button = new Button(this);
            button.setText("Export " + account.name);
            button.setOnClickListener(v -> exportAccount(account));
            layout.addView(button);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private void exportAccount(Account account) {
        try {
            StringBuilder sb = new StringBuilder();
            appendField(sb, "Email", account.name);
            appendField(sb, "Token", accountManager.getPassword(account));
            appendField(sb, "SID", accountManager.peekAuthToken(account, "SID"));
            appendField(sb, "LSID", accountManager.peekAuthToken(account, "LSID"));
            appendField(sb, "services", accountManager.getUserData(account, "services"));
            appendField(sb, "firstName", accountManager.getUserData(account, "firstName"));
            appendField(sb, "lastName", accountManager.getUserData(account, "lastName"));
            appendField(sb, "accountId", accountManager.getUserData(account, "GoogleUserId"));
            String text = sb.toString();

            // Copy 1: MicroG's own external files dir (record).
            File own = new File(getExternalFilesDir(null), EXPORT_FILE_NAME);
            writeText(new FileOutputStream(own), text);

            // Copy 2: shared Downloads via MediaStore, so the file can be picked
            // up by other apps (e.g. a terminal) without extra permissions.
            String downloadsPath = null;
            try {
                downloadsPath = writeToDownloads(text);
            } catch (Exception e) {
                downloadsPath = null;
            }

            status.setText("Exported " + account.name + " (" + text.length() + " chars).\n\n"
                    + "App copy: " + own.getAbsolutePath() + "\n"
                    + (downloadsPath != null
                            ? "Downloads copy: " + downloadsPath + "\n\n"
                            : "Downloads copy failed; use the app copy.\n\n")
                    + "Next: move the file to the other device, into the same "
                    + "app.revanced.android.gms files folder, then open the "
                    + "account import screen there.\n\n"
                    + "Delete both copies when the transfer is done.");
        } catch (Exception e) {
            status.setText("Export failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String writeToDownloads(String text) throws Exception {
        ContentResolver resolver = getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, EXPORT_FILE_NAME);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                writeText(out, text);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return "Download/" + EXPORT_FILE_NAME;
        }
        File file = new File("/sdcard/Download", EXPORT_FILE_NAME);
        writeText(new FileOutputStream(file), text);
        return file.getAbsolutePath();
    }

    private static void writeText(OutputStream out, String text) throws Exception {
        if (out == null) throw new IllegalStateException("null output stream");
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        if (value == null) return;
        sb.append(key).append('=').append(value).append('\n');
    }
}
