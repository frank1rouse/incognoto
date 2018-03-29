/*
* Copyright (C) 2018 Incognoto
* License: GPL version 2 or higher http://www.gnu.org/licenses/gpl.html
 */
package com.notes.sum;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.notes.sum.sec.NFCKey;
import com.notes.sum.sec.Note;
import com.notes.sum.sec.NoteManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App starts from here using the ActivityMain.xml layout file.
 */
public class ActivityMain extends Activity {

    public static LinearLayout tagLayout;
    NfcAdapter nfcAdapter;
    PendingIntent pendingIntent;
    Dialog passwordDialog;
    String authenticated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Secure the view: disable screenshots and block other apps from acquiring screen content
        //getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            // Additional screen security options in versions later than JellyBean
            // Hide notes in the "recent" app preview list
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        // Load the view and show a password prompt to decrypt the content.
        setContentView(R.layout.activity_main);
        tagLayout = (LinearLayout) findViewById(R.id.tags);

        // Start accepting a hardware based authentication method
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        pendingIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        SharedPreferences sharedPrefs = getSharedPreferences("temp", MODE_PRIVATE);
        if (sharedPrefs.getBoolean("default", true)) {
            // Run only once: This is the first startup so the user does not have a master password.
            NoteManager.newDefaultKey(ActivityMain.this);
            sharedPrefs.edit().putBoolean("default", false).commit(); // Never generate pass again
        }

