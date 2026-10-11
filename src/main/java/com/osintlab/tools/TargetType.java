package com.osintlab.tools;

/** Kinds of input a tool can work on. The frontend auto-detects which one the user typed. */
public enum TargetType {
    DOMAIN, IP, URL, EMAIL, USERNAME, PHONE, COMPANY, IMAGE, FILE
}
