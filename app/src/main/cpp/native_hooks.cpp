#include "ds_state.h"

#include <jni.h>
#include <atomic>
#include <mutex>

namespace ds {
std::unordered_map<std::string, std::string> g_props;
std::unordered_map<std::string, std::string> g_settings;
}  // namespace ds

namespace {

std::atomic<bool> g_installed{false};
std::mutex g_install_mutex;

bool ReadJavaMap(JNIEnv* env, jobject java_map,
                 std::unordered_map<std::string, std::string>& out) {
    if (java_map == nullptr) return false;

    jclass mapCls = env->FindClass("java/util/Map");
    if (mapCls == nullptr) return false;
    jmethodID entrySetMID =
        env->GetMethodID(mapCls, "entrySet", "()Ljava/util/Set;");
    if (entrySetMID == nullptr) return false;

    jobject entrySet = env->CallObjectMethod(java_map, entrySetMID);
    if (entrySet == nullptr) return false;

    jclass setCls = env->FindClass("java/util/Set");
    jmethodID iteratorMID =
        env->GetMethodID(setCls, "iterator", "()Ljava/util/Iterator;");
    jobject iterator = env->CallObjectMethod(entrySet, iteratorMID);

    jclass iterCls = env->FindClass("java/util/Iterator");
    jmethodID hasNextMID = env->GetMethodID(iterCls, "hasNext", "()Z");
    jmethodID nextMID =
        env->GetMethodID(iterCls, "next", "()Ljava/lang/Object;");

    jclass entryCls = env->FindClass("java/util/Map$Entry");
    jmethodID getKeyMID =
        env->GetMethodID(entryCls, "getKey", "()Ljava/lang/Object;");
    jmethodID getValueMID =
        env->GetMethodID(entryCls, "getValue", "()Ljava/lang/Object;");

    while (env->CallBooleanMethod(iterator, hasNextMID)) {
        jobject entry = env->CallObjectMethod(iterator, nextMID);
        if (entry == nullptr) continue;

        jstring keyJ = (jstring)env->CallObjectMethod(entry, getKeyMID);
        jstring valJ = (jstring)env->CallObjectMethod(entry, getValueMID);

        if (keyJ != nullptr && valJ != nullptr) {
            const char* keyC = env->GetStringUTFChars(keyJ, nullptr);
            const char* valC = env->GetStringUTFChars(valJ, nullptr);
            if (keyC != nullptr && valC != nullptr) {
                out.emplace(keyC, valC);
            }
            if (keyC) env->ReleaseStringUTFChars(keyJ, keyC);
            if (valC) env->ReleaseStringUTFChars(valJ, valC);
        }
        if (keyJ) env->DeleteLocalRef(keyJ);
        if (valJ) env->DeleteLocalRef(valJ);
        env->DeleteLocalRef(entry);
    }

    env->DeleteLocalRef(iterator);
    env->DeleteLocalRef(entrySet);
    return true;
}

}  // namespace

namespace ds {

bool LookupProperty(const char* name, std::string& out) {
    if (name == nullptr) return false;
    auto it = g_props.find(name);
    if (it == g_props.end()) return false;
    out = it->second;
    return true;
}

bool LookupSetting(const char* name, std::string& out) {
    if (name == nullptr) return false;
    auto it = g_settings.find(name);
    if (it == g_settings.end() || it->second.empty()) return false;
    out = it->second;
    return true;
}

bool IsVerboseLoggingEnabled() {
    auto it = g_settings.find("debug.verbose");
    return it != g_settings.end() && it->second == "1";
}

}  // namespace ds

extern "C" JNIEXPORT jint JNICALL
Java_com_devicespooflab_hooks_NativeHooks_nativeInstall(
        JNIEnv* env, jclass /*cls*/, jobject java_props, jobject java_settings) {
    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (g_installed.exchange(true)) {
        DS_LOGI("nativeInstall: already installed; ignoring");
        return 0;
    }

    std::unordered_map<std::string, std::string> tmp;
    std::unordered_map<std::string, std::string> settings;
    if (!ReadJavaMap(env, java_props, tmp) || !ReadJavaMap(env, java_settings, settings)) {
        DS_LOGE("nativeInstall: ReadJavaMap failed");
        g_installed = false;
        return -1;
    }
    ds::g_props = std::move(tmp);
    ds::g_settings = std::move(settings);
    DS_LOGI("nativeInstall: parsed %zu entries from Java map",
            (size_t)ds::g_props.size());

    // Hook whatever app-owned libs are already mapped. Most apps load their
    // native libs later, so NativeHooks re-drives this on each library load.
    ds::HookAppLibraries();
    return (jint)ds::g_props.size();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_devicespooflab_hooks_NativeHooks_nativeRehook(
        JNIEnv* /*env*/, jclass /*cls*/) {
    return (jint)ds::HookAppLibraries();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_devicespooflab_hooks_NativeHooks_nativeQuery(
        JNIEnv* env, jclass /*cls*/, jstring keyJ) {
    if (keyJ == nullptr) return nullptr;
    const char* key = env->GetStringUTFChars(keyJ, nullptr);
    if (key == nullptr) return nullptr;

    std::string out;
    bool found = ds::LookupProperty(key, out);
    env->ReleaseStringUTFChars(keyJ, key);

    if (!found) return nullptr;
    return env->NewStringUTF(out.c_str());
}
