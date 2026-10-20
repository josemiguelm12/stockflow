package com.stockflow.permission.application;

public class PermissionRequestNotFoundException extends RuntimeException {

    public PermissionRequestNotFoundException() {
        super("Permission request not found");
    }
}
