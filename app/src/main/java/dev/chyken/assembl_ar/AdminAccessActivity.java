package dev.chyken.assembl_ar;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import dev.chyken.assembl_ar.ui.UiUtils;

public final class AdminAccessActivity extends AppCompatActivity {
    private static final String PREFERENCES = "authorized_registration";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_HASH = "pin_hash";
    private static final String KEY_FAILED_ATTEMPTS = "failed_attempts";
    private static final String KEY_LOCK_UNTIL = "lock_until";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MILLIS = 30_000L;

    private SharedPreferences preferences;
    private TextInputLayout pinLayout;
    private TextInputLayout confirmLayout;
    private TextInputEditText pinInput;
    private TextInputEditText confirmInput;
    private MaterialButton submitButton;
    private boolean creatingPin;
    private boolean provisioningAuthorized;
    private final ActivityResultLauncher<Intent> deviceCredential = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    provisioningAuthorized = true;
                    setProvisioningFieldsEnabled(true);
                    submitButton.setText(R.string.create_pin);
                    pinLayout.setError(null);
                    pinInput.requestFocus();
                } else {
                    pinLayout.setError(getString(R.string.admin_device_auth_required));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_admin_access);
        UiUtils.applyStandardInsets(this);
        UiUtils.setupToolbar(this, getString(R.string.administrator_tools));
        UiUtils.setupPrimaryNavigation(this, R.id.navAdminButton);

        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        pinLayout = findViewById(R.id.pinInputLayout);
        confirmLayout = findViewById(R.id.pinConfirmLayout);
        pinInput = findViewById(R.id.pinInput);
        confirmInput = findViewById(R.id.pinConfirmInput);
        submitButton = findViewById(R.id.adminSubmitButton);
        creatingPin = !preferences.contains(KEY_HASH);
        confirmLayout.setVisibility(creatingPin ? View.VISIBLE : View.GONE);
        provisioningAuthorized = !creatingPin;
        setProvisioningFieldsEnabled(!creatingPin);
        submitButton.setText(creatingPin ? R.string.verify_device_owner : R.string.unlock);
        submitButton.setOnClickListener(view -> submit());
    }

    private void submit() {
        clearErrors();
        if (creatingPin && !provisioningAuthorized) {
            requestDeviceOwnerAuthorization();
            return;
        }
        long lockUntil = preferences.getLong(KEY_LOCK_UNTIL, 0L);
        long now = System.currentTimeMillis();
        if (lockUntil > now) {
            long seconds = Math.max(1, (lockUntil - now + 999) / 1000);
            pinLayout.setError("Too many attempts. Try again in " + seconds + " seconds.");
            return;
        }

        String pin = UiUtils.nonBlank(pinInput.getText());
        if (!pin.matches("\\d{4,8}")) {
            pinLayout.setError("Use a 4–8 digit PIN.");
            return;
        }
        if (creatingPin) {
            String confirmation = UiUtils.nonBlank(confirmInput.getText());
            if (!pin.equals(confirmation)) {
                confirmLayout.setError("PINs do not match.");
                return;
            }
            createPin(pin);
            openRegistration();
        } else if (verifyPin(pin)) {
            preferences.edit().putInt(KEY_FAILED_ATTEMPTS, 0).remove(KEY_LOCK_UNTIL).apply();
            openRegistration();
        } else {
            int attempts = preferences.getInt(KEY_FAILED_ATTEMPTS, 0) + 1;
            SharedPreferences.Editor editor = preferences.edit().putInt(KEY_FAILED_ATTEMPTS, attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                editor.putLong(KEY_LOCK_UNTIL, now + LOCK_DURATION_MILLIS)
                        .putInt(KEY_FAILED_ATTEMPTS, 0);
            }
            editor.apply();
            pinLayout.setError("Incorrect administrator PIN.");
            pinInput.setText("");
        }
    }

    private void requestDeviceOwnerAuthorization() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            pinLayout.setError(getString(R.string.admin_secure_lock_required));
            return;
        }
        Intent credentialIntent = keyguard.createConfirmDeviceCredentialIntent(
                getString(R.string.admin_device_auth_title),
                getString(R.string.admin_device_auth_body));
        if (credentialIntent == null) {
            pinLayout.setError(getString(R.string.admin_device_auth_required));
            return;
        }
        deviceCredential.launch(credentialIntent);
    }

    private void setProvisioningFieldsEnabled(boolean enabled) {
        pinLayout.setEnabled(enabled);
        confirmLayout.setEnabled(enabled);
        pinInput.setEnabled(enabled);
        confirmInput.setEnabled(enabled);
    }

    private void createPin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(pin, salt);
        preferences.edit()
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .apply();
    }

    private boolean verifyPin(String pin) {
        try {
            byte[] salt = Base64.decode(preferences.getString(KEY_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(preferences.getString(KEY_HASH, ""), Base64.NO_WRAP);
            return MessageDigest.isEqual(expected, derive(pin, salt));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 120_000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PIN hashing is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private void clearErrors() {
        pinLayout.setError(null);
        confirmLayout.setError(null);
    }

    private void openRegistration() {
        startActivity(new Intent(this, AdminRegistrationActivity.class));
        finish();
    }
}
