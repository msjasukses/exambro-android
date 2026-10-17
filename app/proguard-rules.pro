-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Custom view yang dirujuk dari layout XML
-keep class com.exbro.browser.ui.SecureWebView { public <init>(...); }

# Receiver device admin dirujuk dari AndroidManifest & sistem
-keep class com.exbro.browser.admin.ExamDeviceAdminReceiver { *; }

# Konstruktor View agar inflate dari XML tetap bekerja setelah R8
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
