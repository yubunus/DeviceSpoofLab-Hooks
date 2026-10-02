package com.devicespooflab.hooks;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.devicespooflab.hooks.ui.AndroidVersionTable;
import com.devicespooflab.hooks.ui.DevicePresets;
import com.devicespooflab.hooks.ui.DeviceProfile;
import com.devicespooflab.hooks.ui.IdentifierItem;
import com.devicespooflab.hooks.ui.IdentifierRegistry;
import com.devicespooflab.hooks.utils.ConfigManager;
import com.devicespooflab.hooks.utils.XposedServiceBridge;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.IntConsumer;

public class MainActivity extends AppCompatActivity {

    private static final long PERSIST_DEBOUNCE_MS = 80L;

    private File configFile;
    private final Map<String, IdentifierItem> items = new LinkedHashMap<>();
    private final Map<String, View> rowViews = new LinkedHashMap<>();
    private MaterialSwitch selectAllSwitch;
    private CompoundButton.OnCheckedChangeListener selectAllListener;

    private TextView deviceValue;

    private TextView androidVersionValue;
    private MaterialButton androidVersionMinus;
    private MaterialButton androidVersionPlus;
    private int androidVersionIdx;

    private static final String TIMEZONE_PROPERTY = "persist.sys.timezone";
    private TextView timezoneValue;

    private static final String LOCALE_LANGUAGE_PROPERTY = "locale.language";
    private static final String LOCALE_COUNTRY_PROPERTY = "locale.country";
    private static final String LOCALE_TAG_PROPERTY = "persist.sys.locale";
    private TextView languageValue;

    private static final String DISPLAY_SPOOF_PROPERTY = "hooks.spoof_display";
    private static final String NATIVE_PROPS_PROPERTY = "hooks.native_props";
    private static final String HIDE_ACCOUNTS_PROPERTY = "hooks.hide_accounts";
    private static final String VERBOSE_PROPERTY = "debug.verbose";

    // Set once the saved config has been moved off the pre-1.3 defaults.
    private static final String DEFAULTS_MIGRATED_FLAG = "_defaults_v13";

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spoof-persist");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Runnable persistRunnable = () -> persistExecutor.execute(this::doPersist);