        // Run this each time the app opens
        if (NoteManager.usingDefaultPassword(ActivityMain.this)) {
            // A generated password is being used, just unlock it
            loadNotes(null);
            authenticated = "";
            if (sharedPrefs.getBoolean("firstRun", true)) {
                // Run this only once when a new users installs the app
                addDefaultNotes();
                sharedPrefs.edit().putBoolean("firstRun", false).commit();
            }
        } else {
            // A user defined password is set, prompt for input
            displayPasswordDialog("Notes Are Locked");
        }
    }

    // The first notes that new users see, telling them about the security, privacy, and features.
    public void addDefaultNotes() {
        NoteManager.addNote(new Note(getResources().getString(R.string.welcome_note_3)));
        NoteManager.addNote(new Note(getResources().getString(R.string.welcome_note_2)));
        NoteManager.addNote(new Note(getResources().getString(R.string.welcome_note_1)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfcAdapter.disableForegroundDispatch(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // When the app is open and in the foreground then accept NFC input as the decryption key
        final Pattern OTP_PATTERN = Pattern.compile("^https://my\\.yubico\\.com/neo/([a-zA-Z0-9!]+)$");
        Matcher matcher = OTP_PATTERN.matcher(intent.getDataString());
        if (matcher.matches()) {
            // Found yubikey NEO pattern
            handleHardwareKey(matcher.group(1));
        } else {
            // Parse the key from the from the hardware device parcelable if it's not in the NEO format
            Parcelable[] raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            byte[] bytes = ((NdefMessage) raw[0]).toByteArray();
            bytes = Arrays.copyOfRange(bytes, 23, bytes.length);
            handleHardwareKey(new NFCKey().fromScanCodes(bytes));
        }
    }

    /*
    * Called when an NFC device is used when the app is in the foreground.
    */
    public void handleHardwareKey(final String data) {
        if (authenticated == null) {
            // The user has set an NFC password already, accept the input and attempt to decrypt
            passwordDialog.dismiss();
            loadNotes(data);
        } else {
            // The user has not set a password, ask to use the NFC tag as the password
            AlertDialog.Builder builder1 = new AlertDialog.Builder(ActivityMain.this);
            builder1.setTitle("Hardware Key");
            builder1.setMessage("Do you want to use this as your new password?");
            builder1.setCancelable(true);
            builder1.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    NoteManager.setNewPassword(null, data);
                    ActivityMain.this.deleteFile("default"); // Delete the default password file
                    Toast notify = Toast.makeText(
                            ActivityMain.this, "Success. Test your authentication.", Toast.LENGTH_LONG);
                    notify.setGravity(Gravity.CENTER, 0, 0);
                    notify.show();
                    displayPasswordDialog("Notes Are Locked");
                }
            });
            builder1.setNegativeButton("No", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });
            AlertDialog alert = builder1.create();
            alert.show();
        }
    }

    // Prompt user for password input and attempt to decrypt content
    public void displayPasswordDialog(String title) {
        passwordDialog = new Dialog(ActivityMain.this);
        passwordDialog.setCancelable(false);
        passwordDialog.setContentView(R.layout.dialog_input);
        passwordDialog.setCanceledOnTouchOutside(false);
        passwordDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(Color.TRANSPARENT));
        passwordDialog.setTitle(title);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(passwordDialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;

        final EditText input = (EditText) passwordDialog.findViewById(R.id.input);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                passwordDialog.dismiss();
                loadNotes(input.getText().toString());
                return false;
            }
        });
        passwordDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                InputMethodManager imm = (InputMethodManager)
                        ActivityMain.this.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });

        passwordDialog.findViewById(R.id.submit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                passwordDialog.dismiss();
                loadNotes(input.getText().toString());
            }
        });

        passwordDialog.show();
    }

    // Makes an attempt to decrypt the notes and detect if it's valid
    public void loadNotes(String password) {
        NoteManager nm = new NoteManager(
                (ListView) findViewById(R.id.listview), ActivityMain.this, password,
                (LinearLayout) findViewById(R.id.tags));
        if (nm.status == null || nm == null) {
            displayPasswordDialog("Invalid Password");
            return;
        }
        authenticated = "";

        handleIntents(getIntent());
    }

    // If you highlight text from another app you can select "share" then select this app.
    // Accepts string input from elsewhere, if you do it manually.
    private static void handleIntents(Intent intent) {
        if (intent.getType() != null) {
            if (intent.getType().toString().equals("application/octet-stream")) {
                // Accept any encrypted notes file
                // TODO: prompt for storage permission if not already given
                Uri uri = (Uri) intent.getExtras().get(Intent.EXTRA_STREAM);
                String path = uri.getLastPathSegment().replace("raw:", "");
                try {
                    String content = NoteManager.getFileContent(new FileInputStream(new File(path)));
                    Log.e("NOTES", content);
                    // TODO: If the file is encrypted then prompt for a decryption key
                    // TODO: Delete current contents and encrypt the imported file
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
                // Accept plain text strings
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    NoteManager.addNote(new Note(sharedText));
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        SearchManager manager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        SearchView search = (SearchView) menu.findItem(R.id.search).getActionView();
        search.setSearchableInfo(manager.getSearchableInfo(getComponentName()));
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                NoteManager.clearSearch(false);
                NoteManager.search(query);
                return true;
            }
        });
        return true;

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        // Press back to close the app quickly and remove it from the recent tasks list
        finishAndRemoveTask();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add:
                noteContentPreview(true, ActivityMain.this, null);
                break;
            case R.id.backup:
                NoteManager.backup();
                break;
            case R.id.restore:
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                Toast.makeText(ActivityMain.this,
                        "Select an encrypted notes file to import", Toast.LENGTH_SHORT).show();
                startActivityForResult(intent, 10);
                // `onActivityResult` is automatically called after this
                break;
            case R.id.security:
                showNewMasterPasswordDialog();
                break;
        }
        return true;
    }


    // Called after `restore` when a file has been selected to be imported
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.e("NOTES", "result code: " + String.valueOf(resultCode));
        if (requestCode == 10) {
            // Activity has been started with a file to be imported
            Uri uri = (Uri) data.getExtras().get(Intent.EXTRA_STREAM);
            String path = uri.getLastPathSegment().replace("raw:", "");
            String content = null;
            try {
                content = NoteManager.getFileContent(new FileInputStream(new File(path)));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            Log.e("NOTES", content);
            // TODO: If the file is encrypted then prompt for a decryption key
            // TODO: Delete current contents and encrypt the imported file
        }
    }

    // Used to change note content or make a new note.
    // If `newNote` is true then this will insert a new note into the database,
    // else it will change the given note's content.
    public static void noteContentPreview(final boolean newNote,
                                          final Context context, final Note note) {
        final Dialog notePreviewDialog = new Dialog(context);
        notePreviewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        notePreviewDialog.setContentView(R.layout.dialog_note_view);
        notePreviewDialog.setCanceledOnTouchOutside(false);
        notePreviewDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(android.graphics.Color.TRANSPARENT));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(notePreviewDialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        lp.y -= 216;
        lp.dimAmount=0.8f;
        notePreviewDialog.getWindow().setAttributes(lp);

        final EditText noteNameField = (EditText) notePreviewDialog.findViewById(R.id.textInput);
        if (note != null) {
            // This is not a new note, the user is making changes.
            noteNameField.setText(note.getNoteContent());
            notePreviewDialog.findViewById(R.id.mainLayout).requestFocus();
        } else {
            // This is a new note, optimize fast input
            // Pop up the keyboard when the dialog shows. Used for quick user input.
            notePreviewDialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface dialog) {
                    InputMethodManager imm = (InputMethodManager)
                            context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(noteNameField, InputMethodManager.SHOW_IMPLICIT);
                }
            });
            noteNameField.requestFocus();
        }
        noteNameField.setMaxHeight(Resources.getSystem().getDisplayMetrics().heightPixels - 450);

        // Safety: if the user accidentally hits the back button. Prevents changes from being lost.
        notePreviewDialog.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface arg0, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (note != null && note.getNoteContent().trim().equals(
                            noteNameField.getText().toString().trim())) {
                        notePreviewDialog.dismiss();
                    } else if (note == null && noteNameField.getText().
                            toString().trim().length() == 0) {
                        // This is a new note but no content was entered.
                        notePreviewDialog.dismiss();
                    }
                }
                return true;
            }
        });

        // Update button changes the text value of the note
        notePreviewDialog.findViewById(R.id.update).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String content = noteNameField.getText().toString();
                if (!newNote)
                    NoteManager.removeNote(note); // Remove then re-addNote
                NoteManager.addNote(new Note(content));
                notePreviewDialog.dismiss();
            }
        });

        // Remove the note from the database
        notePreviewDialog.findViewById(R.id.delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (note != null)
                    showDeleteConfirmation(false, context,
                            "Delete This Note?", "This action cannot be undone.", note);
                notePreviewDialog.dismiss();
            }
        });

        // Export the note's string content
        notePreviewDialog.findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
                sharingIntent.setType("text/plain");
                sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, noteNameField.getText().toString());
                context.startActivity(Intent.createChooser(sharingIntent, "Send a copy to:"));
            }
        });

        notePreviewDialog.show();
    }

    public void showNewMasterPasswordDialog() {
        final Dialog inputDialog = new Dialog(ActivityMain.this);
        //inputDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        inputDialog.setContentView(R.layout.dialog_password_change);
        inputDialog.setCanceledOnTouchOutside(false);
        inputDialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(Color.DKGRAY));
        inputDialog.setTitle("Change Master Password");
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(inputDialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;

        final EditText oldPassInput = (EditText) inputDialog.findViewById(R.id.oldPass);
        final boolean usingDefaultPassword = NoteManager.usingDefaultPassword(ActivityMain.this);
        if (usingDefaultPassword) {
            // Hide the 'old password' input since there is no old password set by the user
            oldPassInput.setVisibility(View.GONE);
        }

        final EditText newPassInput = (EditText) inputDialog.findViewById(R.id.newPass);
        final EditText confirmNewPassInput = (EditText) inputDialog.findViewById(R.id.confirmNewPass);
        inputDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                InputMethodManager imm = (InputMethodManager)
                        ActivityMain.this.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(oldPassInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });

        inputDialog.findViewById(R.id.submit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (usingDefaultPassword) {
                    if (newPassInput.getText().toString().equals(confirmNewPassInput.getText().toString()))  {
                        ActivityMain.this.deleteFile("default"); // Delete the default password file
                        NoteManager.setNewPassword(null, newPassInput.getText().toString());
                        inputDialog.dismiss();
                        Toast.makeText(ActivityMain.this, "Password Changed", Toast.LENGTH_LONG).show();
                    } else {
                        // Check that the new password strings do not match
                        oldPassInput.setText("");
                        newPassInput.setText("");
                        Toast.makeText(ActivityMain.this, "Password Do Not Match", Toast.LENGTH_LONG).show();
                    }
                } else if (newPassInput.getText().toString().equals(confirmNewPassInput.getText().toString())) {
                    // Changing master password without a default
                    inputDialog.dismiss();
                    if (NoteManager.setNewPassword(oldPassInput.getText().toString(), newPassInput.getText().toString()) == null)
                        Toast.makeText(ActivityMain.this, "Password Changed", Toast.LENGTH_LONG).show();
                } else {
                    oldPassInput.setText("");
                    Toast.makeText(ActivityMain.this, "Old Password Incorrect", Toast.LENGTH_LONG).show();
                }
            }
        });

        inputDialog.show();
    }

    // In the note preview dialog confirm the deletion action
    // If "all" is true then clear all notes
    public static void showDeleteConfirmation(final boolean all, final Context context,
                                              final String title, final String message,
                                              final Note note) {
        AlertDialog.Builder builder1 = new AlertDialog.Builder(context);
        builder1.setTitle(title);
        builder1.setMessage(message);
        builder1.setCancelable(true);
        builder1.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                if (all)
                    NoteManager.clearAll();
                else
                    NoteManager.removeNote(note);
            }
        });
        builder1.setNegativeButton("No", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });
        AlertDialog alert = builder1.create();
        alert.show();
    }
}
