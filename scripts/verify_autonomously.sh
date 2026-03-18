#!/bin/bash

# KiddoLock Autonomous Verification Script
ADB="/c/Users/eanid/AppData/Local/Android/Sdk/platform-tools/adb.exe"
PACKAGE="com.kiddolock.app"

function dump_ui() {
    # echo "Capturing UI dump via stdout..."
    $ADB shell "uiautomator dump /data/local/tmp/dump.xml > /dev/null && cat /data/local/tmp/dump.xml" > /tmp/view.xml
}

function check_blocking() {
    local target_pkg=$1
    echo "Verifying if $target_pkg is blocked..."
    dump_ui
    if grep -q "com.kiddolock.app:id/ivLockIcon" /tmp/view.xml; then
        echo "SUCCESS: Blocking overlay is ACTIVE."
        return 0
    else
        echo "FAILURE: Blocking overlay NOT FOUND."
        return 1
    fi
}

function check_logs() {
    echo "Fetching recent KiddoLock logs..."
    $ADB logcat -d *:I | grep "KiddoLock" | tail -n 50
}

function get_foreground_app() {
    $ADB shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1
}

# Command dispatch
case "$1" in
    dump)
        dump_ui
        cat /tmp/view.xml
        ;;
    check)
        check_blocking "$2"
        ;;
    logs)
        check_logs
        ;;
    foreground)
        get_foreground_app
        ;;
    *)
        echo "Usage: $0 {dump|check <pkg>|logs|foreground}"
        exit 1
        ;;
esac
