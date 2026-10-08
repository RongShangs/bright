# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\28065\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any custom rules here that might be needed for root shell execution libraries if any
# Since we use standard java.io, usually no extra rules are needed.
-keep class brightnesslock.rongshangs.top.util.ShellUtils { *; }
-keep class brightnesslock.rongshangs.top.util.BrightnessManager { *; }
-keep class brightnesslock.rongshangs.top.hook.RearScreenModule { *; }
