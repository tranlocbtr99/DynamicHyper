package com.ltm.dynamichyper;

import com.ltm.dynamichyper.IPrivilegedLogCallback;

interface IPrivilegedService {
    void setLogCallback(IPrivilegedLogCallback callback);
    boolean setPackageNetworkingEnabled(int uid, boolean enabled);
}
