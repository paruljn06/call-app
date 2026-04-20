package com.example.call;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CALL_PERMISSION = 1;
    private static final int REQUEST_ID_DEFAULT_DIALER = 2;
    private CommunicationViewModel viewModel;
    private RecyclerView recyclerView;
    private ContactAdapter contactAdapter;
    private CallLogAdapter callLogAdapter;
    private TabLayout tabLayout;
    private EditText etSearch, etDialNumber;
    private FloatingActionButton fabAdd, fabDial;
    private LinearLayout contactsLayout, dialpadLayout;
    private String currentNumberToCall = "";
    private String currentNameToCall = "";
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(CommunicationViewModel.class);

        recyclerView = findViewById(R.id.recyclerView);
        tabLayout = findViewById(R.id.tabLayout);
        etSearch = findViewById(R.id.etSearch);
        etDialNumber = findViewById(R.id.etDialNumber);
        fabAdd = findViewById(R.id.fabAdd);
        fabDial = findViewById(R.id.fabDial);
        contactsLayout = findViewById(R.id.contactsLayout);
        dialpadLayout = findViewById(R.id.dialpadLayout);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        contactAdapter = new ContactAdapter();
        callLogAdapter = new CallLogAdapter();

        recyclerView.setAdapter(contactAdapter);

        setupObservers();
        setupListeners();
        setupDialpad();
        
        checkAndRequestDefaultDialer();
    }

    private void checkAndRequestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                startActivityForResult(intent, REQUEST_ID_DEFAULT_DIALER);
            }
        } else {
            TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecomManager != null && !getPackageName().equals(telecomManager.getDefaultDialerPackage())) {
                Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
                startActivityForResult(intent, REQUEST_ID_DEFAULT_DIALER);
            }
        }
    }

    private void setupObservers() {
        viewModel.getAllContacts().observe(this, contacts -> {
            if (tabLayout.getSelectedTabPosition() == 0) {
                contactAdapter.submitList(contacts);
            }
        });

        viewModel.getAllCallLogs().observe(this, logs -> {
            if (tabLayout.getSelectedTabPosition() == 1) {
                callLogAdapter.submitList(logs);
            }
        });
    }

    private void setupListeners() {
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) { // Contacts
                    contactsLayout.setVisibility(View.VISIBLE);
                    dialpadLayout.setVisibility(View.GONE);
                    recyclerView.setAdapter(contactAdapter);
                    fabAdd.show();
                    etSearch.setVisibility(View.VISIBLE);
                    viewModel.getAllContacts().observe(MainActivity.this, contactAdapter::submitList);
                } else if (tab.getPosition() == 1) { // Logs
                    contactsLayout.setVisibility(View.VISIBLE);
                    dialpadLayout.setVisibility(View.GONE);
                    recyclerView.setAdapter(callLogAdapter);
                    fabAdd.hide();
                    etSearch.setVisibility(View.GONE);
                    viewModel.getAllCallLogs().observe(MainActivity.this, callLogAdapter::submitList);
                } else { // Dialpad
                    contactsLayout.setVisibility(View.GONE);
                    dialpadLayout.setVisibility(View.VISIBLE);
                    fabAdd.hide();
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        contactAdapter.setOnItemClickListener(new ContactAdapter.OnItemClickListener() {
            @Override public void onItemClick(Contact contact) { showEditContactDialog(contact); }
            @Override
            public void onCallClick(Contact contact) {
                currentNumberToCall = contact.phoneNumber;
                currentNameToCall = contact.name;
                initiateCall();
            }
        });

        fabAdd.setOnClickListener(v -> showAddContactDialog());
        fabDial.setOnClickListener(v -> {
            currentNumberToCall = etDialNumber.getText().toString().trim();
            if (!currentNumberToCall.isEmpty()) {
                // Try to find name in our DB
                executorService.execute(() -> {
                    currentNameToCall = AppDatabase.getInstance(this).contactDao().getNameByNumber(currentNumberToCall);
                    if (currentNameToCall == null) currentNameToCall = "Unknown";
                    runOnUiThread(this::initiateCall);
                });
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.searchContacts(s.toString()).observe(MainActivity.this, contacts -> {
                    if (tabLayout.getSelectedTabPosition() == 0) contactAdapter.submitList(contacts);
                });
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void setupDialpad() {
        View.OnClickListener listener = v -> {
            Button b = (Button) v;
            etDialNumber.append(b.getText());
        };

        LinearLayout dialpad = findViewById(R.id.dialpadLayout);
        // Find all buttons in the GridLayout and set listener
        View grid = dialpad.getChildAt(1);
        if (grid instanceof android.widget.GridLayout) {
            android.widget.GridLayout g = (android.widget.GridLayout) grid;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                if (v instanceof Button) v.setOnClickListener(listener);
            }
        }
    }

    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_contact, null);
        EditText etName = view.findViewById(R.id.etDialogName);
        EditText etPhone = view.findViewById(R.id.etDialogPhone);

        builder.setView(view).setTitle("Add Contact")
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String phone = etPhone.getText().toString().trim();
                    if (!name.isEmpty() && !phone.isEmpty()) viewModel.insertContact(new Contact(name, phone));
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showEditContactDialog(Contact contact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_contact, null);
        EditText etName = view.findViewById(R.id.etDialogName);
        EditText etPhone = view.findViewById(R.id.etDialogPhone);

        etName.setText(contact.name);
        etPhone.setText(contact.phoneNumber);

        builder.setView(view).setTitle("Edit Contact")
                .setPositiveButton("Update", (dialog, which) -> {
                    contact.name = etName.getText().toString().trim();
                    contact.phoneNumber = etPhone.getText().toString().trim();
                    viewModel.updateContact(contact);
                })
                .setNeutralButton("Delete", (dialog, which) -> viewModel.deleteContact(contact))
                .setNegativeButton("Cancel", null).show();
    }

    private void initiateCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL_PERMISSION);
        } else {
            viewModel.insertCallLog(new CallLogEntry(currentNumberToCall, currentNameToCall, System.currentTimeMillis(), "OUTGOING"));
            
            Intent callingIntent = new Intent(this, CallingActivity.class);
            callingIntent.putExtra("NAME", currentNameToCall);
            callingIntent.putExtra("NUMBER", currentNumberToCall);
            callingIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(callingIntent);

            Intent dialIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + currentNumberToCall));
            startActivity(dialIntent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ID_DEFAULT_DIALER) {
            if (resultCode == RESULT_OK) Toast.makeText(this, "Default app set", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CALL_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initiateCall();
        }
    }
