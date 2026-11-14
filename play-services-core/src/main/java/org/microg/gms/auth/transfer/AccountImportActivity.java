/*
 * Fork addition (mwbra130): account import for phone -> watch account transfer.
 *
 * Reads an AuthResponse wire-format file produced by AccountExportActivity,
 * registers the account exactly the way a successful WebView login does
 * (mirrors LoginActivity.saveAccount + retrieveGmsToken), then performs the
 * same first ac2dm token request a real login performs. That request doubles
 * as the decisive probe: if Google accepts the transferred master token from
 * this device, the response carries an auth token and the account is live.
 */

package org.microg.gms.auth.transfer;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.microg.gms.auth.AuthConstants;
import org.microg.gms.auth.AuthManager;
import org.microg.gms.auth.AuthRequest;
import org.microg.gms.auth.AuthResponse;
import org.microg.gms.checkin.CheckinManager;
import org.microg.gms.common.Constants;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class AccountImportActivity extends Activity {
    private AccountManager accountManager;
    private TextView status;
    private Button importButton;
    private File importFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accountManager = AccountManager.get(this);
        importFile = new File(getExternalFilesDir(null), AccountExportActivity.EXPORT_FILE_NAME);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        layout.addView(status);

        importButton = new Button(this);
        importButton.setText("Import account");
        importButton.setOnClickListener(v -> startImport());
        layout.addView(importButton);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        refreshState();
    }

    private void refreshState() {
        if (importFile.exists()) {
            status.setText("Import file found:\n" + importFile.getAbsolutePath()
                    + "\n\nTap Import to register the account on this device.");
            importButton.setEnabled(true);
        } else {
            status.setText("No import file found.\nExpected: " + importFile.getAbsolutePath()
                    + "\n\nPush " + AccountExportActivity.EXPORT_FILE_NAME
                    + " there first, then reopen this screen.");
            importButton.setEnabled(false);
        }
    }

    private void startImport() {
        importButton.setEnabled(false);
        status.setText("Importing...");
        new Thread(() -> {
            String result = doImport();
            runOnUiThread(() -> {
                status.setText(result);
                importButton.setEnabled(false);
            });
        }).start();
    }

    private String doImport() {
        String text;
        try {
            text = new String(Files.readAllBytes(importFile.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Could not read the import file: " + e.getMessage();
        }

        AuthResponse response = AuthResponse.parse(text);
        if (TextUtils.isEmpty(response.email) || TextUtils.isEmpty(response.token)) {
            return "Import file is missing Email or Token. Nothing was imported; "
                    + "the file was kept so you can inspect or replace it.";
        }

        Account account = new Account(response.email, AuthConstants.DEFAULT_ACCOUNT_TYPE);
        for (Account existing : accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)) {
            if (existing.name.equals(account.name)) {
                return "Account " + account.name + " already exists on this device. "
                        + "Nothing was changed; the file was kept.";
            }
        }

        // Mirror LoginActivity.saveAccount().
        if (!accountManager.addAccountExplicitly(account, response.token, null)) {
            return "AccountManager refused to create the account. Nothing was imported; "
                    + "the file was kept.";
        }
        accountManager.setAuthToken(account, "SID", response.Sid);
        accountManager.setAuthToken(account, "LSID", response.LSid);
        accountManager.setUserData(account, "flags", "1");
        accountManager.setUserData(account, "services", response.services);
        accountManager.setUserData(account, "oauthAccessToken", "1");
        accountManager.setUserData(account, "firstName", response.firstName);
        accountManager.setUserData(account, "lastName", response.lastName);
        if (!TextUtils.isEmpty(response.accountId)) {
            accountManager.setUserData(account, "GoogleUserId", response.accountId);
        }

        StringBuilder report = new StringBuilder();
        report.append("Account created: ").append(account.name).append("\n\n");

        // Mirror LoginActivity.retrieveGmsToken(): the first ac2dm token request.
        // Its result is the verdict on whether the transferred master token works
        // from this device.
        boolean tokenOk = false;
        String tokenError = null;
        try {
            AuthManager authManager = new AuthManager(this, account.name, Constants.GMS_PACKAGE_NAME, "ac2dm");
            authManager.setPermitted(true);
            AuthResponse gmsResponse = new AuthRequest().fromContext(this)
                    .appIsGms()
                    .callerIsGms()
                    .service(authManager.getService())
                    .email(account.name)
                    .token(accountManager.getPassword(account))
                    .systemPartition(true)
                    .hasPermission(true)
                    .addAccount()
                    .getAccountId()
                    .droidguardResults("null")
                    .getResponse();
            if (gmsResponse != null) {
                authManager.storeResponse(gmsResponse);
                tokenOk = !TextUtils.isEmpty(gmsResponse.auth);
                if (!tokenOk) tokenError = "response carried no auth token";
            } else {
                tokenError = "empty response";
            }
        } catch (Exception e) {
            tokenError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        report.append(tokenOk
                ? "Token request: OK - Google accepted the transferred master token from this device.\n\n"
                : "Token request: FAILED (" + tokenError + "). The account exists locally, "
                        + "but Google did not issue a token from this device.\n\n");

        boolean checkinOk;
        try {
            checkinOk = CheckinManager.checkin(this, true) != null;
        } catch (Exception e) {
            checkinOk = false;
        }
        report.append("Device checkin: ").append(checkinOk ? "OK" : "failed (will retry in background)").append("\n\n");

        // The file holds a full account credential; remove it once the account exists.
        if (importFile.delete()) {
            report.append("Import file deleted.");
        } else {
            report.append("WARNING: could not delete the import file. Delete it manually: ")
                    .append(importFile.getAbsolutePath());
        }
        return report.toString();
    }
}
