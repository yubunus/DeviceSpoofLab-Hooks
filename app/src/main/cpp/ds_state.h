#pragma once

#include <sys/types.h>
#include <mutex>
#include <string>
#include <unordered_map>
#include <android/log.h>

#define DS_LOG_TAG "DeviceSpoofLab-Native"
#define DS_LOGI(...) do { if (::ds::IsVerboseLoggingEnabled()) __android_log_print(ANDROID_LOG_INFO, DS_LOG_TAG, __VA_ARGS__); } while (0)
#define DS_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  DS_LOG_TAG, __VA_ARGS__)
#define DS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DS_LOG_TAG, __VA_ARGS__)

namespace ds {

// Populated once from Java; read-only after install. g_props answers property
// reads (an empty value means "reads as unset"); g_settings holds the native
// layer's own settings and is never served as a property.
extern std::unordered_map<std::string, std::string> g_props;
extern std::unordered_map<std::string, std::string> g_settings;

bool LookupProperty(const char* name, std::string& out);

// False when the setting is absent or empty: the real value then stays.
bool LookupSetting(const char* name, std::string& out);

bool IsVerboseLoggingEnabled();

// Registers property + system PLT hooks against a single loaded library
// (by dev+inode, bounded to [offset, offset+size) so a library packed inside a
// shared .apk is hooked on its own) without committing. Called once per lib.
void RegisterSymbolsForLibrary(dev_t dev, ino_t inode, uintptr_t offset,
                               size_t size);

// Asks the linker for the loaded libraries, registers hooks for any
// not-yet-hooked library of the app's own package ("app.package" in the
// settings), and commits. Safe to call repeatedly (idempotent per lib).
// Returns the number of libraries hooked so far.
size_t HookAppLibraries();

// Registers the system-call PLT hooks (uname/gethostname/getifaddrs) for one
// library. Defined in system_hooks.cpp, called from RegisterSymbolsForLibrary.
void RegisterSystemSymbols(dev_t dev, ino_t inode, uintptr_t offset, size_t size);

}  // namespace ds