    // Fired by XposedServiceBridge once the writable IXposedService binder is
    // bound (or replayed from cache). Hop onto persistExecutor so the binder
    // commit never runs on the bind callback's thread (which may be the main
    // thread when the binder was already cached at onCreate time).
    private final Runnable onServiceReady = () -> {
        try {
            persistExecutor.execute(this::publishIfWritable);
        } catch (RejectedExecutionException ignored) {
            // Activity is tearing down; the next launch republishes on bind.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configFile = new File(getFilesDir(), "device_profile.conf");
        ensureConfigFile();
        ConfigManager.reload();
        migrateLegacyDefaults();
        populateMissingValues();
        schedulePersist();

        setContentView(R.layout.activity_main);

        for (IdentifierRegistry.Definition d : IdentifierRegistry.all()) {
            String value = ConfigManager.getIdentifierValue(d.id);
            boolean enabled = ConfigManager.isIdentifierEnabled(d.id);
            items.put(d.id, new IdentifierItem(d.id, d.displayName, value, enabled));
        }

        wireDeviceCard();
        wireAndroidVersionStepper();
        populateTimezoneIfMissing();
        wireTimezonePicker();
        populateLocaleIfMissing();
        wireLanguagePicker();
        wireFlagSwitch(R.id.display_spoof_switch, DISPLAY_SPOOF_PROPERTY,
                ConfigManager.isDisplaySpoofEnabled());
        wireFlagSwitch(R.id.native_props_switch, NATIVE_PROPS_PROPERTY,
                ConfigManager.isNativePropsEnabled());
        wireFlagSwitch(R.id.hide_accounts_switch, HIDE_ACCOUNTS_PROPERTY,
                ConfigManager.isHideAccountsEnabled());
        wireFlagSwitch(R.id.verbose_switch, VERBOSE_PROPERTY,
                ConfigManager.isVerboseLoggingEnabled());
        wireRandomizeAll();
        buildSections();

        selectAllSwitch = findViewById(R.id.select_all_switch);
        selectAllListener = (btn, checked) -> {
            boolean changed = false;
            for (IdentifierItem item : items.values()) {
                if (item.enabled != checked) {
                    item.enabled = checked;
                    ConfigManager.setIdentifierEnabled(item.id, checked);
                    changed = true;
                }
            }
            if (changed) {
                schedulePersist();
                syncRowSwitches();
            }
        };
        silentSetSelectAll(allEnabled());

        // Register for the writable IXposedService binder. Vector delivers it to
        // our manifest-declared XposedProvider when this UID starts, regardless
        // of LSPosed hook scope, so the UI can publish edits straight to
        // RemotePreferences without scoping the module to its own process.
        XposedServiceBridge.init(this, onServiceReady);
    }

    @Override
    protected void onDestroy() {
        debounceHandler.removeCallbacks(persistRunnable);
        persistExecutor.execute(this::doPersist);
        persistExecutor.shutdown();
        super.onDestroy();
    }

    private void wireRandomizeAll() {
        MaterialButton btn = findViewById(R.id.btn_randomize_all);
        btn.setOnClickListener(v -> randomizeAll());
    }

    private void randomizeAll() {
        for (IdentifierRegistry.Definition d : IdentifierRegistry.all()) {
            IdentifierItem item = items.get(d.id);
            if (item == null) continue;
            String newVal = d.generator.generate();
            item.currentValue = newVal;
            ConfigManager.setIdentifierValue(item.id, newVal);
            View row = rowViews.get(item.id);
            if (row == null) continue;
            TextView value = row.findViewById(R.id.identifier_value);
            View invalidRow = row.findViewById(R.id.invalid_row);
            if (value != null) value.setText(newVal);
            if (invalidRow != null) invalidRow.setVisibility(d.isValid(newVal) ? View.GONE : View.VISIBLE);
        }
        schedulePersist();
    }

    private void wireDeviceCard() {
        deviceValue = findViewById(R.id.device_value);
        renderDeviceValue();
        MaterialButton preset = findViewById(R.id.device_preset);
        MaterialButton edit = findViewById(R.id.device_edit);
        preset.setOnClickListener(v -> showPresetPicker());
        edit.setOnClickListener(v -> showDeviceEditDialog());
    }

    private void renderDeviceValue() {
        if (deviceValue == null) return;
        String name = DeviceProfile.fromConfig().displayName();
        deviceValue.setText(name.isEmpty() ? "—" : name);
    }

    private void showPresetPicker() {
        final List<DeviceProfile> presets = DevicePresets.load(this);
        String currentFingerprint = ConfigManager.getRawProperty("ro.build.fingerprint");
        List<String> labels = new ArrayList<>(presets.size());
        int checkedIdx = -1;
        for (int i = 0; i < presets.size(); i++) {
            DeviceProfile preset = presets.get(i);
            labels.add(preset.label());
            if (checkedIdx < 0 && preset.fingerprint().equals(currentFingerprint)) {
                checkedIdx = i;
            }
        }
        showSearchPicker(R.string.device_preset_dialog_title, labels, checkedIdx,
                index -> applyDeviceProperties(presets.get(index).toProperties()));
    }

    private void showDeviceEditDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_device_edit, null);
        final DeviceProfile current = DeviceProfile.fromConfig();
        EditText brand = bindField(view, R.id.field_brand, current.brand);
        EditText manufacturer = bindField(view, R.id.field_manufacturer, current.manufacturer);
        EditText model = bindField(view, R.id.field_model, current.model);
        EditText name = bindField(view, R.id.field_name, current.name);
        EditText device = bindField(view, R.id.field_device, current.device);
        EditText board = bindField(view, R.id.field_board, current.board);
        EditText hardware = bindField(view, R.id.field_hardware, current.hardware);
        EditText platform = bindField(view, R.id.field_platform, current.platform);
        EditText buildId = bindField(view, R.id.field_build_id, current.buildId);
        EditText incremental = bindField(view, R.id.field_incremental, current.incremental);
        EditText securityPatch = bindField(view, R.id.field_security_patch, current.securityPatch);
        EditText fingerprint = bindField(view, R.id.field_fingerprint, current.fingerprint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.device_edit_title)
                .setView(view)
                .setPositiveButton(R.string.dialog_save, (d, w) -> {
                    DeviceProfile edited = DeviceProfile.fromConfig();
                    edited.brand = fieldText(brand);
                    edited.manufacturer = fieldText(manufacturer);
                    edited.model = fieldText(model);
                    edited.name = fieldText(name);
                    edited.device = fieldText(device);
                    edited.board = fieldText(board);
                    edited.hardware = fieldText(hardware);
                    edited.platform = fieldText(platform);
                    edited.buildId = fieldText(buildId);
                    edited.incremental = fieldText(incremental);
                    edited.securityPatch = fieldText(securityPatch);
                    // Fingerprints have no whitespace; drop line breaks from wrapping.
                    edited.fingerprint = fieldText(fingerprint).replaceAll("\\s", "");
                    applyDeviceProperties(DeviceProfile.changedProperties(current, edited));
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        dialog.show();
        // Resize with the IME, so the fields scroll and Save / Cancel stay
        // above the keyboard instead of behind it.
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void applyDeviceProperties(Map<String, String> updates) {
        if (updates.isEmpty()) return;
        ConfigManager.setProperties(updates);
        schedulePersist();
        renderDeviceValue();
        androidVersionIdx = AndroidVersionTable.currentIndex();
        renderAndroidVersion();
    }

    private static EditText bindField(View root, int id, String value) {
        EditText field = root.findViewById(id);
        field.setText(value);
        return field;
    }

    private static String fieldText(EditText field) {
        return field.getText().toString().trim();
    }

    private void wireAndroidVersionStepper() {
        androidVersionValue = findViewById(R.id.android_version_value);
        androidVersionMinus = findViewById(R.id.android_version_minus);
        androidVersionPlus = findViewById(R.id.android_version_plus);

        androidVersionIdx = AndroidVersionTable.currentIndex();
        renderAndroidVersion();

        androidVersionMinus.setOnClickListener(v -> stepAndroidVersion(-1));
        androidVersionPlus.setOnClickListener(v -> stepAndroidVersion(+1));
    }

    private void stepAndroidVersion(int delta) {
        int next = androidVersionIdx + delta;
        if (next < 0 || next >= AndroidVersionTable.size()) return;
        androidVersionIdx = next;
        AndroidVersionTable.Entry entry = AndroidVersionTable.get(next);
        ConfigManager.setProperties(AndroidVersionTable.buildUpdates(entry));
        renderAndroidVersion();
        schedulePersist();
    }

    private void renderAndroidVersion() {
        if (androidVersionValue == null) return;
        AndroidVersionTable.Entry entry = AndroidVersionTable.get(androidVersionIdx);
        androidVersionValue.setText(entry.displayName);
        androidVersionMinus.setEnabled(androidVersionIdx > 0);
        androidVersionPlus.setEnabled(androidVersionIdx < AndroidVersionTable.size() - 1);
    }

    private static final String TIMEZONE_SEEDED_FLAG = "_tz_seeded";
    private static final String LEGACY_DEFAULT_TIMEZONE = "America/Los_Angeles";

    private void populateTimezoneIfMissing() {
        String current = ConfigManager.getRawProperty(TIMEZONE_PROPERTY);
        String seeded = ConfigManager.getRawProperty(TIMEZONE_SEEDED_FLAG);
        boolean isEmpty = current == null || current.isEmpty();
        boolean isLegacyDefaultUnseeded = !"1".equals(seeded)
                && LEGACY_DEFAULT_TIMEZONE.equals(current);
        if (isEmpty || isLegacyDefaultUnseeded) {
            Map<String, String> updates = new HashMap<>();
            updates.put(TIMEZONE_PROPERTY, TimeZone.getDefault().getID());
            updates.put(TIMEZONE_SEEDED_FLAG, "1");
            ConfigManager.setProperties(updates);
            schedulePersist();
        }
    }

    private void wireTimezonePicker() {
        View card = findViewById(R.id.timezone_card);
        timezoneValue = findViewById(R.id.timezone_value);
        renderTimezoneValue();
        card.setOnClickListener(v -> showTimezonePicker());
    }

    private void renderTimezoneValue() {
        if (timezoneValue == null) return;
        String tz = ConfigManager.getRawProperty(TIMEZONE_PROPERTY);
        timezoneValue.setText(tz == null || tz.isEmpty() ? "—" : tz);
    }

    private void showTimezonePicker() {
        final List<String> ids = new ArrayList<>(Arrays.asList(TimeZone.getAvailableIDs()));
        Collections.sort(ids, String.CASE_INSENSITIVE_ORDER);
        String current = ConfigManager.getRawProperty(TIMEZONE_PROPERTY);

        showSearchPicker(R.string.timezone_dialog_title, ids, ids.indexOf(current), index -> {
            Map<String, String> updates = new HashMap<>();
            updates.put(TIMEZONE_PROPERTY, ids.get(index));
            ConfigManager.setProperties(updates);
            schedulePersist();
            renderTimezoneValue();
        });
    }

    private static final String LOCALE_SEEDED_FLAG = "_locale_seeded";
    private static final String LEGACY_DEFAULT_LANGUAGE = "en";
    private static final String LEGACY_DEFAULT_COUNTRY = "US";

    private void populateLocaleIfMissing() {
        String language = ConfigManager.getRawProperty(LOCALE_LANGUAGE_PROPERTY);
        String country = ConfigManager.getRawProperty(LOCALE_COUNTRY_PROPERTY);
        String seeded = ConfigManager.getRawProperty(LOCALE_SEEDED_FLAG);
        boolean isEmpty = language.isEmpty();
        boolean isLegacyDefaultUnseeded = !"1".equals(seeded)
                && LEGACY_DEFAULT_LANGUAGE.equals(language)
                && LEGACY_DEFAULT_COUNTRY.equals(country);
        // LocaleHooks skips the module's own process, so this is the real locale.
        Locale real = Locale.getDefault();
        if ((isEmpty || isLegacyDefaultUnseeded) && !real.getLanguage().isEmpty()) {
            Map<String, String> updates = localeUpdates(real);
            updates.put(LOCALE_SEEDED_FLAG, "1");
            ConfigManager.setProperties(updates);
            schedulePersist();
        }
    }

    private void wireLanguagePicker() {
        View card = findViewById(R.id.language_card);
        languageValue = findViewById(R.id.language_value);
        renderLanguageValue();
        card.setOnClickListener(v -> showLanguagePicker());
    }

    private void renderLanguageValue() {
        if (languageValue == null) return;
        String tag = currentLocaleTag();
        languageValue.setText(tag.isEmpty() ? "—" : tag);
    }

    // Same tag WebViewHooks reports as navigator.language.
    private static String currentLocaleTag() {
        String language = ConfigManager.getRawProperty(LOCALE_LANGUAGE_PROPERTY);
        String country = ConfigManager.getRawProperty(LOCALE_COUNTRY_PROPERTY);
        return language.isEmpty() || country.isEmpty() ? language : language + "-" + country;
    }

    private void showLanguagePicker() {
        // LocaleHooks applies language + country only, so script variants
        // (zh-Hans-CN, sr-Latn-RS) fold into one entry per base locale.
        final Map<String, Locale> byLabel = new HashMap<>();
        for (Locale l : Locale.getAvailableLocales()) {
            if (l.getLanguage().isEmpty() || l.getCountry().isEmpty()) continue;
            Locale base = new Locale(l.getLanguage(), l.getCountry());
            byLabel.put(base.getDisplayName(base) + " · " + base.toLanguageTag(), base);
        }
        final List<String> labels = new ArrayList<>(byLabel.keySet());
        Collections.sort(labels, Collator.getInstance());

        String current = currentLocaleTag();
        int checkedIdx = -1;
        for (int i = 0; i < labels.size(); i++) {
            if (byLabel.get(labels.get(i)).toLanguageTag().equals(current)) {
                checkedIdx = i;
                break;
            }
        }

        showSearchPicker(R.string.language_dialog_title, labels, checkedIdx, index -> {
            ConfigManager.setProperties(localeUpdates(byLabel.get(labels.get(index))));
            schedulePersist();
            renderLanguageValue();
        });
    }

    // Keeps language + country only (what LocaleHooks applies). The language
    // subtag comes from the tag: getLanguage() can report legacy iw/in/ji.
    private static Map<String, String> localeUpdates(Locale locale) {
        String tag = new Locale(locale.getLanguage(), locale.getCountry()).toLanguageTag();
        int dash = tag.indexOf('-');
        Map<String, String> updates = new HashMap<>();
        updates.put(LOCALE_LANGUAGE_PROPERTY, dash < 0 ? tag : tag.substring(0, dash));
        updates.put(LOCALE_COUNTRY_PROPERTY, locale.getCountry());
        updates.put(LOCALE_TAG_PROPERTY, tag);
        return updates;
    }

    // A switch bound to a 0 / 1 config flag.
    private void wireFlagSwitch(int switchId, String property, boolean checked) {
        MaterialSwitch sw = findViewById(switchId);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((btn, isChecked) -> {
            Map<String, String> updates = new HashMap<>();
            updates.put(property, isChecked ? "1" : "0");
            ConfigManager.setProperties(updates);
            schedulePersist();
        });
    }

    // Searchable single-choice list. onPicked gets the index into labels, so
    // labels must be unique.
    private void showSearchPicker(int titleRes, List<String> labels, int checkedIdx,
                                  IntConsumer onPicked) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_search_picker, null);
        EditText search = dialogView.findViewById(R.id.search);
        ListView list = dialogView.findViewById(R.id.list);

        final List<String> all = new ArrayList<>(labels);
        final String checkedLabel = checkedIdx >= 0 && checkedIdx < all.size()
                ? all.get(checkedIdx) : null;

        // ArrayAdapter's default filter is startsWith; override with contains.
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, new ArrayList<>(all)) {
            private final Filter substringFilter = new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    if (constraint == null || constraint.length() == 0) {
                        results.values = new ArrayList<>(all);
                        results.count = all.size();
                        return results;
                    }
                    String needle = constraint.toString().toLowerCase(Locale.ROOT);
                    List<String> matches = new ArrayList<>();
                    for (String label : all) {
                        if (label.toLowerCase(Locale.ROOT).contains(needle)) {
                            matches.add(label);
                        }
                    }
                    results.values = matches;
                    results.count = matches.size();
                    return results;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    clear();
                    if (results.values instanceof List) {
                        addAll((List<String>) results.values);
                    }
                    notifyDataSetChanged();
                    // The checked state is positional; keep it on the current value.
                    list.clearChoices();
                    int pos = checkedLabel == null ? -1 : getPosition(checkedLabel);
                    if (pos >= 0) list.setItemChecked(pos, true);
                }
            };

            @Override
            public Filter getFilter() {
                return substringFilter;
            }
        };
        list.setAdapter(adapter);

        if (checkedLabel != null) {
            list.setItemChecked(checkedIdx, true);
            list.setSelection(checkedIdx);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(dialogView)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                adapter.getFilter().filter(s);
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            String chosen = adapter.getItem(position);
            int index = chosen == null ? -1 : all.indexOf(chosen);
            if (index >= 0) {
                onPicked.accept(index);
            }
            dialog.dismiss();
        });

        dialog.show();
        // Force MATCH_PARENT so the IME doesn't collapse the list to zero height.
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void buildSections() {
        LinearLayout container = findViewById(R.id.section_container);
        LayoutInflater inflater = LayoutInflater.from(this);

        String currentCategory = null;
        LinearLayout currentCardBody = null;

        for (IdentifierRegistry.Definition d : IdentifierRegistry.all()) {
            IdentifierItem item = items.get(d.id);
            if (item == null) continue;

            if (!d.category.equals(currentCategory)) {
                currentCategory = d.category;

                TextView header = (TextView) inflater.inflate(
                        R.layout.section_header, container, false);
                header.setText(d.category.toUpperCase(Locale.ROOT));
                container.addView(header);

                MaterialCardView card = new MaterialCardView(this);
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                card.setLayoutParams(cardLp);
                card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface));
                card.setRadius(dpToPx(16));
                card.setCardElevation(0f);
                card.setStrokeColor(ContextCompat.getColor(this, R.color.divider));
                card.setStrokeWidth((int) dpToPx(1));

                currentCardBody = new LinearLayout(this);
                currentCardBody.setOrientation(LinearLayout.VERTICAL);
                card.addView(currentCardBody, new MaterialCardView.LayoutParams(
                        MaterialCardView.LayoutParams.MATCH_PARENT,
                        MaterialCardView.LayoutParams.WRAP_CONTENT));

                container.addView(card);
            } else if (currentCardBody != null) {
                currentCardBody.addView(buildDivider());
            }

            if (currentCardBody == null) continue;
            View row = inflater.inflate(R.layout.item_identifier, currentCardBody, false);
            bindRow(row, item, d);
            currentCardBody.addView(row);
            rowViews.put(d.id, row);
        }
    }

    private View buildDivider() {
        View divider = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) dpToPx(1)));
        lp.setMarginStart((int) dpToPx(20));
        lp.setMarginEnd((int) dpToPx(20));
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.divider));
        return divider;
    }

    private void bindRow(View row, IdentifierItem item, IdentifierRegistry.Definition def) {
        TextView name = row.findViewById(R.id.identifier_name);
        TextView value = row.findViewById(R.id.identifier_value);
        View invalidRow = row.findViewById(R.id.invalid_row);
        MaterialSwitch sw = row.findViewById(R.id.identifier_switch);
        MaterialButton copy = row.findViewById(R.id.identifier_copy);
        MaterialButton edit = row.findViewById(R.id.identifier_edit);
        MaterialButton randomize = row.findViewById(R.id.identifier_randomize);

        name.setText(item.displayName);
        value.setText(item.currentValue == null ? "" : item.currentValue);
        invalidRow.setVisibility(def.isValid(item.currentValue) ? View.GONE : View.VISIBLE);

        sw.setOnCheckedChangeListener(null);
        sw.setChecked(item.enabled);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (item.enabled == checked) return;
            item.enabled = checked;
            ConfigManager.setIdentifierEnabled(item.id, checked);
            schedulePersist();
            refreshSelectAllState();
        });

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(item.displayName,
                        item.currentValue == null ? "" : item.currentValue));
                Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show();
            }
        });

        edit.setOnClickListener(v -> showEditDialog(item, def));

        randomize.setOnClickListener(v -> updateValue(item, def, def.generator.generate()));
    }

    private void showEditDialog(IdentifierItem item, IdentifierRegistry.Definition def) {
        EditText input = new EditText(this);
        input.setText(item.currentValue);
        input.setTypeface(Typeface.MONOSPACE);
        if (item.currentValue != null) input.setSelection(item.currentValue.length());

        FrameLayout container = new FrameLayout(this);
        int padH = (int) dpToPx(20);
        int padV = (int) dpToPx(8);
        container.setPadding(padH, padV, padH, padV);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.edit_dialog_title, item.displayName))
                .setView(container)
                .setPositiveButton(R.string.dialog_save, (d, w) ->
                        updateValue(item, def, input.getText().toString()))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void updateValue(IdentifierItem item, IdentifierRegistry.Definition def, String newVal) {
        item.currentValue = newVal;
        ConfigManager.setIdentifierValue(item.id, newVal);
        schedulePersist();

        View row = rowViews.get(item.id);
        if (row == null) return;
        TextView value = row.findViewById(R.id.identifier_value);
        View invalidRow = row.findViewById(R.id.invalid_row);
        value.setText(newVal == null ? "" : newVal);
        invalidRow.setVisibility(def.isValid(newVal) ? View.GONE : View.VISIBLE);
    }

    private void syncRowSwitches() {
        for (IdentifierItem item : items.values()) {
            View row = rowViews.get(item.id);
            if (row == null) continue;
            MaterialSwitch sw = row.findViewById(R.id.identifier_switch);
            if (sw != null && sw.isChecked() != item.enabled) {
                sw.setChecked(item.enabled);
            }
        }
    }

    private boolean allEnabled() {
        for (IdentifierItem item : items.values()) if (!item.enabled) return false;
        return true;
    }

    private void refreshSelectAllState() {
        silentSetSelectAll(allEnabled());
    }

    private void silentSetSelectAll(boolean checked) {
        if (selectAllSwitch == null) return;
        selectAllSwitch.setOnCheckedChangeListener(null);
        selectAllSwitch.setChecked(checked);
        selectAllSwitch.setOnCheckedChangeListener(selectAllListener);
    }

    private void schedulePersist() {
        debounceHandler.removeCallbacks(persistRunnable);
        debounceHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS);
    }

    // Writes the live config to disk + a world-readable SharedPreferences (the
    // bootstrap seed target apps read via XSharedPreferences), then pushes it to
    // RemotePreferences over the writable IXposedService binder. Runs on
    // persistExecutor, so the binder commit stays off the main thread.
    private void doPersist() {
        try {
            ConfigManager.saveConfig(configFile);
        } catch (IOException e) {
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.save_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show());
        }
        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, String> e : ConfigManager.getRawProperties().entrySet()) {
            editor.putString(e.getKey(), e.getValue());
        }
        editor.apply();
        makePrefsWorldReadable();
        publishIfWritable();
    }

    // Publishes the in-memory config to RemotePreferences when the writable
    // binder is bound; a no-op otherwise. Always invoked on persistExecutor.
    private void publishIfWritable() {
        if (XposedServiceBridge.isServiceWritable()) {
            ConfigManager.publishToRemotePreferences();
        }
    }

    private void makePrefsWorldReadable() {
        try {
            File dataDir = new File(getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, "config.xml");
            if (prefsFile.exists()) prefsFile.setReadable(true, false);
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false);
                prefsDir.setReadable(true, false);
            }
            dataDir.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
    }

    // A config saved by 1.1 / 1.2 still carries that version's defaults. Runs
    // once; a fresh install has nothing to move and only gets the flag.
    private void migrateLegacyDefaults() {
        if ("1".equals(ConfigManager.getRawProperty(DEFAULTS_MIGRATED_FLAG))) return;
        Map<String, String> updates = ConfigManager.getLegacyDefaultUpdates();
        updates.put(DEFAULTS_MIGRATED_FLAG, "1");
        ConfigManager.setProperties(updates);
    }

    private void populateMissingValues() {
        boolean dirty = false;
        for (IdentifierRegistry.Definition d : IdentifierRegistry.all()) {
            String value = ConfigManager.getIdentifierValue(d.id);
            if (value == null || value.isEmpty()) {
                ConfigManager.setIdentifierValue(d.id, d.generator.generate());
                dirty = true;
            }
        }
        if (dirty) {
            try {
                ConfigManager.saveConfig(configFile);
            } catch (IOException ignored) {
            }
        }
    }

    private void ensureConfigFile() {
        if (configFile.exists()) return;
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(ConfigManager.getDefaultConfigText().getBytes());
            fos.flush();
        } catch (IOException ignored) {
        }
    }

    private float dpToPx(int dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
