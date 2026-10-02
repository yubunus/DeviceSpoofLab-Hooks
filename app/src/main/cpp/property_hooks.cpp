#include "ds_state.h"

#include <elf.h>
#include <link.h>
#include <sys/system_properties.h>
#include <cstring>
#include <memory>
#include <mutex>
#include <set>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <vector>

#include <lsplt.hpp>

namespace ds {

namespace {

int  (*orig_sp_get)(const char*, char*) = nullptr;

const prop_info* (*orig_sp_find)(const char*) = nullptr;

int  (*orig_sp_read)(const prop_info*, char*, char*) = nullptr;

void (*orig_sp_read_callback)(
        const prop_info*,
        void (*)(void*, const char*, const char*, uint32_t),
        void*) = nullptr;

// Opaque prop_info* we return from __system_property_find for spoofed keys.
// The real layout is libc-internal; our read hook recognises these pointers
// and short-circuits before they reach the real read code.
struct SyntheticPi {
    std::string name;
};

std::mutex g_synth_mutex;
std::vector<std::unique_ptr<SyntheticPi>> g_synth_storage;
std::unordered_map<const prop_info*, SyntheticPi*> g_synth_index;
std::unordered_map<std::string, const prop_info*> g_synth_by_name;

bool IsSynthetic(const prop_info* pi, std::string& nameOut) {
    if (pi == nullptr) return false;
    std::lock_guard<std::mutex> lk(g_synth_mutex);
    auto it = g_synth_index.find(pi);
    if (it == g_synth_index.end()) return false;
    nameOut = it->second->name;
    return true;
}

const prop_info* MakeSynthetic(const char* name) {
    if (name == nullptr) return nullptr;
    std::lock_guard<std::mutex> lk(g_synth_mutex);
    auto existing = g_synth_by_name.find(name);
    if (existing != g_synth_by_name.end()) {
        return existing->second;
    }

    auto p = std::make_unique<SyntheticPi>();
    p->name = name;
    const prop_info* opaque = reinterpret_cast<const prop_info*>(p.get());

    g_synth_index.emplace(opaque, p.get());
    g_synth_by_name.emplace(p->name, opaque);
    g_synth_storage.push_back(std::move(p));
    return opaque;
}

int my_sp_get(const char* name, char* value) {
    std::string spoofed;
    if (LookupProperty(name, spoofed)) {
        size_t n = spoofed.size();
        if (n > PROP_VALUE_MAX - 1) n = PROP_VALUE_MAX - 1;
        if (value != nullptr) {
            memcpy(value, spoofed.data(), n);
            value[n] = '\0';
        }
        return (int)n;
    }
    if (orig_sp_get) return orig_sp_get(name, value);
    return 0;
}

const prop_info* my_sp_find(const char* name) {
    if (name != nullptr) {
        std::string spoofed;
        if (LookupProperty(name, spoofed)) {
            // An empty value stands for a property the device doesn't have.
            if (spoofed.empty()) return nullptr;
            return MakeSynthetic(name);
        }
    }
    if (orig_sp_find) return orig_sp_find(name);
    return nullptr;
}

int my_sp_read(const prop_info* pi, char* name, char* value) {
    std::string synthName;
    if (IsSynthetic(pi, synthName)) {
        std::string spoofed;
        if (LookupProperty(synthName.c_str(), spoofed)) {
            if (name != nullptr) {
                size_t nlen = synthName.size();
                if (nlen > PROP_NAME_MAX - 1) nlen = PROP_NAME_MAX - 1;
                memcpy(name, synthName.data(), nlen);
                name[nlen] = '\0';
            }
            size_t vlen = spoofed.size();
            if (vlen > PROP_VALUE_MAX - 1) vlen = PROP_VALUE_MAX - 1;
            if (value != nullptr) {
                memcpy(value, spoofed.data(), vlen);
                value[vlen] = '\0';
            }
            return (int)vlen;
        }
    }

    int rc = 0;
    if (orig_sp_read != nullptr) {
        rc = orig_sp_read(pi, name, value);
    }
    if (name != nullptr && value != nullptr) {
        std::string spoofed;
        if (LookupProperty(name, spoofed)) {
            size_t vlen = spoofed.size();
            if (vlen > PROP_VALUE_MAX - 1) vlen = PROP_VALUE_MAX - 1;
            memcpy(value, spoofed.data(), vlen);
            value[vlen] = '\0';
            return (int)vlen;
        }
    }
    return rc;
}

// read_callback invokes the user callback synchronously, so this can live on
// the caller's stack.
struct CbWrapper {
    void (*orig)(void*, const char*, const char*, uint32_t);
    void* orig_cookie;
    const char* synth_name;
};

void CbTrampoline(void* cookie, const char* name, const char* value,
                  uint32_t serial) {
    auto* w = reinterpret_cast<CbWrapper*>(cookie);
    const char* effective_name = (w->synth_name != nullptr) ? w->synth_name : name;

    std::string spoofed;
    if (LookupProperty(effective_name, spoofed)) {
        w->orig(w->orig_cookie, effective_name, spoofed.c_str(), serial);
        return;
    }
    w->orig(w->orig_cookie, name, value, serial);
}

void my_sp_read_callback(const prop_info* pi,
                         void (*callback)(void*, const char*, const char*,
                                          uint32_t),
                         void* cookie) {
    if (callback == nullptr) {
        if (orig_sp_read_callback) orig_sp_read_callback(pi, callback, cookie);
        return;
    }

    std::string synthName;
    if (IsSynthetic(pi, synthName)) {
        std::string spoofed;
        if (LookupProperty(synthName.c_str(), spoofed)) {
            callback(cookie, synthName.c_str(), spoofed.c_str(), /*serial=*/0);
            return;
        }
    }

    CbWrapper w{};
    w.orig = callback;
    w.orig_cookie = cookie;
    w.synth_name = nullptr;

    if (orig_sp_read_callback) {
        orig_sp_read_callback(pi, &CbTrampoline, &w);
    }
}

// Libraries we have already registered hooks for, keyed by dev+inode+offset so
// each ELF is processed once. offset distinguishes libraries packed inside the
// same .apk (they share dev+inode).
std::mutex g_hooked_mutex;
std::set<std::tuple<dev_t, ino_t, uintptr_t>> g_hooked_libs;
// Loaded libraries already looked at (header address + name), so a pass that
// finds nothing new reads no /proc/self/maps.
std::set<std::pair<uintptr_t, std::string>> g_seen_libs;

// We hook the PLT/GOT of the *caller* library, not libc's own. An app reads a
// property by calling libc's __system_property_get through its own library's
// GOT slot; patching libc's internal GOT never sees that call and, worse,
// remaps libc's hot data segment out from under the running JIT/GC threads
// (SIGSEGV). So we target the app's own native libraries only.
//
bool HasPrefix(const std::string& s, const char* prefix) {
    return s.compare(0, strlen(prefix), prefix) == 0;
}

// The app's own == a library under the install directory or a data directory
// of the package this process runs as (pkg, "app.package" in the settings).
// That covers both an extracted .so and a library loaded straight out of the
// .apk/.jar (extractNativeLibs=false, the modern default and split APKs),
// which the linker names "<archive>!/lib/<abi>/<name>.so". Excluded on purpose:
//   * system/apex/vendor libs — read through the Java layer (already hooked),
//     and remapping their live code is what crashes the process;
//   * other packages' code loaded into the process, which sits under the same
//     /data roots: the WebView (/data/app/.../com.google.android.webview-...)
//     and Play services modules. They are as busy as a system library;
//   * ART/OAT artifacts (/oat/*.odex) — the runtime loads these through the
//     linker too, but they are compiled dex, not something an app calls libc
//     through, and they are large enough that walking them for a PLT faults;
//   * root-module libs (/data/adb/...) and our own module libs.
bool IsAppLibPath(const std::string& path, const std::string& pkg) {
    if (HasPrefix(path, "/data/app/")) {
        // /data/app/[~~<random>==/]<package>-<random>==/...
        if (path.find("/" + pkg + "-") == std::string::npos) return false;
    } else if (HasPrefix(path, "/data/data/") || HasPrefix(path, "/data/user/") ||
               HasPrefix(path, "/data/user_de/")) {
        if (path.find("/" + pkg + "/") == std::string::npos) return false;
    } else {
        return false;
    }
    if (path.find("/oat/") != std::string::npos) return false;
    if (path.find("ds_native") != std::string::npos) return false;
    if (path.find("liblsplt") != std::string::npos) return false;
    if (path.find("com.devicespooflab.hooks") != std::string::npos) return false;
    return true;
}

// One library the linker has loaded for the app: where its ELF header sits in
// memory, and how much of its file it spans (max PT_LOAD file end), which
// bounds the hook to just this library within a shared .apk.
struct LoadedLib {
    std::string name;
    uintptr_t header;
    size_t span;
};

struct AppLibs {
    std::string package;
    std::vector<LoadedLib> libs;
};

// dl_iterate_phdr callback: collects the app's own libraries. It runs under
// the linker's lock, so a library it sees is completely loaded and relocated
// and can't be unloaded while its program headers are read. Libraries used to
// be found by reading the ELF header of every mapping /proc/self/maps listed;
// an app maps and unmaps pieces of its .apk all the time, such a mapping can
// be gone by the time it is read, and that read crashed the app.
int CollectAppLib(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* found = static_cast<AppLibs*>(data);
    if (info->dlpi_name == nullptr || info->dlpi_phdr == nullptr) return 0;
    // Most of what is loaded is the system's: skip it without a copy.
    if (strncmp(info->dlpi_name, "/data/", 6) != 0) return 0;
    std::string name(info->dlpi_name);
    if (!IsAppLibPath(name, found->package)) return 0;
    bool has_header = false;
    uintptr_t header = 0;
    uintptr_t max_end = 0;
    for (size_t i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD) continue;
        if (ph.p_offset == 0) {
            // The segment that maps the start of the file holds the ELF header.
            header = static_cast<uintptr_t>(info->dlpi_addr + ph.p_vaddr);
            has_header = true;
        }
        uintptr_t seg_end = static_cast<uintptr_t>(ph.p_offset) +
                            static_cast<uintptr_t>(ph.p_filesz);
        if (seg_end > max_end) max_end = seg_end;
    }
    if (has_header && max_end != 0) {
        found->libs.push_back({std::move(name), header, static_cast<size_t>(max_end)});
    }
    return 0;
}

}  // namespace

