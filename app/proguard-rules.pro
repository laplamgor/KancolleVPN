# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/i069076/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
-keep class com.webeye.base.CalledByNative {}

-keepclasseswithmembers class * {
    @com.webeye.base.CalledByNative *;
}

-keepclassmembers class * {
    @com.webeye.base.CalledByNative *;
}

-keepclasseswithmembers class com.webeye.android.weproxy.** {
    native <methods>;
}

-keep class com.webeye.android.weproxy.WeService extends android.app.Service {
    public void onCreate();
    public int onStartCommand(android.content.Intent,int,int);
    public android.os.IBinder onBind(android.content.Intent);
    public void onDestroy();
    private static java.lang.String networkProxyInfoCallback();
}