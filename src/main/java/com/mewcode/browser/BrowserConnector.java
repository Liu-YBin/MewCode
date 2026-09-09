package com.mewcode.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