void RegisterSymbolsForLibrary(dev_t dev, ino_t inode, uintptr_t offset,
                               size_t size) {
    lsplt::RegisterHook(dev, inode, offset, size, "__system_property_get",
                        reinterpret_cast<void*>(&my_sp_get),
                        reinterpret_cast<void**>(&orig_sp_get));
    lsplt::RegisterHook(dev, inode, offset, size, "__system_property_find",
                        reinterpret_cast<void*>(&my_sp_find),
                        reinterpret_cast<void**>(&orig_sp_find));
    lsplt::RegisterHook(dev, inode, offset, size, "__system_property_read",
                        reinterpret_cast<void*>(&my_sp_read),
                        reinterpret_cast<void**>(&orig_sp_read));
    lsplt::RegisterHook(dev, inode, offset, size, "__system_property_read_callback",
                        reinterpret_cast<void*>(&my_sp_read_callback),
                        reinterpret_cast<void**>(&orig_sp_read_callback));
    RegisterSystemSymbols(dev, inode, offset, size);
}

size_t HookAppLibraries() {
    AppLibs found;
    // Without a package to go by, nothing counts as the app's own.
    if (LookupSetting("app.package", found.package)) {
        dl_iterate_phdr(&CollectAppLib, &found);
    }

    bool any_new = false;
    size_t total = 0;
    {
        std::lock_guard<std::mutex> lk(g_hooked_mutex);
        std::vector<const LoadedLib*> fresh;
        for (const auto& lib : found.libs) {
            if (g_seen_libs.emplace(lib.header, lib.name).second) fresh.push_back(&lib);
        }
        if (!fresh.empty()) {
            // The maps only say which file a header address belongs to, and
            // where in that file. Nothing is read through their addresses.
            auto maps = lsplt::MapInfo::Scan();
            for (const LoadedLib* lib : fresh) {
                for (const auto& m : maps) {
                    if (m.start != lib->header) continue;
                    auto key = std::make_tuple(m.dev, m.inode,
                                               static_cast<uintptr_t>(m.offset));
                    if (g_hooked_libs.insert(key).second) {
                        DS_LOGI("Hooking app lib %s (dev=%lu inode=%lu off=0x%lx size=0x%zx)",
                                lib->name.c_str(), (unsigned long)m.dev,
                                (unsigned long)m.inode, (unsigned long)m.offset, lib->span);
                        RegisterSymbolsForLibrary(m.dev, m.inode,
                                                  static_cast<uintptr_t>(m.offset), lib->span);
                        any_new = true;
                    }
                    break;
                }
            }
        }
        total = g_hooked_libs.size();
    }

    if (any_new) {
        bool committed = lsplt::CommitHook();
        DS_LOGI("HookAppLibraries: CommitHook=%d hooked_libs=%zu spoofed_keys=%zu",
                committed, total, (size_t)g_props.size());
    }
    return total;
}

}  // namespace ds
