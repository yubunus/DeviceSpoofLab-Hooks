package com.devicespooflab.hooks.ui;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// Built-in devices from assets/device_presets.json: an array of objects keyed
// like the DeviceProfile fields, plus an optional "props" object of overrides.
public final class DevicePresets {

    private static final String ASSET = "device_presets.json";

    public static List<DeviceProfile> load(Context context) {
        List<DeviceProfile> presets = new ArrayList<>();
        try (InputStream in = context.getAssets().open(ASSET)) {
            JSONArray array = new JSONArray(readFully(in));
            for (int i = 0; i < array.length(); i++) {
                presets.add(parse(array.getJSONObject(i)));
            }
        } catch (IOException | JSONException e) {
            android.util.Log.w("DeviceSpoofLab",
                    "Failed to load " + ASSET + ": " + e.getMessage());
        }
        return presets;
    }

    private static DeviceProfile parse(JSONObject o) throws JSONException {
        DeviceProfile p = new DeviceProfile();
        p.brand = o.optString("brand");
        p.manufacturer = o.optString("manufacturer");
        p.model = o.optString("model");
        p.marketName = o.optString("marketName");
        p.name = o.optString("name");
        p.device = o.optString("device");
        p.board = o.optString("board");
        p.hardware = o.optString("hardware");
        p.platform = o.optString("platform");
        p.socManufacturer = o.optString("socManufacturer");
        p.socModel = o.optString("socModel");
        p.buildId = o.optString("buildId");
        p.incremental = o.optString("incremental");
        p.securityPatch = o.optString("securityPatch");
        p.release = o.optString("release");
        p.sdk = o.optInt("sdk");
        p.fingerprint = o.optString("fingerprint");
        p.characteristics = o.optString("characteristics");
        p.bootloaderPrefix = o.optString("bootloaderPrefix");
        p.gpuVendor = o.optString("gpuVendor");
        p.gpuRenderer = o.optString("gpuRenderer");
        p.egl = o.optString("egl");
        p.vulkan = o.optString("vulkan");
        p.screenW = o.optInt("screenW");
        p.screenH = o.optInt("screenH");
        p.density = o.optInt("density");
        p.memoryKb = o.optLong("memoryKb");
        p.cpuCores = o.optInt("cpuCores");
        JSONObject props = o.optJSONObject("props");
        if (props != null) {
            for (Iterator<String> it = props.keys(); it.hasNext(); ) {
                String key = it.next();
                p.props.put(key, props.getString(key));
            }
        }
        return p;
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }

    private DevicePresets() {}
}
