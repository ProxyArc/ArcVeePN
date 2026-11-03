package com.vpeen.android;

public class Location {
    public String region;
    public String countryCode;
    public String name;
    public String id;

    @Override public String toString() {
        return name + " (" + countryCode + " - " + region + ")";
    }
}
