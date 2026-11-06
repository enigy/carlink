package com.enigy.carlink2.bridge;

/**
 * API 6. Carlink's, set with ICarlinkBridge.setAccessListener(): told whenever other apps' access
 * changes (turned on or off, an approval, a request, a handover starting or ending, who holds the
 * adapter). It carries nothing: Carlink reads getAccess() again. Add new methods at the END only.
 */
oneway interface IBridgeAccessListener {
    void onAccessChanged();
}
