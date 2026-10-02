#include "ds_state.h"

#include <cstring>
#include <ifaddrs.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <netinet/in.h>
#include <string>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <unistd.h>

#include <lsplt.hpp>

namespace ds {

namespace {

int (*orig_uname)(struct utsname*) = nullptr;
int (*orig_gethostname)(char*, size_t) = nullptr;
int (*orig_getifaddrs)(struct ifaddrs**) = nullptr;

// Each kernel.* setting replaces its field; an unset one leaves the phone's
// own. sysname and machine always stay (Linux / the process's architecture).
int my_uname(struct utsname* u) {
    int rc = orig_uname ? orig_uname(u) : -1;
    if (rc == 0 && u != nullptr) {
        std::string v;
        if (LookupSetting("kernel.osrelease", v)) {
            snprintf(u->release, sizeof(u->release), "%s", v.c_str());
        }
        if (LookupSetting("kernel.version", v)) {
            snprintf(u->version, sizeof(u->version), "%s", v.c_str());
        }
        if (LookupSetting("kernel.hostname", v)) {
            snprintf(u->nodename, sizeof(u->nodename), "%s", v.c_str());
        }
    }
    return rc;
}

int my_gethostname(char* name, size_t len) {
    std::string h;
    if (name == nullptr || len == 0 || !LookupSetting("kernel.hostname", h)) {
        return orig_gethostname ? orig_gethostname(name, len) : -1;
    }
    size_t n = h.size();
    if (n + 1 > len) n = len - 1;
    memcpy(name, h.data(), n);
    name[n] = '\0';
    return 0;
}

bool ParseMac(const std::string& s, uint8_t out[6]) {
    if (s.size() < 17) return false;
    auto hex = [](char c, int& v) {
        if (c >= '0' && c <= '9') { v = c - '0'; return true; }
        if (c >= 'a' && c <= 'f') { v = c - 'a' + 10; return true; }
        if (c >= 'A' && c <= 'F') { v = c - 'A' + 10; return true; }
        return false;
    };
    for (int i = 0; i < 6; i++) {
        int hi = 0, lo = 0;
        if (!hex(s[i*3 + 0], hi)) return false;
        if (!hex(s[i*3 + 1], lo)) return false;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return true;
}

int my_getifaddrs(struct ifaddrs** ifap) {
    int rc = orig_getifaddrs ? orig_getifaddrs(ifap) : -1;
    if (rc != 0 || ifap == nullptr || *ifap == nullptr) return rc;

    std::string wifiMac;
    std::string btMac;
    LookupSetting("wifi.mac", wifiMac);
    LookupSetting("bluetooth.mac", btMac);
    uint8_t wifiBytes[6] = {0}, btBytes[6] = {0};
    bool haveWifi = !wifiMac.empty() && ParseMac(wifiMac, wifiBytes);
    bool haveBt   = !btMac.empty()   && ParseMac(btMac,   btBytes);

    for (struct ifaddrs* it = *ifap; it != nullptr; it = it->ifa_next) {
        if (it->ifa_name == nullptr || it->ifa_addr == nullptr) continue;
        if (it->ifa_addr->sa_family != AF_PACKET) continue;
        auto* sll = reinterpret_cast<struct sockaddr_ll*>(it->ifa_addr);
        if (sll->sll_halen != 6) continue;

        const char* n = it->ifa_name;
        if (haveWifi && (strncmp(n, "wlan", 4) == 0)) {
            memcpy(sll->sll_addr, wifiBytes, 6);
        } else if (haveBt && (strncmp(n, "bt", 2) == 0 || strncmp(n, "bnep", 4) == 0)) {
            memcpy(sll->sll_addr, btBytes, 6);
        } else if (strncmp(n, "lo", 2) == 0) {
            // loopback
        } else {
            memset(sll->sll_addr, 0, 6);
        }
    }
    return rc;
}

}  // namespace

// Registers the syscall PLT hooks for one app-owned library, bounded to
// [offset, offset+size) so a library inside a shared .apk is hooked on its own.
// The caller (HookAppLibraries) commits once after registering every library.
void RegisterSystemSymbols(dev_t dev, ino_t inode, uintptr_t offset, size_t size) {
    lsplt::RegisterHook(dev, inode, offset, size, "uname",
            reinterpret_cast<void*>(&my_uname),
            reinterpret_cast<void**>(&orig_uname));
    lsplt::RegisterHook(dev, inode, offset, size, "gethostname",
            reinterpret_cast<void*>(&my_gethostname),
            reinterpret_cast<void**>(&orig_gethostname));
    lsplt::RegisterHook(dev, inode, offset, size, "getifaddrs",
            reinterpret_cast<void*>(&my_getifaddrs),
            reinterpret_cast<void**>(&orig_getifaddrs));
}

}  // namespace ds
