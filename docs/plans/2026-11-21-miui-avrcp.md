# MIUI 12.5 Mi 10S playback crash

On the affected phone, a clean Win11 playback test at 2026-11-21 11:35:23 +0800
aborted com.android.bluetooth in list_begin because its list argument was NULL.
The call chain was handle_notification_response -> handle_avk_rc_metamsg_rsp ->
btif_av_state_started_handler. Bluetooth then restarted and lost the connection.
This reproduction did not overlap an application role switch.

The original /system_ext/lib64/libbluetooth_qti.so has SHA-256
169d1823c3a0d6d07fd87da84632317fbc793f82e1dd956e573a057a5e652adb
and BuildId f449db94900216f3a51aa3c1f0ec2cf6. Its mini debug data identifies
handle_notification_response at 0x20c3b4. The CHANGED response path calls
list_begin on the device's pointer at offset 0x370 without a NULL check.
The list is initialized only after supported-event capability discovery.
The correspondence of that list to rc_supported_event_list is also supported
by LineageOS android_vendor_qcom_opensource_system_bt lineage-18.1 btif_rc.cc.
That source is a reference, not an exact source match to the shipped library.
The exact remote notification payload remains unknown (no HCI recording).

NativeCompatibility recognizes only this complete file hash. It copies the
library to a root-owned private directory, preserves the logging base register,
and replaces debug logging instructions in the CHANGED branch with a check
that returns through the existing epilogue when the event list is NULL.
Non-NULL lists continue through the original list processing. The result is
verified against a second complete hash before mounting. No vendor library is
bundled or redistributed. Other builds receive no binary modification.

The copy is bind-mounted in Magisk's global mount namespace while receiving.
The source role and application restore action unmount it; reboot also removes
the mount. Boot restoration reapplies it if the requested role is receiver.
An already-mapped process exits as part of the normal role transition, so
unmount uses lazy detachment. No system partition file is overwritten.

The isolated ARM64 test requires an original library supplied by its operator
and the Python unicorn package:

    python3 tests/test-native-compatibility.py /path/to/original/libbluetooth_qti.so

It checks both original/result hashes and emulates NULL and non-NULL paths,
including preservation of the device and the logging base. Physical playback,
volume, role transition and reboot results must be recorded separately.
