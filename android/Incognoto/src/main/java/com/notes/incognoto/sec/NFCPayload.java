package com.notes.incognoto.sec;

import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Parcelable;

import com.notes.incognoto.ActivityMain;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Used to read a YubiKey NEO's NFC payload as an input device for decryption
 */
public class NFCPayload {
    public NFCPayload() {}

    private static final String[] usb2key1 = new String[]{
            "", "", "", "", "a", "b", "c", "d", "e", "f", "g", /* 0xa */ "h", "i", "j", "k", "l",
            "m", "n", "o", "p", "q", /* 0x14 */ "r", "s", "t", "u", "v", "w", "x", "y", "z",
            "1", /* 0x1e */ "2", "3", "4", "5", "6", "7", "8", "9", "0", "\n", /* 0x28 */ "", "",
            "\t", " ", "-", "=", "[", "]", "", "\\", ";", "'", "`", ",", ".", "/", /* 0x38 */ };
    private static final String[] usb2key2 = new String[]{
            "", "", "", "", "A", "B", "C", "D", "E", "F", "G", /* 0x8a */ "H", "I", "J", "K",
            "L", "M", "N", "O", "P", "Q", /* 0x94 */ "R", "S", "T", "U", "V", "W", "X", "Y",
            "Z", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "", "", "", "", "", "_",
            "+", "{", "}", "", "|", ":", "\"", "~", "<", ">", "?", };

    protected static final int SHIFT = 0x80;

    protected String fromScanCode(int code) {
        if (code < SHIFT && code < usb2key1.length) {
            return usb2key1[code];
        } else {
            code = code ^ SHIFT;
            if (code < usb2key2.length) {
                return usb2key2[code];
            }
        }
        return "";
    }

    public final String fromScanCodes(byte[] bytes) {
        StringBuilder buf = new StringBuilder();
        for (byte b : bytes) {
            buf.append(fromScanCode(b & 0xff));
        }
        return buf.toString();
    }

    public static void handleIntent(Intent intent) {
        final Pattern OTP_PATTERN = Pattern.compile("^https://my\\.yubico\\.com/neo/([a-zA-Z0-9!]+)$");
        Matcher matcher = OTP_PATTERN.matcher(intent.getDataString());
        if (matcher.matches()) {
            // Found yubikey NEO pattern
            ActivityMain.handleHardwareKey(matcher.group(1));
        } else {
            // Parse the key from the from the hardware device parcelable if it's not in the NEO format
            Parcelable[] raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            byte[] bytes = ((NdefMessage) raw[0]).toByteArray();
            bytes = Arrays.copyOfRange(bytes, 23, bytes.length);
            ActivityMain.handleHardwareKey(new NFCPayload().fromScanCodes(bytes));
        }

    }
}